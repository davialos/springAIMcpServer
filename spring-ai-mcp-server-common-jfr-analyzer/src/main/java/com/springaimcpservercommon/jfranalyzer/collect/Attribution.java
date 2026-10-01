package com.springaimcpservercommon.jfranalyzer.collect;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A recorded stack and the frame in it that the cost is attributed to.
 *
 * @param frames    all recorded frames, leaf first
 * @param appIndex  index of the first frame (from the leaf) in a requested package, {@code -1} if none
 * @param truncated whether JFR truncated the stack (raise {@code -XX:FlightRecorderOptions:stackdepth})
 * @param inPackageMethods distinct methods in the requested packages anywhere on the stack
 */
public record Attribution(List<ResolvedFrame> frames, int appIndex, boolean truncated,
                          List<MethodInfo> inPackageMethods) {

    /** @return the leaf frame, or {@code null} for an empty stack */
    public @Nullable ResolvedFrame leaf() {
        return frames.isEmpty() ? null : frames.getFirst();
    }

    /** @return the frame in a requested package closest to the leaf, or {@code null} */
    public @Nullable ResolvedFrame app() {
        return appIndex < 0 ? null : frames.get(appIndex);
    }
}
