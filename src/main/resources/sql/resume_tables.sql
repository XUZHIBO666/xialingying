-- 简历投递记录表（在 MySQL 中执行）
CREATE TABLE IF NOT EXISTS resume_application_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL COMMENT '微信用户ID',
    resume_id BIGINT NOT NULL COMMENT '关联的简历ID',
    platform VARCHAR(64) NOT NULL DEFAULT 'liepin' COMMENT '平台：liepin/boss/lagou',
    job_title VARCHAR(256) COMMENT '岗位名称',
    company_name VARCHAR(256) COMMENT '公司名称',
    job_url VARCHAR(1024) COMMENT '岗位链接',
    application_status VARCHAR(32) DEFAULT 'pending' COMMENT '状态：pending/success/failed',
    result_message TEXT COMMENT '投递结果/错误信息',
    applied_at DATETIME COMMENT '投递时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_resume_id (resume_id),
    INDEX idx_status (application_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
