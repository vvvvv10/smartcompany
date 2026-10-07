package com.example.tms.driver;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 司机创建/更新入参。id 为空是创建，非空是更新。 */
public record DriverPayload(
        Long id,

        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名最长 64 字")
        String name,

        @Size(max = 32, message = "电话最长 32 字")
        String phone,

        @Size(max = 16)
        String status,

        @Size(max = 255, message = "备注最长 255 字")
        String remark) {
}
