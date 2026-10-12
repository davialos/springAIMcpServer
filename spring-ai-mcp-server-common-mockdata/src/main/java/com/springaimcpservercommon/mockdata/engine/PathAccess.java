package com.springaimcpservercommon.mockdata.engine;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reads a value at a dotted path ({@code address.city}) of an object graph made of records and plain classes. */
public final class PathAccess {

    private PathAccess() {
    }

    /**
     * Reads the value at a path.
     *
     * @param root the object
     * @param path dotted path
     * @return the value, or {@code null} when a step is null or does not exist
     */
    public static @Nullable Object get(@Nullable Object root, String path) {
        Object cur = root;
        for (String step : path.split("\\.")) {
            if (cur == null) {
                return null;
            }
            cur = read(cur, step);
        }
        return cur;
    }

    private static @Nullable Object read(Object target, String name) {
        try {
            if (target.getClass().isRecord()) {
                Method m = target.getClass().getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            }
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException e) {
                    // look in the superclass
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        return null;
    }
}
