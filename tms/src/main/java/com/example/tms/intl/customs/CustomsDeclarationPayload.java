package com.example.tms.intl.customs;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 报关单创建/更新入参。id 为空是创建，非空是更新。
 *
 * <p>status 不在这里改：走 PATCH /{id}/status。
 * 和运单同一个道理——「建单」与「推流程」分开，前端的按钮才敢做得显眼。</p>
 */
public record CustomsDeclarationPayload(
        Long id,

        @Size(max = 32, message = "报关单号最长 32 字")
        String declarationNo,

        @NotNull(message = "关联运单不能为空")
        Long shipmentId,

        @Size(max = 64, message = "报关行最长 64 字")
        String customsBroker,

        @Size(max = 32, message = "报关行联系电话最长 32 字")
        String brokerContact,

        @Size(max = 512, message = "HS 编码汇总最长 512 字")
        String hsCodeSummary,

        @DecimalMin(value = "0", message = "申报货值不能为负")
        BigDecimal declaredValue,

        @Pattern(regexp = "([A-Z]{3})?", message = "币种只能是三位 ISO 代码，如 USD")
        String currency,

        @DecimalMin(value = "0", message = "关税不能为负")
        BigDecimal dutyAmount,

        @DecimalMin(value = "0", message = "增值税不能为负")
        BigDecimal vatAmount,

        @DecimalMin(value = "0", message = "消费税不能为负")
        BigDecimal consumptionTax,

        @DecimalMin(value = "0", message = "保证金不能为负")
        BigDecimal depositAmount,

        @Pattern(regexp = "(DRAFT|FILED|RELEASED|INSPECTING|HELD|REJECTED)?",
                message = "报关状态只能是 DRAFT/FILED/RELEASED/INSPECTING/HELD/REJECTED")
        String status,

        @Size(max = 255, message = "备注最长 255 字")
        String remark) {

    public CustomsDeclarationPayload withId(long newId) {
        return new CustomsDeclarationPayload(newId, declarationNo, shipmentId, customsBroker, brokerContact,
                hsCodeSummary, declaredValue, currency, dutyAmount, vatAmount, consumptionTax,
                depositAmount, status, remark);
    }
}
