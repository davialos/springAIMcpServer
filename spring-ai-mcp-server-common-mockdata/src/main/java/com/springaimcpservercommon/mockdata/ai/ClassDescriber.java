package com.springaimcpservercommon.mockdata.ai;

import com.springaimcpservercommon.annotations.CustomFakeData;
import com.springaimcpservercommon.annotations.FakeAi;
import com.springaimcpservercommon.annotations.FakeData;
import com.springaimcpservercommon.annotations.FakeIgnore;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Describes a class to an AI model: field names, types and the hints from {@code @FakeAi} / {@code @FakeData}. */
public final class ClassDescriber {

    private static final int MAX_DEPTH = 3;

    private ClassDescriber() {
    }

    /**
     * Describes the fields of a type, one per line, nested objects indented.
     *
     * @param type the class or record
     * @return the description
     */
    public static String describe(Class<?> type) {
        StringBuilder sb = new StringBuilder();
        describe(type, 0, sb);
        return sb.toString();
    }

    private static void describe(Class<?> type, int depth, StringBuilder sb) {
        if (depth >= MAX_DEPTH) {
            return;
        }
        for (Member m : members(type)) {
            if (m.ignored() || m.custom()) {
                continue;
            }
            sb.append("  ".repeat(depth)).append("- ").append(m.name()).append(": ").append(typeName(m.type()));
            if (!m.hint().isEmpty()) {
                sb.append(" — ").append(m.hint());
            }
            sb.append('\n');
            Class<?> nested = nested(m.type());
            if (nested != null && nested != type) {
                describe(nested, depth + 1, sb);
            }
        }
    }

    private record Member(String name, Type type, boolean ignored, boolean custom, String hint) {
    }

    private static List<Member> members(Class<?> type) {
        List<Member> out = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent c : type.getRecordComponents()) {
                Field f = field(type, c.getName());
                out.add(new Member(c.getName(), c.getGenericType(),
                        c.isAnnotationPresent(FakeIgnore.class) || (f != null && f.isAnnotationPresent(FakeIgnore.class)),
                        c.isAnnotationPresent(CustomFakeData.class) || (f != null && f.isAnnotationPresent(CustomFakeData.class)),
                        hint(c.getAnnotation(FakeAi.class) != null ? c.getAnnotation(FakeAi.class) : f == null ? null : f.getAnnotation(FakeAi.class),
                                c.getAnnotation(FakeData.class) != null ? c.getAnnotation(FakeData.class) : f == null ? null : f.getAnnotation(FakeData.class))));
            }
            return out;
        }
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                    continue;
                }
                out.add(new Member(f.getName(), f.getGenericType(), f.isAnnotationPresent(FakeIgnore.class),
                        f.isAnnotationPresent(CustomFakeData.class), hint(f.getAnnotation(FakeAi.class), f.getAnnotation(FakeData.class))));
            }
        }
        return out;
    }

    private static Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static String hint(FakeAi ai, FakeData data) {
        if (ai != null && !ai.value().isBlank()) {
            return ai.value();
        }
        return data != null ? "looks like Datafaker " + data.value() : "";
    }

    private static Class<?> nested(Type t) {
        Class<?> raw = t instanceof Class<?> c ? c : t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> r ? r : null;
        if (raw != null && (Collection.class.isAssignableFrom(raw)) && t instanceof ParameterizedType p && p.getActualTypeArguments()[0] instanceof Class<?> e) {
            raw = e;
        }
        if (raw == null || raw.isPrimitive() || raw.isEnum() || raw.getPackageName().startsWith("java.") || Map.class.isAssignableFrom(raw)) {
            return null;
        }
        return raw;
    }

    private static String typeName(Type t) {
        if (t instanceof Class<?> c) {
            return c.isEnum() ? "one of " + java.util.Arrays.toString(c.getEnumConstants()) : c.getSimpleName();
        }
        return t.getTypeName().replaceAll("[a-zA-Z0-9_.]*\\.", "");
    }
}
