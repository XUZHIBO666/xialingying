package com.demo.demo.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 简历投递记录（猎聘等平台）。
 */
@Data
@TableName("resume_application_record")
public class ResumeApplicationRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("resume_id")
    private Long resumeId;

    @TableField("platform")
    private String platform;

    @TableField("job_title")
    private String jobTitle;

    @TableField("company_name")
    private String companyName;

    @TableField("job_url")
    private String jobUrl;

    @TableField("application_status")
    private String applicationStatus;

    @TableField("result_message")
    private String resultMessage;

    @TableField("applied_at")
    private LocalDateTime appliedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
