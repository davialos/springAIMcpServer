package com.springaimcpservercommon.core.schema;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Minimal generic type resolution (Spring-free): binds the type variables of every supertype of a context class
 * so that, e.g., {@code CrudService<T>.find(T)} on {@code OrderService extends CrudService<Order>} maps {@code T}
 * to {@code Order}.
 */
public final class GenericTypes {

    private GenericTypes() {
    }

    /**
     * Type-variable bindings contributed by the generic supertypes of {@code contextClass}.
     *
     * @param contextClass the concrete class
     * @return bindings (possibly empty)
     */
    public static Map<TypeVariable<?>, Type> bindingsOf(Class<?> contextClass) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        collect(contextClass, bindings, new HashSet<>());
        return bindings;
    }

    private static void collect(Class<?> type, Map<TypeVariable<?>, Type> bindings, Set<Class<?>> seen) {
        if (!seen.add(type)) {
            return;
        }
        Type superclass = type.getGenericSuperclass();
        if (superclass != null) {
            bind(superclass, bindings, seen);
        }
        for (Type itf : type.getGenericInterfaces()) {
            bind(itf, bindings, seen);
        }
    }

    private static void bind(Type supertype, Map<TypeVariable<?>, Type> bindings, Set<Class<?>> seen) {
        if (supertype instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw) {
            TypeVariable<?>[] vars = raw.getTypeParameters();
            Type[] args = p.getActualTypeArguments();
            for (int i = 0; i < vars.length && i < args.length; i++) {
                bindings.putIfAbsent(vars[i], resolve(args[i], bindings));
            }
            collect(raw, bindings, seen);
        } else if (supertype instanceof Class<?> c) {
            collect(c, bindings, seen);
        }
    }

    /**
     * Substitutes bound type variables (one level, recursively through wildcards and arrays is not needed for
     * binding purposes).
     *
     * @param type     type
     * @param bindings bindings
     * @return the resolved type (the input if unbound)
     */
    public static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
        Type current = type;
        int guard = 0;
        while (current instanceof TypeVariable<?> tv && bindings.containsKey(tv) && guard++ < 16) {
            current = bindings.get(tv);
        }
        return current;
    }

    /**
     * Erasure of a type.
     *
     * @param type     type
     * @param bindings bindings for type variables
     * @return the raw class
     */
    public static Class<?> raw(Type type, Map<TypeVariable<?>, Type> bindings) {
        Type t = resolve(type, bindings);
        return switch (t) {
            case Class<?> c -> c;
            case ParameterizedType p -> (Class<?>) p.getRawType();
            case GenericArrayType g -> raw(g.getGenericComponentType(), bindings).arrayType();
            case WildcardType w -> raw(w.getUpperBounds()[0], bindings);
            case TypeVariable<?> v -> raw(v.getBounds()[0], bindings);
            default -> Object.class;
        };
    }
}
