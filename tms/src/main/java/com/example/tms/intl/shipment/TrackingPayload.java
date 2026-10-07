package com.example.tms.intl.shipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 人工录入轨迹节点的入参。
 *
 * <p><b>source 不由前端传</b>：走网关的页面调用一律记 MANUAL。
 * 让前端传 source 等于允许「人工点的」被标成「承运商回调的」，
 * 那这一列就失去了它唯一的存在意义。承运商回调入口（M2）由服务端固定写
 * CARRIER_CALLBACK。</p>
 *
 * <p>nodeTime 是 UTC（ISO 字符串，无时区后缀），约定与服务端一致：传进来就是 UTC。</p>
 */
public record TrackingPayload(
        @NotBlank(message = "节点码不能为空")
        @Size(max = 24, message = "节点码最长 24 字")
        String nodeCode,

        @Size(max = 64, message = "节点名称最长 64 字")
        String nodeName,

        @NotNull(message = "节点时间不能为空")
        LocalDateTime nodeTime,

        @Size(max = 128, message = "节点地点最长 128 字")
        String location,

        @Size(max = 255, message = "备注最长 255 字")
        String remark) {
}
