package com.springaimcpservercommon.jfranalyzer.collect;

import com.springaimcpservercommon.jfranalyzer.model.JvmInfo;
import jdk.jfr.consumer.RecordedEvent;
import org.jspecify.annotations.Nullable;

/** The recorded JVM and machine; command lines are passed through {@link Redactor}. */
public final class JvmInfoCollector implements EventCollector {

    private @Nullable String jvmName;
    private @Nullable String jvmVersion;
    private @Nullable String jvmArguments;
    private @Nullable String javaArguments;
    private @Nullable Long pid;
    private @Nullable String os;
    private @Nullable String cpu;
    private @Nullable Integer hwThreads;
    private @Nullable Long physicalMemory;

    @Override
    public void accept(String type, RecordedEvent e) {
        switch (type) {
            case "jdk.JVMInformation" -> {
                jvmName = Fields.string(e, "jvmName");
                jvmVersion = Fields.string(e, "jvmVersion");
                jvmArguments = Redactor.commandLine(Fields.string(e, "jvmArguments"));
                javaArguments = Redactor.commandLine(Fields.string(e, "javaArguments"));
                pid = Fields.longValue(e, "pid");
            }
            case "jdk.OSInformation" -> os = Fields.string(e, "osVersion");
            case "jdk.CPUInformation" -> {
                cpu = Fields.string(e, "cpu");
                Long threads = Fields.longValue(e, "hwThreads");
                hwThreads = threads == null ? null : threads.intValue();
            }
            case "jdk.PhysicalMemory" -> physicalMemory = Fields.longValue(e, "totalSize");
            default -> {
            }
        }
    }

    /** @return the JVM info */
    public JvmInfo build() {
        return new JvmInfo(jvmName, jvmVersion, jvmArguments, javaArguments, pid, firstLine(os), firstLine(cpu),
                hwThreads, physicalMemory);
    }

    private static @Nullable String firstLine(@Nullable String s) {
        if (s == null) {
            return null;
        }
        String line = s.strip().lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) : line;
    }
}
