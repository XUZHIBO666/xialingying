package com.demo.demo.Service.scheduling.persistence;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent SQLite schema initializer for scheduling tables.
 *
 * <p>Uses {@code CREATE TABLE IF NOT EXISTS} / {@code CREATE INDEX IF NOT EXISTS}
 * so repeated application starts are safe.  All timestamps are stored as
 * {@code INTEGER} (UTC epoch milliseconds).  Local time, IANA zone, and
 * payload are stored as {@code TEXT}.
 *
 * <p><b>No MySQL-specific syntax.</b> Compatible with SQLite 3.x.
 */
@Slf4j
@Component
public class SchedulingSchemaInitializer {

    private final JdbcTemplate jdbc;

    public SchedulingSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        log.info("[SchedulingSchema] Initializing scheduling tables...");

        createDeliveryTargetTable();
        createScheduledTaskTable();
        createTaskExecutionTable();

        log.info("[SchedulingSchema] Initialization complete");
    }

    private void createDeliveryTargetTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS wechat_delivery_target (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    target_id       TEXT    NOT NULL UNIQUE,
                    user_id         TEXT    NOT NULL,
                    encrypted_token TEXT    NOT NULL,
                    created_at      INTEGER NOT NULL,
                    updated_at      INTEGER NOT NULL
                )
                """);
        log.debug("[SchedulingSchema] wechat_delivery_target ready");
    }

    private void createScheduledTaskTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scheduled_task (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id          TEXT    NOT NULL UNIQUE,
                    owner_target_id  TEXT    NOT NULL,
                    task_type        TEXT    NOT NULL DEFAULT 'DAILY_WEATHER',
                    status           TEXT    NOT NULL DEFAULT 'ACTIVE',
                    location         TEXT    NOT NULL,
                    local_time       TEXT    NOT NULL,
                    time_zone        TEXT    NOT NULL,
                    payload          TEXT    DEFAULT '{}',
                    next_run_at      INTEGER NOT NULL,
                    version          INTEGER NOT NULL DEFAULT 0,
                    created_at       INTEGER NOT NULL,
                    updated_at       INTEGER NOT NULL
                )
                """);

        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_scheduled_task_owner_status
                    ON scheduled_task(owner_target_id, status)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_scheduled_task_next_run
                    ON scheduled_task(next_run_at, status)
                """);

        log.debug("[SchedulingSchema] scheduled_task ready");
    }

    private void createTaskExecutionTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scheduled_task_execution (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    execution_id     TEXT    NOT NULL UNIQUE,
                    task_id          TEXT    NOT NULL,
                    scheduled_for    INTEGER NOT NULL,
                    status           TEXT    NOT NULL DEFAULT 'PENDING',
                    attempt_count    INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at  INTEGER,
                    error_code       TEXT,
                    lease_until      INTEGER,
                    created_at       INTEGER NOT NULL,
                    updated_at       INTEGER NOT NULL,
                    UNIQUE(task_id, scheduled_for)
                )
                """);

        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_execution_status_next_attempt
                    ON scheduled_task_execution(status, next_attempt_at)
                """);

        log.debug("[SchedulingSchema] scheduled_task_execution ready");
    }
}
