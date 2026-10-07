package com.example.crm.contact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContactPayload(
        @NotBlank(message = "联系人姓名不能为空")
        @Size(max = 32, message = "姓名最长 32 字")
        String name,

        @Size(max = 32, message = "职位最长 32 字")
        String position,

        @Size(max = 32, message = "电话最长 32 字")
        String phone,

        @Size(max = 64, message = "邮箱最长 64 字")
        String email,

        Boolean isPrimary) {
}
