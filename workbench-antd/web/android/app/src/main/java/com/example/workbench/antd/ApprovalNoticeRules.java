package com.example.workbench.antd;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 待办提醒的**纯逻辑**：台账键、挑新单、拼文案、裁剪台账。
 *
 * <p>刻意不碰任何 Android API —— 这样能直接跑 JUnit 单测。「同一条单子只提醒一次」
 * 「权限申请与花名申请各自的文案」「台账满了丢最旧的」这三条规则是这个功能最容易
 * 回归的地方（改错一次就是用户被同一条待办轰炸，或者漏提醒），锁在单测里比靠手指点靠谱。
 *
 * <p>与原生版 {@code workbench-android} 的 {@code ApprovalNotice.kt} /
 * {@code NotifyConfig.pruneKeys} 同一套规则，两端提醒行为必须一致。
 */
public final class ApprovalNoticeRules {

    /** 台账上限：超出丢最旧的。200 足够装下任何人的待办，又不会让 SharedPreferences 无限长。 */
    public static final int MAX_SEEN = 200;

    private ApprovalNoticeRules() {
    }

    /**
     * 台账键：两类待办的唯一标识，**跨类型不撞**（花名 12 与权限申请 12 是两张单子）。
     * 前缀沿用原生版的 n/p，别改——改了两端台账就对不上了。
     */
    public static String nickKey(long id) {
        return "n:" + id;
    }

    public static String permKey(long id) {
        return "p:" + id;
    }

    /** 一条待办的提醒素材。 */
    public static final class Notice {
        public final String key;
        public final String applicant;
        public final String body;

        public Notice(String key, String applicant, String body) {
            this.key = key;
            this.applicant = applicant;
            this.body = body;
        }
    }

    /** 花名申请：正文只说人话（通知栏里放 {@code oms:view} 这种权限码毫无意义）。 */
    public static Notice ofNick(long id, String nickname, String account, String newNickname) {
        String who = isBlank(nickname) ? fallback(account) : nickname;
        return new Notice(nickKey(id), who, who + " 申请把花名改为「" + newNickname + "」，等你审批");
    }

    /** 权限申请。 */
    public static Notice ofPerm(long id, String nickname, String account, String name, String code) {
        String who = isBlank(nickname) ? fallback(account) : nickname;
        String what = isBlank(name) ? code : name;
        return new Notice(permKey(id), who, who + " 申请权限「" + what + "」，等你审批");
    }

    /**
     * 挑出「这一轮还没被台账记过」的键。
     *
     * <p>台账记的是「已经知道的待办」而不是「发过通知的」，所以返回的就是**新出现**的单子。
     * 首次轮询（台账为空）会把当前待办全部当新的返回——调用方据此决定要不要发，
     * 见 {@link ApprovalsWatchService} 的「首次只补记、不补发」。
     */
    public static List<String> newKeys(List<String> current, Set<String> seen) {
        List<String> fresh = new ArrayList<>();
        for (String key : current) {
            if (!seen.contains(key)) fresh.add(key);
        }
        return fresh;
    }

    /**
     * 要不要为这批新单子发通知。
     *
     * <p>刻意与「台账是否为空」分开：判据是**有没有完成过首次轮询**。
     * 原生版（{@code workbench-android}）用的判据是「台账非空」，于是有个坑：
     * 从没待过办的审批人，首次轮询记的是空台账，之后台账一直为空，
     * 于是他来的**第一条**待办也不会提醒——直到他手上有过第二条。
     *
     * <p>「首次只补记不补发」的本意是"刚装好/刚登录别被一屏历史待办轰炸"，
     * 用一个一次性标记表达更准确，也顺带修掉上面那个坑。
     */
    public static boolean shouldNotify(boolean initialized, int freshCount) {
        return initialized && freshCount > 0;
    }

    /** 通知标题：一条说「有新的审批待办」，多条带数量（通知栏一行放得下）。 */
    public static String noticeTitle(int count) {
        return count <= 1 ? "有新的审批待办" : "有 " + count + " 条新的审批待办";
    }

    /**
     * 台账合并 + 裁剪。
     *
     * <p>空集合短路：绝大多数轮询「一条新的都没有」，不该每次都写一遍磁盘。
     * 超限时丢最旧的若干条（LinkedHashSet 保持插入序）。
     */
    public static LinkedHashSet<String> pruneKeys(LinkedHashSet<String> current, Collection<String> added) {
        if (added.isEmpty() && !current.isEmpty()) return current;
        LinkedHashSet<String> merged = new LinkedHashSet<>(current);
        merged.addAll(added);
        if (merged.size() <= MAX_SEEN) return merged;
        List<String> list = new ArrayList<>(merged);
        return new LinkedHashSet<>(list.subList(list.size() - MAX_SEEN, list.size()));
    }

    /** 解析台账串（逗号分隔）。空串返回空集合。 */
    public static LinkedHashSet<String> decodeSeen(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            String k = part.trim();
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }

    public static String encodeSeen(Collection<String> keys) {
        return String.join(",", keys);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String fallback(String s) {
        return isBlank(s) ? "有人" : s;
    }
}
