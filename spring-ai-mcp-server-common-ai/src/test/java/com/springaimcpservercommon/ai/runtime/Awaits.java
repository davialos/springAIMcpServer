package com.springaimcpservercommon.ai.runtime;

import java.util.function.BooleanSupplier;

/**
 * Waits for work a stream finishes in {@code doFinally} (turn and transcript recording), which may still be running
 * on the emitting thread when {@code block()} has already returned.
 */
final class Awaits {

    private Awaits() {
    }

    static void until(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5 s");
            }
            Thread.onSpinWait();
        }
    }
}
