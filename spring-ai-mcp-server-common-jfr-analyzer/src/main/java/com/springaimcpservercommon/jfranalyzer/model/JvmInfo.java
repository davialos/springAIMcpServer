package com.springaimcpservercommon.jfranalyzer.model;

import org.jspecify.annotations.Nullable;

/**
 * The recorded JVM and machine, from {@code jdk.JVMInformation}, {@code jdk.OSInformation},
 * {@code jdk.CPUInformation} and {@code jdk.PhysicalMemory}; any field may be absent from a recording.
 *
 * @param jvmName              JVM name
 * @param jvmVersion           JVM version string
 * @param jvmArguments         JVM arguments
 * @param javaArguments        main class / jar and its arguments
 * @param pid                  process id
 * @param os                   operating system description
 * @param cpu                  CPU description
 * @param hardwareThreads      hardware threads
 * @param physicalMemoryBytes  physical memory
 */
public record JvmInfo(@Nullable String jvmName, @Nullable String jvmVersion, @Nullable String jvmArguments,
                      @Nullable String javaArguments, @Nullable Long pid, @Nullable String os, @Nullable String cpu,
                      @Nullable Integer hardwareThreads, @Nullable Long physicalMemoryBytes) {
}
