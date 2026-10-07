package com.example.crm.intl.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH /tickets/{id}/status 入参。
 *
 * <p>进 RESOLVED 必须带 resolution：没有处理结果的「已解决」等于没解决，
 * 两周后没人记得当初为什么关掉。Service 里强校验。</p>
 */
public record TicketStatusPayload(
        @NotBlank(message = "目标状态不能为空")
        @Pattern(regexp = "(OPEN|FOLLOWING|RESOLVED|CLOSED)", message = "不是合法的工单状态")
        String status,

        @Size(max = 500, message = "处理结果最长 500 字")
        String resolution) {
}
