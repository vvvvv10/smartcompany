package com.example.crm.followup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FollowUpPayload(
        @NotBlank(message = "跟进内容不能为空")
        @Size(max = 1000, message = "跟进内容最长 1000 字")
        String content,

        @Size(max = 16)
        String type,

        /** 下次跟进时间，yyyy-MM-ddTHH:mm 或 yyyy-MM-dd，空表示暂不安排 */
        String nextFollowAt) {
}
