package com.springaimcpservercommon.security.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflective access to optional Spring Security types (SAML 2, LDAP) whose modules are not dependencies of this
 * library. Only public no-arg getters declared on public types are invoked; failures yield {@code null}.
 */
public final class Reflection {

    private Reflection() {
    }

    /**
     * Whether the object's class, one of its superclasses or one of its interfaces has the given name.
     *
     * @param target    object to inspect
     * @param className fully qualified class or interface name
     * @return {@code true} if the type hierarchy contains the name
     */
    public static boolean isInstanceOf(@Nullable Object target, String className) {
        if (target == null) {
            return false;
        }
        for (Class<?> type : typeHierarchy(target.getClass())) {
            if (type.getName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Invokes a public no-arg method declared on a public type of the target's hierarchy.
     *
     * @param target     object, may be {@code null}
     * @param methodName getter name
     * @return the result, or {@code null} if absent, inaccessible or failing
     */
    public static @Nullable Object invokeGetter(@Nullable Object target, String methodName) {
        if (target == null) {
            return null;
        }
        for (Class<?> type : typeHierarchy(target.getClass())) {
            if (!Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            Method method;
            try {
                method = type.getMethod(methodName);
            } catch (NoSuchMethodException e) {
                continue;
            }
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            try {
                return method.invoke(target);
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static List<Class<?>> typeHierarchy(Class<?> type) {
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            types.add(c);
        }
        types.addAll(ClassUtils.getAllInterfacesForClassAsSet(type));
        return types;
    }
}
