package com.demo.demo.Service.scheduling.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchedulingSchemaInitializerTest {

    private JdbcTemplate jdbc;
    private Path tempFile;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("scheduling-test-", ".sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempFile.toAbsolutePath());
        this.jdbc = new JdbcTemplate(ds);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempFile);
    }

    // ==================== idempotency ====================

    @Test
    void shouldCreateAllTablesOnFirstRun() {
        new SchedulingSchemaInitializer(jdbc).init();

        List<String> tables = listUserTables();
        assertTrue(tables.contains("wechat_delivery_target"), "missing wechat_delivery_target");
        assertTrue(tables.contains("scheduled_task"), "missing scheduled_task");
        assertTrue(tables.contains("scheduled_task_execution"), "missing scheduled_task_execution");
    }

    @Test
    void shouldBeIdempotentAcrossMultipleRuns() {
        SchedulingSchemaInitializer initializer = new SchedulingSchemaInitializer(jdbc);

        initializer.init();
        initializer.init();
        initializer.init();

        List<String> tables = listUserTables();
        assertEquals(3, tables.size(), "repeated init should not create duplicate tables");
    }

    // ==================== table structure ====================

    @Test
    void deliveryTargetTableShouldHaveRequiredColumns() {
        new SchedulingSchemaInitializer(jdbc).init();

        List<Map<String, Object>> cols = jdbc.queryForList(
                "PRAGMA table_info('wechat_delivery_target')");

        assertColumn(cols, "id", "INTEGER");
        assertColumn(cols, "target_id", "TEXT");
        assertColumn(cols, "user_id", "TEXT");
        assertColumn(cols, "encrypted_token", "TEXT");
        assertColumn(cols, "created_at", "INTEGER");
        assertColumn(cols, "updated_at", "INTEGER");
    }

    @Test
    void scheduledTaskTableShouldHaveRequiredColumns() {
        new SchedulingSchemaInitializer(jdbc).init();

        List<Map<String, Object>> cols = jdbc.queryForList(
                "PRAGMA table_info('scheduled_task')");

        assertColumn(cols, "id", "INTEGER");
        assertColumn(cols, "task_id", "TEXT");
        assertColumn(cols, "owner_target_id", "TEXT");
        assertColumn(cols, "task_type", "TEXT");
        assertColumn(cols, "status", "TEXT");
        assertColumn(cols, "location", "TEXT");
        assertColumn(cols, "local_time", "TEXT");
        assertColumn(cols, "time_zone", "TEXT");
        assertColumn(cols, "next_run_at", "INTEGER");
        assertColumn(cols, "version", "INTEGER");
        assertColumn(cols, "created_at", "INTEGER");
        assertColumn(cols, "updated_at", "INTEGER");
    }

    @Test
    void taskExecutionTableShouldHaveRequiredColumns() {
        new SchedulingSchemaInitializer(jdbc).init();

        List<Map<String, Object>> cols = jdbc.queryForList(
                "PRAGMA table_info('scheduled_task_execution')");

        assertColumn(cols, "id", "INTEGER");
        assertColumn(cols, "execution_id", "TEXT");
        assertColumn(cols, "task_id", "TEXT");
        assertColumn(cols, "scheduled_for", "INTEGER");
        assertColumn(cols, "status", "TEXT");
        assertColumn(cols, "attempt_count", "INTEGER");
        assertColumn(cols, "next_attempt_at", "INTEGER");
        assertColumn(cols, "error_code", "TEXT");
        assertColumn(cols, "lease_until", "INTEGER");
        assertColumn(cols, "created_at", "INTEGER");
        assertColumn(cols, "updated_at", "INTEGER");
    }

    // ==================== constraints ====================

    @Test
    void taskExecutionShouldEnforceUniqueCompositeKey() {
        new SchedulingSchemaInitializer(jdbc).init();

        long now = System.currentTimeMillis();
        // Insert first execution
        jdbc.update(
                "INSERT INTO scheduled_task_execution (execution_id, task_id, scheduled_for, status, attempt_count, created_at, updated_at) VALUES (?, ?, ?, 'PENDING', 0, ?, ?)",
                "exec-1", "task-1", now, now, now);

        // Second insert with same (task_id, scheduled_for) must fail
        assertThrows(RuntimeException.class, () -> {
            jdbc.update(
                    "INSERT INTO scheduled_task_execution (execution_id, task_id, scheduled_for, status, attempt_count, created_at, updated_at) VALUES (?, ?, ?, 'PENDING', 0, ?, ?)",
                    "exec-2", "task-1", now, now + 1, now + 1);
        }, "UNIQUE(task_id, scheduled_for) must reject duplicate (got UncategorizedSQLException from SQLite driver)");
    }

    @Test
    void deliveryTargetTargetIdShouldBeUnique() {
        new SchedulingSchemaInitializer(jdbc).init();

        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO wechat_delivery_target (target_id, user_id, encrypted_token, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                "tgt-1", "user-1", "enc-1", now, now);

        assertThrows(RuntimeException.class, () -> {
            jdbc.update(
                    "INSERT INTO wechat_delivery_target (target_id, user_id, encrypted_token, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    "tgt-1", "user-2", "enc-2", now + 1, now + 1);
        }, "UNIQUE(target_id) must reject duplicate (got UncategorizedSQLException from SQLite driver)");
    }

    // ==================== no MySQL syntax ====================

    @Test
    void shouldNotUseMysqlSpecificSyntax() {
        new SchedulingSchemaInitializer(jdbc).init();

        // Verify tables exist by inserting and querying
        long now = System.currentTimeMillis();

        jdbc.update("INSERT INTO wechat_delivery_target (target_id, user_id, encrypted_token, created_at, updated_at) VALUES (?,?,?,?,?)",
                "t1", "u1", "x", now, now);
        jdbc.update("INSERT INTO scheduled_task (task_id, owner_target_id, task_type, status, location, local_time, time_zone, next_run_at, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "task-1", "t1", "DAILY_WEATHER", "ACTIVE", "杭州", "08:00", "Asia/Shanghai", now + 86400000L, 0, now, now);
        jdbc.update("INSERT INTO scheduled_task_execution (execution_id, task_id, scheduled_for, status, attempt_count, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                "exec-1", "task-1", now, "PENDING", 0, now, now);

        // Verify INTEGER timestamps are queryable as numbers
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task WHERE next_run_at > ?", Long.class, 0L);
        assertEquals(1L, count);
    }

    // ==================== helpers ====================

    private List<String> listUserTables() {
        return jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                String.class);
    }

    private void assertColumn(List<Map<String, Object>> cols, String expectedName, String expectedTypePrefix) {
        boolean found = cols.stream().anyMatch(col ->
                expectedName.equals(col.get("name"))
                        && String.valueOf(col.get("type")).toUpperCase().startsWith(expectedTypePrefix));
        assertTrue(found,
                () -> "Column " + expectedName + " of type " + expectedTypePrefix
                      + " not found in " + cols);
    }
}
