package com.example.workbench.antd;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * 提醒台账 + 开关（SharedPreferences 版）。
 *
 * <p>台账记的是「**当前已知的待办键**」而不是「发过的通知」——这样同一张单子被处理掉又
 * 重新提交时会再提醒一次（键重新出现在待办里就是新的），而单纯重复轮询不会打扰用户。
 *
 * <p>为什么用 SharedPreferences 而不是 DataStore：这个 Capacitor 工程没有引入
 * androidx.datastore 依赖（就为了两个键不值得），而且 {@code apply()} 的异步写在这里够用——
 * 丢一次台账的后果只是「重启后首次轮询多提醒一轮」，不会出错。
 *
 * <p>与原生版 {@code core/store/NotifyConfig.kt} 的键名保持一致，两端互不干扰但语义相同。
 */
public final class NotifyLedger {

    private static final String PREF = "wb_notify";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SEEN = "seen";
    private static final String KEY_PERMISSION_ASKED = "permissionAsked";
    /** 是否完成过一次轮询（决定要不要补发历史待办，见 shouldNotify 的注释） */
    private static final String KEY_INITIALIZED = "initialized";
    /** 常驻服务用的登录凭据（token 由 WebView 侧同步过来，服务读不到 localStorage） */
    private static final String KEY_TOKEN = "token";
    private static final String KEY_BASE_URL = "baseUrl";

    private NotifyLedger() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static boolean isPermissionAsked(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PERMISSION_ASKED, false);
    }

    public static void markPermissionAsked(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_PERMISSION_ASKED, true).apply();
    }

    /** 冷启动同步读盘：常驻服务在首帧就要判断「开关是否开着」，不能等异步。 */
    public static LinkedHashSet<String> seenNow(Context ctx) {
        return ApprovalNoticeRules.decodeSeen(prefs(ctx).getString(KEY_SEEN, ""));
    }

    public static boolean hasInitialized(Context ctx) {
        return prefs(ctx).getBoolean(KEY_INITIALIZED, false);
    }

    public static void markInitialized(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_INITIALIZED, true).apply();
    }

    public static void markSeen(Context ctx, Collection<String> keys) {
        LinkedHashSet<String> merged =
                ApprovalNoticeRules.pruneKeys(seenNow(ctx), keys);
        prefs(ctx).edit().putString(KEY_SEEN, ApprovalNoticeRules.encodeSeen(merged)).apply();
    }

    /**
     * 退出登录时清台账：下一个人登录不该看到上一位的"已知待办"。
     *
     * <p>连 {@code initialized} 一起清——重新登录理应重新享受一次"首次不补发"，
     * 否则他登录后会被上一个人留下的待办炸一轮。
     */
    public static void clearSeen(Context ctx) {
        prefs(ctx).edit().remove(KEY_SEEN).remove(KEY_INITIALIZED).apply();
    }

    public static void saveToken(Context ctx, String token, String baseUrl) {
        prefs(ctx).edit().putString(KEY_TOKEN, token).putString(KEY_BASE_URL, baseUrl).apply();
    }

    public static void updateToken(Context ctx, String token) {
        prefs(ctx).edit().putString(KEY_TOKEN, token).apply();
    }

    /** token 过期时把凭据抹掉，服务下一轮发现没 token 就自己停。 */
    public static void clearToken(Context ctx) {
        prefs(ctx).edit().remove(KEY_TOKEN).remove(KEY_BASE_URL).apply();
    }

    public static String token(Context ctx) {
        return prefs(ctx).getString(KEY_TOKEN, "");
    }

    public static String baseUrl(Context ctx) {
        return prefs(ctx).getString(KEY_BASE_URL, "");
    }
}
