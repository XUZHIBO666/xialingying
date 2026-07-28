package com.demo.demo.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

@Slf4j
@Service
public class PeriodicReplyService {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final int MAX_TASKS_PER_USER = 20;
    private static final int FILE_VERSION = 1;

    public record PeriodicTask(
            int id,
            String userId,
            String contextToken,
            String scheduleType,
            String scheduleValue,
            String mode,
            String content,
            Instant nextRunAt,
            boolean enabled) {
    }

    private record TaskSnapshot(int version, List<PeriodicTask> tasks) {
    }

    @FunctionalInterface
    public interface TaskSender {
        void send(String userId, String contextToken, String text);
    }

    private final Path taskFile;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Object lock = new Object();
    private final List<PeriodicTask> tasks = new ArrayList<>();
    private final ScheduledExecutorService scheduler;

    private volatile BooleanSupplier botLoggedIn = () -> false;
    private volatile Function<PeriodicTask, String> dynamicGenerator = task -> null;
    private volatile TaskSender sender = (userId, contextToken, text) -> {
    };

    @Autowired
    public PeriodicReplyService(
            @Value("${ai.periodic-reply.file:./data/periodic-replies.json}") String file,
            ObjectMapper objectMapper) {
        this(Path.of(file), objectMapper, Clock.systemUTC());
    }

    PeriodicReplyService(Path file, ObjectMapper objectMapper, Clock clock) {
        this.taskFile = file.toAbsolutePath();
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "periodic-reply-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        load();
    }

    Instant nextRun(String scheduleType, String scheduleValue, Instant after) {
        String type = normalize(scheduleType);
        return switch (type) {
            case "INTERVAL" -> nextInterval(scheduleValue, after);
            case "DAILY" -> nextDaily(scheduleValue, after);
            case "WEEKLY" -> nextWeekly(scheduleValue, after);
            default -> throw new IllegalArgumentException("不支持的周期类型: " + scheduleType);
        };
    }

    PeriodicTask create(
            String userId,
            String contextToken,
            String scheduleType,
            String scheduleValue,
            String mode,
            String content) {
        requireText(userId, "userId");
        requireText(contextToken, "contextToken");
        requireText(content, "content");
        String normalizedType = normalize(scheduleType);
        String normalizedMode = normalize(mode);
        if (!List.of("FIXED", "AGENT").contains(normalizedMode)) {
            throw new IllegalArgumentException("不支持的任务模式: " + mode);
        }
        Instant runAt = nextRun(normalizedType, scheduleValue, clock.instant());

        synchronized (lock) {
            List<PeriodicTask> userTasks = tasks.stream()
                    .filter(task -> task.enabled() && task.userId().equals(userId))
                    .toList();
            if (userTasks.size() >= MAX_TASKS_PER_USER) {
                throw new IllegalStateException("每个用户最多创建 20 个周期任务");
            }
            int id = userTasks.stream().mapToInt(PeriodicTask::id).max().orElse(0) + 1;
            PeriodicTask task = new PeriodicTask(
                    id, userId, contextToken, normalizedType, scheduleValue,
                    normalizedMode, content, runAt, true);
            List<PeriodicTask> replacement = new ArrayList<>(tasks);
            replacement.add(task);
            persist(replacement);
            replaceTasks(replacement);
            return task;
        }
    }

    List<PeriodicTask> list(String userId) {
        synchronized (lock) {
            return tasks.stream()
                    .filter(task -> task.enabled() && task.userId().equals(userId))
                    .sorted((left, right) -> Integer.compare(left.id(), right.id()))
                    .toList();
        }
    }

    String cancel(String userId, Integer taskId) {
        synchronized (lock) {
            List<PeriodicTask> userTasks = tasks.stream()
                    .filter(task -> task.enabled() && task.userId().equals(userId))
                    .toList();
            if (userTasks.isEmpty()) {
                return "当前没有有效周期任务";
            }
            if (taskId == null && userTasks.size() > 1) {
                return "存在多个周期任务，请指定任务编号";
            }
            int targetId = taskId == null ? userTasks.getFirst().id() : taskId;
            boolean exists = userTasks.stream().anyMatch(task -> task.id() == targetId);
            if (!exists) {
                return "未找到任务 " + targetId;
            }
            List<PeriodicTask> replacement = tasks.stream()
                    .filter(task -> !(task.userId().equals(userId) && task.id() == targetId))
                    .toList();
            persist(replacement);
            replaceTasks(replacement);
            return "已取消任务 " + targetId;
        }
    }

    @Tool(description = "创建周期回复任务。scheduleType 仅可为 INTERVAL、DAILY、WEEKLY；"
            + "scheduleValue 示例 PT2H、08:00、MONDAY@09:00；"
            + "mode 仅可为 FIXED 或 AGENT。")
    public String createPeriodicReply(
            String scheduleType,
            String scheduleValue,
            String mode,
            String content,
            ToolContext toolContext) {
        try {
            String userId = contextValue(toolContext, "user_id");
            String contextToken = contextValue(toolContext, "context_token");
            PeriodicTask task = create(
                    userId, contextToken, scheduleType, scheduleValue, mode, content);
            return "已创建任务 " + task.id() + "：" + describe(task);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "创建周期任务失败：" + e.getMessage();
        }
    }

    @Tool(description = "查看当前微信用户的所有有效周期回复任务。")
    public String listPeriodicReplies(ToolContext toolContext) {
        String userId;
        try {
            userId = contextValue(toolContext, "user_id");
        } catch (IllegalArgumentException e) {
            return "查看周期任务失败：" + e.getMessage();
        }
        List<PeriodicTask> userTasks = list(userId);
        if (userTasks.isEmpty()) {
            return "当前没有有效周期任务";
        }
        StringBuilder result = new StringBuilder("当前周期任务：\n");
        userTasks.forEach(task -> result.append("- 任务 ")
                .append(task.id()).append("：").append(describe(task)).append('\n'));
        return result.toString().trim();
    }

    @Tool(description = "取消当前微信用户的周期回复任务。"
            + "只有一个任务时 taskId 可省略；多个任务时必须指定。")
    public String cancelPeriodicReply(Integer taskId, ToolContext toolContext) {
        try {
            return cancel(contextValue(toolContext, "user_id"), taskId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "取消周期任务失败：" + e.getMessage();
        }
    }

    public String activeTaskSummary(String userId) {
        List<PeriodicTask> userTasks = list(userId);
        if (userTasks.isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder("【当前有效周期任务】\n");
        userTasks.forEach(task -> summary.append("- 任务")
                .append(task.id()).append("：").append(describe(task)).append('\n'));
        summary.append("这些任务是当前系统状态；不得虚构、重复创建或擅自取消。");
        return summary.toString();
    }

    public void configure(
            BooleanSupplier botLoggedIn,
            Function<PeriodicTask, String> dynamicGenerator,
            TaskSender sender) {
        this.botLoggedIn = Objects.requireNonNull(botLoggedIn);
        this.dynamicGenerator = Objects.requireNonNull(dynamicGenerator);
        this.sender = Objects.requireNonNull(sender);
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::scanSafely, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    void scanDueTasks() {
        if (!botLoggedIn.getAsBoolean()) {
            return;
        }
        Instant now = clock.instant();
        List<PeriodicTask> due;
        synchronized (lock) {
            due = tasks.stream()
                    .filter(task -> task.enabled() && !task.nextRunAt().isAfter(now))
                    .toList();
            if (due.isEmpty()) {
                return;
            }
            Map<String, PeriodicTask> advanced = due.stream().collect(
                    java.util.stream.Collectors.toMap(
                            PeriodicReplyService::taskKey,
                            task -> advancePast(task, now)));
            List<PeriodicTask> replacement = tasks.stream()
                    .map(task -> advanced.getOrDefault(taskKey(task), task))
                    .toList();
            persist(replacement);
            replaceTasks(replacement);
        }

        for (PeriodicTask task : due) {
            try {
                String text = task.content();
                if ("AGENT".equals(task.mode())) {
                    try {
                        text = dynamicGenerator.apply(task);
                    } catch (Exception e) {
                        text = null;
                    }
                    if (text == null || text.isBlank()) {
                        text = "本次周期任务生成失败，请稍后重试";
                    }
                }
                sender.send(task.userId(), task.contextToken(), text);
            } catch (Exception e) {
                log.warn("[周期任务] 执行失败 userId={} taskId={} error={}",
                        maskUserId(task.userId()), task.id(),
                        e.getClass().getSimpleName());
            }
        }
    }

    private void scanSafely() {
        try {
            scanDueTasks();
        } catch (Exception e) {
            log.warn("[周期任务] 扫描失败 error={}", e.getClass().getSimpleName());
        }
    }

    private PeriodicTask advancePast(PeriodicTask task, Instant now) {
        Instant next = task.nextRunAt();
        do {
            next = nextRun(task.scheduleType(), task.scheduleValue(), next);
        } while (!next.isAfter(now));
        return new PeriodicTask(
                task.id(), task.userId(), task.contextToken(),
                task.scheduleType(), task.scheduleValue(), task.mode(),
                task.content(), next, task.enabled());
    }

    private static String taskKey(PeriodicTask task) {
        return task.userId() + "\u0000" + task.id();
    }

    private static String contextValue(ToolContext toolContext, String key) {
        if (toolContext == null) {
            throw new IllegalArgumentException("缺少可信工具上下文");
        }
        Object value = toolContext.getContext().get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("缺少 " + key);
        }
        return text;
    }

    private static String describe(PeriodicTask task) {
        String schedule = switch (task.scheduleType()) {
            case "INTERVAL" -> "每隔 " + formatInterval(task.scheduleValue());
            case "DAILY" -> "每天 " + task.scheduleValue();
            case "WEEKLY" -> "每周 " + task.scheduleValue().replace('@', ' ');
            default -> task.scheduleValue();
        };
        String action = "AGENT".equals(task.mode())
                ? "，由 Agent 执行“" + task.content() + "”"
                : "，固定发送“" + task.content() + "”";
        return schedule + action;
    }

    private static String formatInterval(String scheduleValue) {
        long seconds = Duration.parse(scheduleValue).toSeconds();
        if (seconds % 3600 == 0) {
            return seconds / 3600 + " 小时";
        }
        if (seconds % 60 == 0) {
            return seconds / 60 + " 分钟";
        }
        return seconds + " 秒";
    }

    private Instant nextInterval(String scheduleValue, Instant after) {
        Duration duration;
        try {
            duration = Duration.parse(scheduleValue);
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的间隔规则", e);
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("间隔必须大于零");
        }
        return after.plus(duration);
    }

    private Instant nextDaily(String scheduleValue, Instant after) {
        LocalTime time;
        try {
            time = LocalTime.parse(scheduleValue);
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的每日规则", e);
        }
        ZonedDateTime afterAtZone = after.atZone(ZONE);
        ZonedDateTime candidate = ZonedDateTime.of(afterAtZone.toLocalDate(), time, ZONE);
        if (!candidate.toInstant().isAfter(after)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }

    private Instant nextWeekly(String scheduleValue, Instant after) {
        String[] parts = scheduleValue.split("@", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("无效的每周规则");
        }
        DayOfWeek day;
        LocalTime time;
        try {
            day = DayOfWeek.valueOf(parts[0].toUpperCase(Locale.ROOT));
            time = LocalTime.parse(parts[1]);
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的每周规则", e);
        }
        ZonedDateTime afterAtZone = after.atZone(ZONE);
        LocalDate date = afterAtZone.toLocalDate().with(TemporalAdjusters.nextOrSame(day));
        ZonedDateTime candidate = ZonedDateTime.of(date, time, ZONE);
        if (!candidate.toInstant().isAfter(after)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate.toInstant();
    }

    private void load() {
        if (!Files.isRegularFile(taskFile)) {
            return;
        }
        try {
            TaskSnapshot snapshot = objectMapper.readValue(taskFile.toFile(), TaskSnapshot.class);
            if (snapshot.version() != FILE_VERSION || snapshot.tasks() == null) {
                throw new IOException("不支持的任务文件版本");
            }
            synchronized (lock) {
                replaceTasks(snapshot.tasks());
            }
            log.info("[周期任务] 已加载 {} 条任务", snapshot.tasks().size());
        } catch (Exception e) {
            log.warn("[周期任务] 加载失败 path={} error={}",
                    taskFile, e.getClass().getSimpleName());
        }
    }

    private void persist(List<PeriodicTask> replacement) {
        try {
            Path parent = taskFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(
                    parent, taskFile.getFileName().toString(), ".tmp");
            try {
                objectMapper.writeValue(temp.toFile(),
                        new TaskSnapshot(FILE_VERSION, List.copyOf(replacement)));
                try {
                    Files.move(temp, taskFile, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, taskFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("周期任务文件保存失败", e);
        }
    }

    private void replaceTasks(List<PeriodicTask> replacement) {
        tasks.clear();
        tasks.addAll(replacement);
    }

    private static String normalize(String value) {
        requireText(value, "value");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private static String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) {
            return "***";
        }
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
    }
}
