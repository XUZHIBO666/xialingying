package com.demo.demo.Service.scheduling.tool;

/**
 * Server-trusted context holder for scheduling tools.
 *
 * <p>Populated by {@link TrustedToolContextInterceptor} from
 * {@code RunnableConfig.metadata} before each tool call, and cleared
 * after.  Tools read the target ID from here rather than accepting
 * it as a model-provided parameter.
 *
 * <p>Uses a thread-local because the Agent executes tools synchronously
 * within the same thread that called {@code ReactAgent.call()}.  This
 * is NOT a general-purpose ThreadLocal — it is tightly scoped to the
 * tool execution lifecycle.
 */
public final class TrustedToolContext {

    private static final ThreadLocal<String> TARGET_ID = new ThreadLocal<>();

    private TrustedToolContext() {
    }

    public static void setTargetId(String targetId) {
        TARGET_ID.set(targetId);
    }

    public static String getTargetId() {
        return TARGET_ID.get();
    }

    public static void clear() {
        TARGET_ID.remove();
    }
}
