package com.demo.demo.Service.scheduling.application;

import com.demo.demo.Service.scheduling.persistence.DeliveryTargetRepository;
import com.demo.demo.Service.scheduling.persistence.JdbcDeliveryTargetRepository;
import com.demo.demo.Service.scheduling.persistence.SchedulingSchemaInitializer;
import com.demo.demo.Service.scheduling.security.ContextTokenCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryTargetServiceTest {

    private DeliveryTargetService service;
    private JdbcTemplate jdbc;
    private Path tempFile;
    private Instant now;
    private ContextTokenCipher cipher;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("delivery-target-svc-", ".sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempFile.toAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        new SchedulingSchemaInitializer(jdbc).init();
        cipher = new ContextTokenCipher("");
        service = new DeliveryTargetService(
                new JdbcDeliveryTargetRepository(jdbc), cipher);
        now = Instant.now();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempFile);
    }

    @Test
    void refreshShouldCreateTargetAndReturnId() {
        String targetId = service.refresh(
                new DeliveryTargetRefreshCommand("user-1", "token-plain-1", now));

        assertNotNull(targetId);
        assertFalse(targetId.isBlank());
    }

    @Test
    void refreshShouldReuseTargetIdOnSecondCall() {
        String id1 = service.refresh(
                new DeliveryTargetRefreshCommand("user-1", "token-v1", now));
        String id2 = service.refresh(
                new DeliveryTargetRefreshCommand("user-1", "token-v2", now.plusSeconds(60)));

        assertEquals(id1, id2, "same user should reuse target ID");
    }

    @Test
    void tokenShouldBeStoredEncrypted() {
        String targetId = service.refresh(
                new DeliveryTargetRefreshCommand("user-1", "secret-token", now));

        // Check DB directly — token must NOT be plaintext
        String encrypted = jdbc.queryForObject(
                "SELECT encrypted_token FROM wechat_delivery_target WHERE target_id = ?",
                String.class, targetId);
        assertNotNull(encrypted);
        assertNotEquals("secret-token", encrypted, "token must be encrypted in DB");
    }

    @Test
    void resolveShouldReturnDecryptedToken() {
        String token = "my-real-token";
        String targetId = service.refresh(
                new DeliveryTargetRefreshCommand("user-1", token, now));

        DeliveryTargetResolved resolved = service.resolve(targetId);
        assertEquals("user-1", resolved.userId());
        assertEquals(token, resolved.contextToken());
    }

    @Test
    void resolveShouldFailForUnknownTarget() {
        assertThrows(SchedulingException.class, () ->
                service.resolve("nonexistent-target"));
    }

    @Test
    void differentUsersShouldGetSeparateTargets() {
        String id1 = service.refresh(
                new DeliveryTargetRefreshCommand("user-a", "token-a", now));
        String id2 = service.refresh(
                new DeliveryTargetRefreshCommand("user-b", "token-b", now));

        assertNotEquals(id1, id2);
        assertEquals("token-a", service.resolve(id1).contextToken());
        assertEquals("token-b", service.resolve(id2).contextToken());
    }
}
