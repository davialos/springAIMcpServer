package com.springaimcpservercommon.jfranalyzer.model;

/**
 * Blocking file and socket I/O above JFR's threshold (20 ms in the {@code default} and {@code profile} settings).
 * Weights are nanoseconds; detail = path or host:port.
 *
 * @param fileRead           {@code jdk.FileRead}
 * @param fileWrite          {@code jdk.FileWrite}
 * @param socketRead         {@code jdk.SocketRead}
 * @param socketWrite        {@code jdk.SocketWrite}
 * @param fileBytesRead      bytes read from files by those events
 * @param fileBytesWritten   bytes written to files
 * @param socketBytesRead    bytes read from sockets
 * @param socketBytesWritten bytes written to sockets
 */
public record IoReport(HotspotReport fileRead, HotspotReport fileWrite, HotspotReport socketRead,
                       HotspotReport socketWrite, long fileBytesRead, long fileBytesWritten, long socketBytesRead,
                       long socketBytesWritten) {
}
