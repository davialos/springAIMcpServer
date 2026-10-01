package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.ExceptionReport;
import jdk.jfr.consumer.RecordedEvent;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Exceptions: {@code jdk.JavaExceptionThrow}, {@code jdk.JavaErrorThrow}, {@code jdk.ExceptionStatistics}.
 * Throw events are recorded inside the throwable's constructor, so leading constructor frames of throwable classes
 * are skipped: the hot spot is the method that created the exception, not its constructor.
 */
public final class ExceptionCollector implements EventCollector {

    private final StackResolver stacks;
    private final HotspotAggregator throwSites;
    private long minThrowables = Long.MAX_VALUE;
    private long maxThrowables = Long.MIN_VALUE;

    /**
     * @param stacks stack resolver
     * @param limits output limits
     */
    public ExceptionCollector(StackResolver stacks, Limits limits) {
        this.stacks = stacks;
        this.throwSites = new HotspotAggregator("Exception throw sites", "events", "Thrown class", limits);
    }

    @Override
    public void accept(String type, RecordedEvent e) {
        switch (type) {
            case "jdk.JavaExceptionThrow", "jdk.JavaErrorThrow" -> {
                String thrown = Fields.className(e, "thrownClass");
                throwSites.add(skipConstructors(stacks.resolve(e.getStackTrace())), 1, Fields.eventThreadName(e),
                        thrown == null ? "?" : thrown);
            }
            case "jdk.ExceptionStatistics" -> {
                Long n = Fields.longValue(e, "throwables");
                if (n != null) {
                    minThrowables = Math.min(minThrowables, n);
                    maxThrowables = Math.max(maxThrowables, n);
                }
            }
            default -> {
            }
        }
    }

    /**
     * @param span recording span
     * @return the exceptions section
     */
    public ExceptionReport build(Span span) {
        Long created = maxThrowables >= minThrowables ? maxThrowables - minThrowables : null;
        return new ExceptionReport(created, created == null ? null : created / span.seconds(), throwSites.build());
    }

    static @Nullable Attribution skipConstructors(@Nullable Attribution a) {
        if (a == null || a.appIndex() < 0) {
            return a;
        }
        List<ResolvedFrame> frames = a.frames();
        int index = a.appIndex();
        while (index < frames.size() && isThrowableConstructor(frames.get(index).method())) {
            index++;
        }
        while (index < frames.size() && !frames.get(index).method().inPackage()) {
            index++;
        }
        if (index == a.appIndex()) {
            return a;
        }
        return new Attribution(frames, index < frames.size() ? index : a.appIndex(), a.truncated(),
                a.inPackageMethods());
    }

    private static boolean isThrowableConstructor(MethodInfo m) {
        if (!"<init>".equals(m.methodName())) {
            return false;
        }
        String simple = m.className().substring(m.className().lastIndexOf('.') + 1);
        return simple.endsWith("Exception") || simple.endsWith("Error") || simple.endsWith("Throwable");
    }
}
