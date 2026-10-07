package com.example.tms.intl.callback;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 国际域回调与定时任务的配置（M2-4 / M2-5）。
 *
 * <p><b>回调 token 为什么必须是配置而不是常量</b>：它是<b>服务端到服务端</b>的凭据，
 * 会被写进承运商的对接文档、被复制到对方的 IP 白名单配置里、也会在轮换时短暂出现在
 * 工单和聊天记录里。硬编码在代码里就意味着「泄露路径 = 泄露源码 = 泄露全部密钥」，
 * 而轮换要改代码重新发版。放配置项之后：值来自环境变量（不进 git），
 * 轮换只改环境变量重启，不必发版。</p>
 *
 * <p><b>代码与日志里都不回显这个值</b>：token 比错了只回一句「token 不匹配」，
 * 不说「你给的是 xxx」。鉴权失败的信息量对调用方应当为零——否则这个端点就成了
 * 「用一个字符一位地试出正确 token」的 oracle。</p>
 */
@ConfigurationProperties(prefix = "tms.intl")
public record TmsIntlProperties(Callback callback, Schedule schedule) {

    public TmsIntlProperties {
        callback = callback == null ? new Callback(null) : callback;
        schedule = schedule == null ? new Schedule(null, null, null) : schedule;
    }

    /**
     * @param internalToken 回调端点的 X-Internal-Token。**不设默认值**：
     *                      配错/漏配时端点必须直接拒绝（见 CarrierCallbackController），
     *                      而不是悄悄放行一个空 token 的请求。
     */
    public record Callback(String internalToken) {
    }

    /**
     * 定时推进。
     *
     * @param cron      Spring 6 位 cron（首字段是<b>秒</b>）。默认凌晨 2 点；
     *                  测试环境要 30 秒级观察到效果，就把它设成「每 20 秒一次」
     *                  （首字段为 {@code 斜杠20}，后五位全为星号——这里不写成字面量是因为
     *                  它会提前结束这段 javadoc 注释）。
     *                  做成配置项而不是写死在 @Scheduled 上，是为了让「评审验收标准
     *                  30 秒内观察到」和「生产别每 20 秒扫一次库」这两个诉求不打架。
     * @param batchSize 每轮最多推进多少票。定时任务没有人在旁边看着，
     *                  不限量的话一个卡住的全表扫描能把连接池吃光。
     * @param tenantId  定时任务用哪个租户跑。调度线程没有请求上下文，
     *                  {@code TenantContext.get()} 会落到默认租户「alibaba」——
     *                  与租户默认值重合是巧合而不是设计，显式写出来才看得见。
     */
    public record Schedule(String cron, Integer batchSize, String tenantId) {
    }
}