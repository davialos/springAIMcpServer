package com.springaimcpservercommon.core.schema;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Recognises Spring Data paging types <em>by class name</em>, so the core needs no Spring Data dependency
 * (LLD-14 §3.3): {@code Pageable}, {@code Limit} parameters and {@code Page}/{@code Slice}/{@code Window} returns.
 */
public final class SpringDataTypes {

    /** {@code org.springframework.data.domain.Pageable}. */
    public static final String PAGEABLE = "org.springframework.data.domain.Pageable";
    /** {@code org.springframework.data.domain.Limit}. */
    public static final String LIMIT = "org.springframework.data.domain.Limit";
    /** {@code org.springframework.data.domain.Sort}. */
    public static final String SORT = "org.springframework.data.domain.Sort";
    /** {@code org.springframework.data.domain.Slice} (supertype of {@code Page}). */
    public static final String SLICE = "org.springframework.data.domain.Slice";
    /** {@code org.springframework.data.domain.Page}. */
    public static final String PAGE = "org.springframework.data.domain.Page";
    /** {@code org.springframework.data.domain.Window} (keyset scrolling). */
    public static final String WINDOW = "org.springframework.data.domain.Window";

    private SpringDataTypes() {
    }

    /**
     * Whether {@code type} is, extends or implements the named type.
     *
     * @param type     type to test
     * @param typeName fully qualified name
     * @return {@code true} if the name appears in the type hierarchy
     */
    public static boolean isA(Class<?> type, String typeName) {
        Deque<Class<?>> todo = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        todo.add(type);
        while (!todo.isEmpty()) {
            Class<?> c = todo.poll();
            if (!seen.add(c)) {
                continue;
            }
            if (c.getName().equals(typeName)) {
                return true;
            }
            if (c.getSuperclass() != null) {
                todo.add(c.getSuperclass());
            }
            todo.addAll(java.util.Arrays.asList(c.getInterfaces()));
        }
        return false;
    }

    /**
     * Whether the type is a paged result ({@code Page}, {@code Slice} or {@code Window}).
     *
     * @param type return type
     * @return {@code true} for paged results
     */
    public static boolean isPagedResult(Class<?> type) {
        return isA(type, SLICE) || isA(type, WINDOW);
    }
}
