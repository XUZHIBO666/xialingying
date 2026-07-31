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
        cleanupFinishedTasks();
        tasks.values().stream()
                .filter(ScheduledTask::isPending)
                .filter(t -> !t.isPastDue())
                .forEach(this::scheduleInternal);
        log.info("[定时任务] 初始化完成，已加载 {} 个任务", tasks.size());
    }

    @PreDestroy
    public void shutdown() {
        futures.values().forEach(f -> f.cancel(false));//关闭前取消所有未执行的调度任务。cancel(false) 表示不中断正在执行的任务。
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
        }//两阶段关闭模式:先 shutdown()（等任务跑完）→ 超时后 shutdownNow()（强制中断）。
        log.info("[定时任务] 调度器已关闭");
    }

    // ==================== 公开方法 ====================

    public String scheduleTask(String userId, String contextToken, String action, LocalDateTime executeAt) {
        String id = "task-" + UUID.randomUUID().toString().substring(0, 8);//生成短ID
        ScheduledTask task = new ScheduledTask(id, userId, contextToken, action,
                executeAt.toString());//创建任务对象
        tasks.put(id, task);//存入Map
        latestContextTokens.put(userId, contextToken);//记录该用户最新的contextToken
        scheduleInternal(task);//注册到调度器
        persistToDisk();//持久化
        log.info("[定时任务] 已创建 id={} userId={} executeAt={} action={}",
                id, maskUserId(userId), executeAt, truncate(action, 50));
        return id;//返回ID给用户
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
    }//返回所有活跃任务按时间排序

    public void updateContextToken(String userId, String contextToken) {
        if (userId != null && contextToken != null) {
            latestContextTokens.put(userId, contextToken);
        }
    }//更新用户的contextToken

    // ==================== 内部调度 ====================

    private void scheduleInternal(ScheduledTask task) {
        LocalDateTime executeAt = LocalDateTime.parse(task.getExecuteAt());//从对象中解析执行时间
        long delayMs = ChronoUnit.MILLIS.between(LocalDateTime.now(), executeAt);//计算当前时间与执行时间毫秒差
        if (delayMs <= 0) {
            log.warn("[定时任务] 任务已过期，跳过 id={} executeAt={}", task.getId(), executeAt);
            task.setStatus("expired");
            return;
        }//如果延迟小于0，说明任务已过期直接返回
        ScheduledFuture<?> future = scheduler.schedule(
                () -> executeTask(task.getId()), delayMs, TimeUnit.MILLISECONDS);//在delayMs毫秒后执行executeTask
        futures.put(task.getId(), future);//保存Future引用,用于后续可能取消的操作
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
        cleanupFinishedTasks();
        persistToDisk();
    }

    // ==================== 持久化 ====================

    private void persistToDisk() {
        try {
            List<ScheduledTask> taskList = new ArrayList<>(tasks.values());//173行，快照
            String json = GSON.toJson(taskList);//174行，序列化
            Path path = Path.of(filePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());//确保目录存在
            }
            Path temp = Files.createTempFile(
                    path.getParent(), path.getFileName().toString(), ".tmp");//临时创建文件
            try {
                Files.writeString(temp, json, StandardCharsets.UTF_8);       // 写入临时文件
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);                         // 原子替换目标文件
            } finally {
                Files.deleteIfExists(temp);                                  // 清理临时文件
            }
        } catch (Exception e) {
            log.error("[定时任务] 持久化失败: {}", e.getMessage());
        }
    }

    private void loadFromDisk() {
        Path path = Path.of(filePath);
        if (!Files.isRegularFile(path)) {    // 第 195 行
            return;                           // 文件不存在 → 首次启动，直接返回
        }
        try {
            String json = Files.readString(path);
            if (json.isBlank()) {
                return;                           // 空文件 → 跳过
            }
            // 第 203–204 行
            List<ScheduledTask> loaded = GSON.fromJson(json,
                    new TypeToken<List<ScheduledTask>>() {}.getType());
            // 第 205–210 行
            if (loaded != null) {
                for (ScheduledTask t : loaded) {
                    if (t.getId() != null) {          // 防御性检查：跳过无效数据
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
    }//截断长字符串，日志中避免输出过长内容
    private void cleanupFinishedTasks() {
        int before = tasks.size();
        tasks.entrySet().removeIf(entry -> !entry.getValue().isPending());
        int removed = before - tasks.size();
        if (removed > 0) {
            log.info("[定时任务] 已清理 {} 个终态任务，剩余 {} 个", removed, tasks.size());
        }
    }


}
