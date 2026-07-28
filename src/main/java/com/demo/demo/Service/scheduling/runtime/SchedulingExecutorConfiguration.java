package com.demo.demo.Service.scheduling.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.demo.demo.Service.scheduling.execution.ScheduledTaskHandler;
import com.demo.demo.Service.scheduling.execution.ScheduledTaskHandlerRegistry;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Enables Spring scheduling and provides a bounded worker thread pool
 * for scheduled task execution.
 */
@Slf4j
@Configuration
@EnableScheduling
public class SchedulingExecutorConfiguration {

    private static final int WORKER_THREADS = Integer.parseInt(
            System.getenv().getOrDefault("SCHEDULING_WORKER_THREADS", "2"));
    private static final int WORKER_QUEUE_CAPACITY = Integer.parseInt(
            System.getenv().getOrDefault("SCHEDULING_WORKER_QUEUE", "50"));

    @Bean
    public ScheduledTaskHandlerRegistry scheduledTaskHandlerRegistry(
            List<ScheduledTaskHandler> handlers) {
        return new ScheduledTaskHandlerRegistry(handlers);
    }

    @Bean(name = "schedulingWorkerExecutor")
    public ExecutorService schedulingWorkerExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                WORKER_THREADS, WORKER_THREADS,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(WORKER_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "scheduling-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy() // throw if queue full
        );
        log.info("[Scheduling] Worker pool: {} threads, queue capacity {}",
                WORKER_THREADS, WORKER_QUEUE_CAPACITY);
        return executor;
    }
}
