package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.IoReport;
import jdk.jfr.consumer.RecordedEvent;
import org.jspecify.annotations.Nullable;

/** Blocking I/O: {@code jdk.FileRead}, {@code jdk.FileWrite}, {@code jdk.SocketRead}, {@code jdk.SocketWrite}. */
public final class IoCollector implements EventCollector {

    private final StackResolver stacks;
    private final HotspotAggregator fileRead;
    private final HotspotAggregator fileWrite;
    private final HotspotAggregator socketRead;
    private final HotspotAggregator socketWrite;
    private long fileBytesRead;
    private long fileBytesWritten;
    private long socketBytesRead;
    private long socketBytesWritten;

    /**
     * @param stacks stack resolver
     * @param limits output limits
     */
    public IoCollector(StackResolver stacks, Limits limits) {
        this.stacks = stacks;
        this.fileRead = new HotspotAggregator("File reads", "nanos", "Path", limits);
        this.fileWrite = new HotspotAggregator("File writes", "nanos", "Path", limits);
        this.socketRead = new HotspotAggregator("Socket reads", "nanos", "Remote endpoint", limits);
        this.socketWrite = new HotspotAggregator("Socket writes", "nanos", "Remote endpoint", limits);
    }

    @Override
    public void accept(String type, RecordedEvent e) {
        switch (type) {
            case "jdk.FileRead" -> {
                fileBytesRead += positive(Fields.longValue(e, "bytesRead"));
                fileRead.add(stacks.resolve(e.getStackTrace()), Fields.durationNanos(e), Fields.eventThreadName(e),
                        path(e));
            }
            case "jdk.FileWrite" -> {
                fileBytesWritten += positive(Fields.longValue(e, "bytesWritten"));
                fileWrite.add(stacks.resolve(e.getStackTrace()), Fields.durationNanos(e), Fields.eventThreadName(e),
                        path(e));
            }
            case "jdk.SocketRead" -> {
                socketBytesRead += positive(Fields.longValue(e, "bytesRead"));
                socketRead.add(stacks.resolve(e.getStackTrace()), Fields.durationNanos(e),
                        Fields.eventThreadName(e), endpoint(e));
            }
            case "jdk.SocketWrite" -> {
                socketBytesWritten += positive(Fields.longValue(e, "bytesWritten"));
                socketWrite.add(stacks.resolve(e.getStackTrace()), Fields.durationNanos(e),
                        Fields.eventThreadName(e), endpoint(e));
            }
            default -> {
            }
        }
    }

    /** @return the I/O section */
    public IoReport build() {
        return new IoReport(fileRead.build(), fileWrite.build(), socketRead.build(), socketWrite.build(),
                fileBytesRead, fileBytesWritten, socketBytesRead, socketBytesWritten);
    }

    private static String path(RecordedEvent e) {
        String path = Fields.string(e, "path");
        return path == null || path.isBlank() ? "(unknown path)" : path;
    }

    private static String endpoint(RecordedEvent e) {
        String host = Fields.string(e, "host");
        if (host == null || host.isBlank()) {
            host = Fields.string(e, "address");
        }
        Long port = Fields.longValue(e, "port");
        return (host == null || host.isBlank() ? "?" : host) + (port == null ? "" : ":" + port);
    }

    private static long positive(@Nullable Long v) {
        return v == null || v < 0 ? 0 : v;
    }
}
