package com.springaimcpservercommon.jfranalyzer.collect;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedThread;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Null-safe field access: fields differ between JDK versions, so every read checks that the field exists.
 */
public final class Fields {

    private Fields() {
    }

    /**
     * @param o     record
     * @param field field name
     * @return the value widened to {@code long}, or {@code null} if absent
     */
    public static @Nullable Long longValue(@Nullable RecordedObject o, String field) {
        if (o == null || !o.hasField(field)) {
            return null;
        }
        Object v = o.getValue(field);
        return v instanceof Number n ? n.longValue() : null;
    }

    /**
     * @param o     record
     * @param field field name
     * @return the value widened to {@code double}, or {@code null} if absent
     */
    public static @Nullable Double doubleValue(@Nullable RecordedObject o, String field) {
        if (o == null || !o.hasField(field)) {
            return null;
        }
        Object v = o.getValue(field);
        return v instanceof Number n ? n.doubleValue() : null;
    }

    /**
     * @param o     record
     * @param field field name
     * @return the string, or {@code null}
     */
    public static @Nullable String string(@Nullable RecordedObject o, String field) {
        if (o == null || !o.hasField(field)) {
            return null;
        }
        Object v = o.getValue(field);
        return v == null ? null : v.toString();
    }

    /**
     * @param o     record
     * @param field a {@code Timespan} field
     * @return nanoseconds, or 0 if absent
     */
    public static long nanos(@Nullable RecordedObject o, String field) {
        if (o == null || !o.hasField(field)) {
            return 0;
        }
        try {
            Duration d = o.getDuration(field);
            return d == null ? 0 : saturatedNanos(d);
        } catch (IllegalArgumentException notATimespan) {
            return 0;
        }
    }

    /**
     * @param o     record
     * @param field a {@code Class} field
     * @return the class name, or {@code null}
     */
    public static @Nullable String className(@Nullable RecordedObject o, String field) {
        if (o == null || !o.hasField(field)) {
            return null;
        }
        Object v = o.getValue(field);
        return v instanceof RecordedClass c ? typeName(c.getName()) : null;
    }

    /**
     * JFR reports array classes by descriptor; turns them into source form.
     *
     * @param name a class name as JFR reports it, e.g. {@code [B} or {@code [Ljava.lang.String;}
     * @return e.g. {@code byte[]}, {@code java.lang.String[]}; other names unchanged
     */
    public static String typeName(String name) {
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') {
            dims++;
        }
        if (dims == 0 || dims == name.length()) {
            return name;
        }
        String element = name.substring(dims);
        String base = switch (element) {
            case "Z" -> "boolean";
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            case "I" -> "int";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            default -> element.startsWith("L") && element.endsWith(";")
                    ? element.substring(1, element.length() - 1).replace('/', '.') : element;
        };
        return base + "[]".repeat(dims);
    }

    /**
     * @param o     record
     * @param field a nested-object field
     * @return the nested object, or {@code null}
     */
    public static @Nullable RecordedObject object(@Nullable RecordedObject o, String field) {
        if (o == null || !o.hasField(field)) {
            return null;
        }
        Object v = o.getValue(field);
        return v instanceof RecordedObject r ? r : null;
    }

    /**
     * @param o     record
     * @param field a {@code Thread} field
     * @return the thread's display name, or {@code null}
     */
    public static @Nullable String threadName(@Nullable RecordedObject o, String field) {
        if (o == null || !o.hasField(field)) {
            return null;
        }
        Object v = o.getValue(field);
        return v instanceof RecordedThread t ? threadName(t) : null;
    }

    /**
     * @param e event
     * @return the thread the event happened on: {@code sampledThread} for samples, else {@code eventThread}
     */
    public static @Nullable String eventThreadName(RecordedEvent e) {
        String sampled = threadName(e, "sampledThread");
        return sampled != null ? sampled : threadName(e, "eventThread");
    }

    /**
     * @param t thread
     * @return Java name, else OS name, with a marker for virtual threads
     */
    public static String threadName(RecordedThread t) {
        String name = t.getJavaName();
        if (name == null || name.isEmpty()) {
            name = t.getOSName();
        }
        if (name == null || name.isEmpty()) {
            name = "thread-" + t.getJavaThreadId();
        }
        return t.isVirtual() ? name + " (virtual)" : name;
    }

    /**
     * @param e event
     * @return its duration in nanoseconds
     */
    public static long durationNanos(RecordedEvent e) {
        return saturatedNanos(e.getDuration());
    }

    private static long saturatedNanos(Duration d) {
        try {
            return d.toNanos();
        } catch (ArithmeticException overflow) {
            return d.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }
}
