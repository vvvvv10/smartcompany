package com.example.crm.intl;

import java.math.BigDecimal;

/**
 * 国际运单域写库前的字段归一化工具。
 *
 * <p>为什么不让 Service 各自写一遍 {@code value == null ? "" : value.trim()}：
 * 这个模式在四个服务的几十个插入/更新点重复出现，漏一处就会往 NOT NULL 列写 null
 * 然后炸在数据库上（而不是在入口处）。集中一处后，漏写的后果是「编译不过」而不是
 * 「上线后某个字段莫名是空」。</p>
 */
public final class IntlFields {

    private IntlFields() {
    }

    /** null/空白 → {@code fallback}，否则 trim。用于枚举列与单号列。 */
    public static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** null → 空串（NOT NULL DEFAULT '' 的文本列）。 */
    public static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** null → 0（计数/天数/标记列）。 */
    public static int zero(Integer value) {
        return value == null ? 0 : value;
    }

    /** null → 0（金额/重量列；DECIMAL NOT NULL DEFAULT 0）。 */
    public static BigDecimal decimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
