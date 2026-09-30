package com.springaimcpservercommon.ai.guard;

/**
 * Marks the current (virtual) thread as executing inside an AI read scope (ADR-0014).
 *
 * <p>Uses Java 25 {@link ScopedValue} so the flag is automatically cleared when the scoped
 * block exits, even on exception, and is never visible to other threads.
 *
 * <p>Usage (in the tool bridge or advisor that enters read scope):
 * <pre>{@code
 * AiReadScope.runScoped(() -> { ... hibernate session here ... });
 * }</pre>
 */
public final class AiReadScope {

    private static final ScopedValue<Boolean> FLAG = ScopedValue.newInstance();

    private AiReadScope() {}

    /**
     * @return {@code true} if the current thread is executing inside an AI read scope
     */
    public static boolean isActive() {
        return FLAG.orElse(Boolean.FALSE);
    }

    /**
     * Runs {@code task} with the AI read scope active on the current thread.
     *
     * @param task the task to run
     * @throws Exception any exception thrown by {@code task}
     */
    public static void runScoped(CheckedRunnable task) throws Exception {
        ScopedValue.where(FLAG, Boolean.TRUE).call(() -> {
            task.run();
            return null;
        });
    }

    /**
     * Runs {@code task} with the AI read scope active and returns its result.
     *
     * @param task the task to call
     * @param <T>  the result type
     * @return the result
     * @throws Exception any exception thrown by {@code task}
     */
    public static <T> T callScoped(CheckedCallable<T> task) throws Exception {
        return ScopedValue.where(FLAG, Boolean.TRUE).call(task::call);
    }

    /** A {@link Runnable} that may throw a checked exception. */
    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    /** A {@link java.util.concurrent.Callable} lookalike scoped to this API. */
    @FunctionalInterface
    public interface CheckedCallable<T> {
        T call() throws Exception;
    }
}
