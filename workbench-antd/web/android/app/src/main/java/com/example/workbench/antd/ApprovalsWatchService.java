package com.example.workbench.antd;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 审批待办的前台轮询服务（原生版 {@code ApprovalsWatchService} 的 Java 复刻）。
 *
 * <p><b>为什么必须放在原生而不是 WebView 里</b>：Android 会在 App 切后台后节流甚至暂停
 * WebView 里的定时器，纯前端轮询在后台基本等于没有。要「不打开 App 也收到待办」就得有个
 * 系统的执行体，所以是前台服务。
 *
 * <p><b>间隔是三档自适应的</b>（与原生版一致）：
 * <ul>
 *   <li>正常 1 分钟——审批是「盯着」的事，15 秒太费电，5 分钟又太迟</li>
 *   <li>两类审批权限都没有（普通员工）→ 5 分钟，别空转</li>
 *   <li>两路都拉失败（网络/后端问题）→ 2 分钟，退避</li>
 * </ul>
 *
 * <p><b>首次只补记、不补发</b>：用户刚登录就被一屏旧待办轰炸是最糟的第一印象，
 * 而且那些单子他在页面上已经看得见。
 *
 * <p>凭据（token / 服务地址）由 Web 侧通过 {@link NotifyPlugin} 同步进来——服务读不到
 * WebView 的 localStorage，这是壳架构下必须明确的一条边界。
 */
public class ApprovalsWatchService extends Service {

    private static final String TAG = "ApprovalsWatch";

    /** 正常轮询间隔 */
    public static final long POLL_INTERVAL = 60_000L;
    /** 两类审批权限都没有时的兜底间隔 */
    public static final long IDLE_INTERVAL = 5 * 60_000L;
    /** 两路都拉失败时的退避间隔 */
    public static final long ERROR_INTERVAL = 2 * 60_000L;
    /** 每类待办取多少条：列表按时间倒序，新单子必在第一页 */
    public static final int PAGE_SIZE = 20;
    /** 单次 HTTP 超时。两个接口并行等，取最慢的那个，所以给得比较宽。 */
    public static final int HTTP_TIMEOUT_MS = 15_000;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // 前台服务不对外提供接口，绑定请求一律回绝
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotifyCenter.ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat(NotifyCenter.foreground(this, 0));
        if (running.compareAndSet(false, true)) {
            worker = new Thread(this::pollLoop, "approvals-watch");
            worker.start();
        }
        // 被系统杀掉后不自动重启：重启前必须先有 token，否则会空转一轮再自己停
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running.set(false);
        if (worker != null) worker.interrupt();
        worker = null;
        super.onDestroy();
    }

    private void startForegroundCompat(android.app.Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotifyCenter.FG_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NotifyCenter.FG_ID, notification);
        }
    }

    private void pollLoop() {
        long interval = POLL_INTERVAL;
        while (running.get()) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Outcome outcome = pollOnce();
            interval = intervalFor(outcome);
        }
    }

    /** 三档间隔的决策抽出来，逻辑单测能直接覆盖（原生版是同一条规则）。 */
    static long intervalFor(Outcome outcome) {
        switch (outcome) {
            case NO_PERMISSION:
                return IDLE_INTERVAL;
            case ERROR:
                return ERROR_INTERVAL;
            default:
                return POLL_INTERVAL;
        }
    }

    enum Outcome {
        OK,
        NO_PERMISSION,
        ERROR,
        STOPPED
    }

    private Outcome pollOnce() {
        // 开关被关、权限没给、或者没有登录凭据：停掉自己，别空转
        if (!NotifyLedger.isEnabled(this)) {
            Log.i(TAG, "提醒开关已关，停止轮询");
            return Outcome.STOPPED;
        }
        String token = NotifyLedger.token(this);
        if (token == null || token.isEmpty()) {
            Log.i(TAG, "没有登录凭据，停止轮询");
            return Outcome.STOPPED;
        }
        if (!NotifyCenter.canNotify(this)) {
            // 没通知权限就别占着前台服务的位置（用户看不到任何东西，白耗电）
            Log.i(TAG, "没有通知权限，停止轮询");
            return Outcome.STOPPED;
        }

        String base = NotifyLedger.baseUrl(this);
        List<ApprovalNoticeRules.Notice> nick = null;
        List<ApprovalNoticeRules.Notice> perm = null;
        int forbidden = 0;
        int failed = 0;

        try {
            nick = fetchNick(base, token);
        } catch (HttpError e) {
            if (e.status == 401) {
                // token 过期：清凭据并停下，等 Web 侧刷新后重新 start
                Log.i(TAG, "token 过期，停止轮询");
                NotifyLedger.clearToken(this);
                return Outcome.STOPPED;
            }
            Log.w(TAG, "花名待办拉取失败 HTTP " + e.status);
            if (e.status == 403) forbidden++; else failed++;
        } catch (Exception e) {
            // 这里必须留日志：静默吞掉的话，"轮询一直没效果"根本无从查起
            Log.w(TAG, "花名待办拉取异常", e);
            failed++;
        }
        try {
            perm = fetchPerm(base, token);
        } catch (HttpError e) {
            if (e.status == 401) {
                Log.i(TAG, "token 过期，停止轮询");
                NotifyLedger.clearToken(this);
                return Outcome.STOPPED;
            }
            Log.w(TAG, "权限待办拉取失败 HTTP " + e.status);
            if (e.status == 403) forbidden++; else failed++;
        } catch (Exception e) {
            Log.w(TAG, "权限待办拉取异常", e);
            failed++;
        }

        if (forbidden == 2 || failed == 2) {
            Log.i(TAG, "本轮无有效数据：forbidden=" + forbidden + " failed=" + failed);
        }
        // 两类都 403 = 这个人根本不审待办（普通员工）；两路都失败 = 网络/后端问题。
        // 只挂一路时照常用另一路的数据——花名可审但没权限审权限申请是真实存在的人。
        if (forbidden == 2) return Outcome.NO_PERMISSION;
        if (failed == 2) return Outcome.ERROR;

        List<ApprovalNoticeRules.Notice> items = new ArrayList<>();
        if (nick != null) items.addAll(nick);
        if (perm != null) items.addAll(perm);

        List<String> keys = new ArrayList<>();
        for (ApprovalNoticeRules.Notice n : items) keys.add(n.key);

        LinkedHashSet<String> seen = NotifyLedger.seenNow(this);
        LinkedHashSet<String> fresh = new LinkedHashSet<>(ApprovalNoticeRules.newKeys(keys, seen));
        // 首次轮询只补记、不补发（别让用户刚装好就被历史待办轰炸）。
        // 判据是「完成过一次轮询」而不是「台账非空」——后者会让"从没待过办的审批人"
        // 永远收不到第一条提醒，单测 ApprovalNoticeRulesTest#shouldNotify 钉住这条。
        if (ApprovalNoticeRules.shouldNotify(NotifyLedger.hasInitialized(this), fresh.size())) {
            List<ApprovalNoticeRules.Notice> freshItems = new ArrayList<>();
            for (ApprovalNoticeRules.Notice n : items) {
                if (fresh.contains(n.key)) freshItems.add(n);
            }
            NotifyCenter.newApprovals(this, freshItems);
        }
        // 记「这一轮看见的全部待办」——下次台账里没有的就是新单子
        NotifyLedger.markSeen(this, keys);
        NotifyLedger.markInitialized(this);

        // 常驻通知同步待办数：不打开 App 也知道有没有事在等
        notifyForegroundCount(items.size());
        Log.i(TAG, "轮询完成：待办 " + items.size() + " 条，其中新增 " + fresh.size() + " 条");
        return Outcome.OK;
    }

    private void notifyForegroundCount(int count) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NotifyCenter.FG_ID, NotifyCenter.foreground(this, count));
        } catch (Exception e) {
            Log.w(TAG, "更新常驻通知失败", e);
        }
    }

    // ---------------- HTTP ----------------

    private static final class HttpError extends Exception {
        final int status;

        HttpError(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    private List<ApprovalNoticeRules.Notice> fetchNick(String base, String token) throws Exception {
        String url = base + "/api/admin/nickname-requests?status=PENDING&page=1&size=" + PAGE_SIZE;
        Json list = get(url, token);
        List<ApprovalNoticeRules.Notice> out = new ArrayList<>();
        for (Json row : list.array("list")) {
            out.add(ApprovalNoticeRules.ofNick(
                    row.num("id"),
                    row.str("nickname"),
                    row.str("account"),
                    row.str("newNickname")));
        }
        return out;
    }

    private List<ApprovalNoticeRules.Notice> fetchPerm(String base, String token) throws Exception {
        String url = base + "/api/admin/permission-requests?status=PENDING&page=1&size=" + PAGE_SIZE;
        Json list = get(url, token);
        List<ApprovalNoticeRules.Notice> out = new ArrayList<>();
        for (Json row : list.array("list")) {
            out.add(ApprovalNoticeRules.ofPerm(
                    row.num("id"),
                    row.str("nickname"),
                    row.str("account"),
                    row.str("permissionName"),
                    row.str("permissionCode")));
        }
        return out;
    }

    private Json get(String url, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        try {
            int status = conn.getResponseCode();
            if (status == 401 || status == 403) throw new HttpError(status);
            if (status < 200 || status >= 300) throw new HttpError(status);
            String body;
            try (InputStream in = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                body = sb.toString();
            }
            return Json.parse(body);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 极简 JSON 读取。
     *
     * <p>不引 Gson/Moshi：这个 APK 现在 4MB，为两个只读接口加序列化库不划算；
     * 而且 org.json 是 Android 自带的（api 1 就有），只是它不接受"容错"——
     * 后端少给字段时必须给默认值，所以每个取值都带兜底。
     */
    static final class Json {
        private final Object value;

        private Json(Object value) {
            this.value = value;
        }

        static Json parse(String raw) {
            try {
                return new Json(new org.json.JSONObject(raw));
            } catch (Exception e) {
                return new Json(null);
            }
        }

        private org.json.JSONObject obj() {
            return value instanceof org.json.JSONObject ? (org.json.JSONObject) value : null;
        }

        List<Json> array(String key) {
            org.json.JSONObject o = obj();
            List<Json> out = new ArrayList<>();
            if (o == null) return out;
            org.json.JSONArray arr = o.optJSONArray(key);
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                out.add(new Json(arr.opt(i)));
            }
            return out;
        }

        String str(String key) {
            org.json.JSONObject o = obj();
            if (o == null) return "";
            String s = o.optString(key, "");
            return "null".equals(s) ? "" : s;
        }

        long num(String key) {
            org.json.JSONObject o = obj();
            return o == null ? 0L : o.optLong(key, 0L);
        }
    }

    /** 供测试用：两次轮询之间的间隔。 */
    static long intervalForTest(String outcome) {
        return intervalFor(Outcome.valueOf(outcome));
    }

    /** 让测试能构造一个不依赖 Android 的循环体（避免真机 sleep）。 */
    static long now() {
        return SystemClock.elapsedRealtime();
    }
}
