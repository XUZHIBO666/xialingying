package com.demo.demo.Service.scheduling.persistence;

import com.demo.demo.Service.scheduling.domain.DeliveryTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JdbcDeliveryTargetRepositoryTest {

    private JdbcDeliveryTargetRepository repo;
    private Path tempFile;
    private Instant now;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("delivery-target-test-", ".sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempFile.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new SchedulingSchemaInitializer(jdbc).init();
        repo = new JdbcDeliveryTargetRepository(jdbc);
        now = Instant.now();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempFile);
    }

    @Test
    void upsertShouldCreateNewTarget() {
        DeliveryTarget result = repo.upsert("user-1", "encrypted-token-1", now);

        assertNotNull(result.targetId(), "should generate a target ID");
        assertEquals("user-1", result.userId());
        assertEquals("encrypted-token-1", result.encryptedToken());
    }

    @Test
    void upsertShouldUpdateExistingTarget() {
        DeliveryTarget first = repo.upsert("user-1", "token-v1", now);

        Instant later = now.plusSeconds(3600);
        DeliveryTarget second = repo.upsert("user-1", "token-v2", later);

        assertEquals(first.targetId(), second.targetId(), "same user should reuse target ID");
        assertEquals("token-v2", second.encryptedToken(), "token should be updated");
        assertTrue(second.updatedAt().isAfter(first.updatedAt()), "updatedAt should advance");
    }

    @Test
    void upsertShouldCreateSeparateTargetsForDifferentUsers() {
        DeliveryTarget t1 = repo.upsert("user-1", "token-1", now);
        DeliveryTarget t2 = repo.upsert("user-2", "token-2", now);

        assertNotEquals(t1.targetId(), t2.targetId(), "different users need separate targets");
        assertEquals("user-1", t1.userId());
        assertEquals("user-2", t2.userId());
    }

    @Test
    void findByIdShouldReturnTarget() {
        DeliveryTarget created = repo.upsert("user-1", "token-1", now);
        Optional<DeliveryTarget> found = repo.findById(created.targetId());

        assertTrue(found.isPresent());
        assertEquals(created.targetId(), found.get().targetId());
        assertEquals("user-1", found.get().userId());
    }

    @Test
    void findByIdShouldReturnEmptyForUnknownId() {
        Optional<DeliveryTarget> found = repo.findById("nonexistent-id");
        assertTrue(found.isEmpty());
    }
}
