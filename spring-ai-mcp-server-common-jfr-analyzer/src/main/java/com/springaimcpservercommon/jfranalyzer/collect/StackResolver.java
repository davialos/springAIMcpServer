package com.springaimcpservercommon.jfranalyzer.collect;

import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns JFR stack traces into {@link Attribution}s. The JFR parser hands out the same constant-pool objects for
 * every event of a chunk that shares a stack or method, so both are cached by identity; the identity caches are
 * dropped when they grow large (a new chunk brings new instances anyway). Methods are additionally de-duplicated by
 * key so that equal methods from different chunks aggregate together.
 */
public final class StackResolver {

    private static final int MAX_IDENTITY_ENTRIES = 200_000;

    private final PackageMatcher matcher;
    private final Map<RecordedMethod, MethodInfo> methodsByIdentity = new IdentityHashMap<>();
    private final Map<String, MethodInfo> methodsByKey = new HashMap<>();
    private final Map<RecordedStackTrace, Attribution> stacksByIdentity = new IdentityHashMap<>();

    /** @param matcher decides which frames are in the requested packages */
    public StackResolver(PackageMatcher matcher) {
        this.matcher = matcher;
    }

    /**
     * @param stackTrace an event's stack trace, may be {@code null}
     * @return the attribution, or {@code null} if the event has no stack trace
     */
    public @Nullable Attribution resolve(@Nullable RecordedStackTrace stackTrace) {
        if (stackTrace == null) {
            return null;
        }
        Attribution cached = stacksByIdentity.get(stackTrace);
        if (cached != null) {
            return cached;
        }
        List<RecordedFrame> recorded = stackTrace.getFrames();
        List<ResolvedFrame> frames = new ArrayList<>(recorded.size());
        int appIndex = -1;
        Set<MethodInfo> inPackage = new LinkedHashSet<>();
        for (RecordedFrame frame : recorded) {
            RecordedMethod method = frame.getMethod();
            if (method == null) {
                continue;
            }
            MethodInfo info = method(method);
            if (info.inPackage()) {
                if (appIndex < 0) {
                    appIndex = frames.size();
                }
                inPackage.add(info);
            }
            String type = frame.getType();
            frames.add(new ResolvedFrame(info, frame.getLineNumber(), type == null ? "" : type));
        }
        Attribution attribution = new Attribution(List.copyOf(frames), appIndex, stackTrace.isTruncated(),
                List.copyOf(inPackage));
        if (stacksByIdentity.size() >= MAX_IDENTITY_ENTRIES) {
            stacksByIdentity.clear();
        }
        stacksByIdentity.put(stackTrace, attribution);
        return attribution;
    }

    private MethodInfo method(RecordedMethod method) {
        MethodInfo cached = methodsByIdentity.get(method);
        if (cached != null) {
            return cached;
        }
        String className = method.getType() == null ? "?" : method.getType().getName();
        String name = method.getName();
        String descriptor = method.getDescriptor() == null ? "" : method.getDescriptor();
        String key = className + "#" + name + descriptor;
        MethodInfo info = methodsByKey.computeIfAbsent(key, k -> new MethodInfo(k, className, name,
                className + "." + name + "(" + parameters(descriptor) + ")", fileName(className),
                matcher.matches(className)));
        if (methodsByIdentity.size() >= MAX_IDENTITY_ENTRIES) {
            methodsByIdentity.clear();
        }
        methodsByIdentity.put(method, info);
        return info;
    }

    /**
     * {@code com.acme.Outer$Inner$1} → {@code Outer.java}. Hidden classes ({@code Outer$$Lambda.0x...} or
     * {@code Outer$$Lambda/0x...}) are cut at {@code $$} first, since their suffix may itself contain a dot.
     */
    static String fileName(String className) {
        int hidden = className.indexOf("$$");
        String name = hidden > 0 ? className.substring(0, hidden) : className;
        String simple = name.substring(name.lastIndexOf('.') + 1);
        int dollar = simple.indexOf('$');
        if (dollar > 0) {
            simple = simple.substring(0, dollar);
        }
        int slash = simple.indexOf('/');
        if (slash > 0) {
            simple = simple.substring(0, slash);
        }
        return simple + ".java";
    }

    /** {@code (I[Ljava/lang/String;J)V} → {@code int, String[], long}. */
    static String parameters(String descriptor) {
        int open = descriptor.indexOf('(');
        int close = descriptor.indexOf(')');
        if (open < 0 || close < open) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int i = open + 1;
        while (i < close) {
            int dims = 0;
            while (descriptor.charAt(i) == '[') {
                dims++;
                i++;
            }
            String type;
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                String binary = descriptor.substring(i + 1, end);
                type = binary.substring(binary.lastIndexOf('/') + 1);
                i = end + 1;
            } else {
                type = switch (c) {
                    case 'Z' -> "boolean";
                    case 'B' -> "byte";
                    case 'C' -> "char";
                    case 'S' -> "short";
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'F' -> "float";
                    case 'D' -> "double";
                    default -> String.valueOf(c);
                };
                i++;
            }
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(type).append("[]".repeat(dims));
        }
        return out.toString();
    }
}
