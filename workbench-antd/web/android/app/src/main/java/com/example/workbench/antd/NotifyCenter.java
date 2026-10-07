package com.example.workbench.antd;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * 通知的构建与投递（频道、两条通知、点击深链的 Intent）。
 *
 * <p>两个频道分开是必须的：常驻通知是「服务在跑」的低优先级提醒，用户不该被它打断；
 * 新待办才是要声音的事。合成一个频道的话，新待办会被常驻那条的优先级压住。
 *
 * <p>通知 ID 与频道名沿用原生版 {@code core/notify/Notifications.kt} 的取值，
 * 这样用户在两个 App 之间切换时通知栏不会残留两套同名频道。
 */
public final class NotifyCenter {

    public static final int FG_ID = 1001;
    public static final int NEW_APPROVAL_ID = 1002;

    public static final String CHANNEL_WATCH = "approval_watch";
    public static final String CHANNEL_APPROVAL = "approval_new";

    /** 点击通知时 MainActivity 收到的 extra：待审批单子的 id（-1 = 只进审批页不定位） */
    public static final String EXTRA_APPROVAL_ID = "approvalId";
    public static final long NO_ID = -1L;

    /** 进程内暂存的点击落点：Activity 起来之前 WebView 还没注册插件，先存着等它来取。 */
    private static volatile String pendingId = null;

    private NotifyCenter() {
    }

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel watch = new NotificationChannel(
                CHANNEL_WATCH, "审批待办监控", NotificationManager.IMPORTANCE_LOW);
        watch.setDescription("常驻通知：显示当前待审批条数");
        watch.setShowBadge(false);
        NotificationChannel approval = new NotificationChannel(
                CHANNEL_APPROVAL, "新的审批待办", NotificationManager.IMPORTANCE_HIGH);
        approval.setDescription("有人提交了花名或权限申请等你审批");
        nm.createNotificationChannel(watch);
        nm.createNotificationChannel(approval);
    }

    /** Android 13+ 要用户授权；没授权时 notify() 会被静默丢弃，所以要先判断。 */
    public static boolean canNotify(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled();
    }

    private static NotificationCompat.Builder base(Context ctx, String channel) {
        ensureChannels(ctx);
        return new NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setAutoCancel(true);
    }

    /** 常驻通知：不打开 App 也知道有没有事在等，并显示待办条数。 */
    public static Notification foreground(Context ctx, int pendingCount) {
        return base(ctx, CHANNEL_WATCH)
                .setContentTitle(pendingCount > 0 ? "待审批 " + pendingCount + " 条" : "暂无待审批")
                .setContentText("工作台正在后台盯着审批待办")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(openApp(ctx, NO_ID))
                .build();
    }

    /** 新待办通知。 */
    public static void newApprovals(Context ctx, java.util.List<ApprovalNoticeRules.Notice> items) {
        if (items.isEmpty() || !canNotify(ctx)) return;
        String title = ApprovalNoticeRules.noticeTitle(items.size());
        NotificationCompat.Builder b = base(ctx, CHANNEL_APPROVAL)
                .setContentTitle(title)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setNumber(items.size());
        // 通知栏一行放得下：单条给完整句子，多条给第一句 + 数量
        b.setContentText(items.get(0).body);
        if (items.size() > 1) {
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(items.get(0).body));
        }
        b.setContentIntent(openApp(ctx, firstIdOf(items.get(0))));
        NotificationManagerCompat.from(ctx).notify(NEW_APPROVAL_ID, b.build());
    }

    /** 台账键 `n:12` / `p:12` → 单子 id，供深链定位用。 */
    private static long firstIdOf(ApprovalNoticeRules.Notice notice) {
        int idx = notice.key.indexOf(':');
        if (idx < 0) return NO_ID;
        try {
            return Long.parseLong(notice.key.substring(idx + 1));
        } catch (NumberFormatException e) {
            return NO_ID;
        }
    }

    private static PendingIntent openApp(Context ctx, long approvalId) {
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_APPROVAL_ID, approvalId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(ctx, (int) (approvalId + 2), intent, flags);
    }

    /**
     * MainActivity.onNewIntent 把落点存进来，Web 侧插件来取。
     * 一次性消费：定位是高亮的辅助，不是标记已读，用户手动切走就没意义了。
     */
    public static void stashDeepLink(Long approvalId) {
        pendingId = String.valueOf(approvalId == null ? NO_ID : approvalId);
    }

    /** 取一次就没了；没有点击过返回 null。 */
    public static String consumeDeepLink() {
        String v = pendingId;
        pendingId = null;
        return v;
    }
}
