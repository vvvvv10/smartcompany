package com.example.crm.opportunity;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record OpportunityPayload(
        Long id,

        @NotNull(message = "必须指定客户")
        Long customerId,

        @NotBlank(message = "商机名称不能为空")
        @Size(max = 64, message = "商机名称最长 64 字")
        String name,

        @DecimalMin(value = "0", message = "金额不能为负数")
        BigDecimal amount,

        @Size(max = 16)
        String stage,

        Integer probability,

        String expectedCloseDate,

        @Size(max = 255, message = "备注最长 255 字")
        String remark) {
}
