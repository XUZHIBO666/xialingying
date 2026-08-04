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

-- 简历表（在 SQLite 中执行，对应 Resume 实体 @TableName("resume")）
-- 注意：实际本地运行使用 SQLite（application-local.yml: jdbc:sqlite:E:/Dev/Tools/SQLite/mydb.db）
CREATE TABLE IF NOT EXISTS resume (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id VARCHAR(128) NOT NULL,
    resume_name VARCHAR(256),
    resume_content TEXT,
    file_type VARCHAR(64),
    file_size BIGINT,
    analysis_result TEXT,
    optimized_content TEXT,
    status VARCHAR(32),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 简历投递记录表（SQLite 版，对应 ResumeApplicationRecord 实体 @TableName("resume_application_record")）
CREATE TABLE IF NOT EXISTS resume_application_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id VARCHAR(128) NOT NULL,
    resume_id BIGINT NOT NULL,
    platform VARCHAR(64) DEFAULT 'liepin',
    job_title VARCHAR(256),
    company_name VARCHAR(256),
    job_url VARCHAR(1024),
    application_status VARCHAR(32) DEFAULT 'pending',
    result_message TEXT,
    applied_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
