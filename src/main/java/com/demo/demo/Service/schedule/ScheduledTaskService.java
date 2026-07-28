package com.demo.demo.Service.schedule;

import com.demo.demo.Service.AIService;
import com.demo.demo.Service.MultiBotManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class ScheduledTaskService {

    @Lazy
    @Resource
    private AIService aiService;

    @Resource
    private MultiBotManager multiBotManager;

    @Value("${ai.schedule.file:./data/scheduled-tasks.json}")
    private String filePath;

    private final ConcurrentMap<String, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> latestContextTokens = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @PostConstruct
    public void init() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "scheduled-task-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        loadFromDisk();
        tasks.values().stream()
                .filter(ScheduledTask::isPending)
                .filter(t -> !t.isPastDue())
                .forEach(this::scheduleInternal);
        log.info("[定时任务] 初始化完成，已加载 {} 个任务", tasks.size());
    }

    @PreDestroy
    public void shutdown() {
        futures.values().forEach(f -> f.cancel(false));
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("[定时任务] 调度器已关闭");
    }

    // ==================== 公开方法 ====================

    public String scheduleTask(String userId, String contextToken, String action, LocalDateTime executeAt) {
        String id = "task-" + UUID.randomUUID().toString().substring(0, 8);
        ScheduledTask task = new ScheduledTask(id, userId, contextToken, action,
                executeAt.toString());
        tasks.put(id, task);
        latestContextTokens.put(userId, contextToken);
        scheduleInternal(task);
        persistToDisk();
        log.info("[定时任务] 已创建 id={} userId={} executeAt={} action={}",
                id, maskUserId(userId), executeAt, truncate(action, 50));
        return id;
    }

    public boolean cancelTask(String taskId) {
        ScheduledTask task = tasks.get(taskId);
        if (task == null || !task.isPending()) {
            return false;
        }
        task.setStatus("cancelled");
        ScheduledFuture<?> future = futures.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
        persistToDisk();
        log.info("[定时任务] 已取消 id={}", taskId);
        return true;
    }


    public List<ScheduledTask> listActiveTasks() {
        return tasks.values().stream()
                .filter(ScheduledTask::isPending)
                .sorted(Comparator.comparing(ScheduledTask::getExecuteAt))
                .toList();
    }

    public void updateContextToken(String userId, String contextToken) {
        if (userId != null && contextToken != null) {
            latestContextTokens.put(userId, contextToken);
        }
    }

    // ==================== 内部调度 ====================

    private void scheduleInternal(ScheduledTask task) {
        LocalDateTime executeAt = LocalDateTime.parse(task.getExecuteAt());
        long delayMs = ChronoUnit.MILLIS.between(LocalDateTime.now(), executeAt);
        if (delayMs <= 0) {
            log.warn("[定时任务] 任务已过期，跳过 id={} executeAt={}", task.getId(), executeAt);
            task.setStatus("expired");
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(
                () -> executeTask(task.getId()), delayMs, TimeUnit.MILLISECONDS);
        futures.put(task.getId(), future);
        log.info("[定时任务] 已注册到调度器 id={} delayMs={}", task.getId(), delayMs);
    }

    private void executeTask(String taskId) {
        ScheduledTask task = tasks.get(taskId);
        if (task == null || !task.isPending()) {
            return;
        }

        log.info("[定时任务] 开始执行 id={} userId={} action={}",
                taskId, maskUserId(task.getUserId()), truncate(task.getAction(), 50));

        futures.remove(taskId);

        try {
            String reply = aiService.chat(task.getUserId(),
                    "[定时任务自动执行] 请完成以下任务：" + task.getAction());

            if (reply != null && !reply.isBlank()) {
                String contextToken = latestContextTokens.getOrDefault(
                        task.getUserId(), task.getContextToken());
                multiBotManager.getDefaultBot()
                        .sendReply(task.getUserId(), contextToken, reply);
            }
            task.setStatus("completed");
        } catch (Exception e) {
            log.error("[定时任务] 执行失败 id={}: {}", taskId, e.getMessage(), e);
            task.setStatus("failed");
        }
        persistToDisk();
    }

    // ==================== 持久化 ====================

    private void persistToDisk() {
        try {
            List<ScheduledTask> taskList = new ArrayList<>(tasks.values());
            String json = GSON.toJson(taskList);
            Path path = Path.of(filePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path temp = Files.createTempFile(
                    path.getParent(), path.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temp, json, StandardCharsets.UTF_8);
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            log.error("[定时任务] 持久化失败: {}", e.getMessage());
        }
    }

    private void loadFromDisk() {
        Path path = Path.of(filePath);
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String json = Files.readString(path);
            if (json.isBlank()) {
                return;
            }
            List<ScheduledTask> loaded = GSON.fromJson(json,
                    new TypeToken<List<ScheduledTask>>() {}.getType());
            if (loaded != null) {
                for (ScheduledTask t : loaded) {
                    if (t.getId() != null) {
                        tasks.put(t.getId(), t);
                    }
                }
            }
            log.info("[定时任务] 从磁盘加载 {} 个任务", loaded != null ? loaded.size() : 0);
        } catch (Exception e) {
            log.warn("[定时任务] 加载失败: {}", e.getMessage());
        }
    }
    private static String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) {
            return "***";
        }
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
