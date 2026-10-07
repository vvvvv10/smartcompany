package com.example.crm.intl.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 异常工单创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>ownerId 不传时取网关注入的 X-User-Id：新建工单的人默认就是跟进人，
 * 否则会出现一堆「无人负责」的工单，比「责任人是我」更难管。</p>
 */
public record TicketPayload(
        Long id,

        @Size(max = 32, message = "出口子单号最长 32 字")
        String orderNo,

        @Size(max = 32, message = "国际运单号最长 32 字")
        String shipmentNo,

        Long customerId,

        @Size(max = 128, message = "客户名称最长 128 字")
        String customerName,

        @Pattern(regexp = "(DELAY|DAMAGE|CUSTOMS_HOLD|AMEND|ABANDON|OTHER)?",
                message = "类别只能是 DELAY/DAMAGE/CUSTOMS_HOLD/AMEND/ABANDON/OTHER")
        String category,

        @Pattern(regexp = "(LOW|MEDIUM|HIGH)?", message = "严重度只能是 LOW/MEDIUM/HIGH")
        String severity,

        @NotBlank(message = "标题不能为空")
        @Size(max = 128, message = "标题最长 128 字")
        String title,

        @Size(max = 1000, message = "问题描述最长 1000 字")
        String detail,

        Long ownerId,

        @Size(max = 255, message = "跟进人姓名最长 255 字")
        String ownerName,

        LocalDateTime dueAt) {

    public TicketPayload withId(long newId) {
        return new TicketPayload(newId, orderNo, shipmentNo, customerId, customerName, category, severity,
                title, detail, ownerId, ownerName, dueAt);
    }
}
