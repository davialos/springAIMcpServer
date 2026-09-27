package com.springaimcpservercommon.core.policy;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Key of a policy override (LLD-03 §4.2): either a canonical {@link CatalogElementRef} or the {@code Class.method}
 * shorthand, which is accepted only if it resolves to exactly one operation.
 */
public sealed interface PolicyKey permits PolicyKey.Canonical, PolicyKey.Shorthand {

    /** {@code fully.qualified.Type.method}: at least one package/type segment plus a method segment. */
    Pattern SHORTHAND = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+$");

    /**
     * The key as written in the policy document.
     *
     * @return textual key
     */
    String text();

    /**
     * Parses a key: text containing {@code :} must be a canonical reference, anything else a shorthand.
     *
     * @param text key text
     * @return the key
     * @throws IllegalArgumentException if the key is malformed
     */
    static PolicyKey parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.indexOf(':') >= 0) {
            return new Canonical(CatalogElementRef.parse(text));
        }
        if (!SHORTHAND.matcher(text).matches()) {
            throw new IllegalArgumentException("policy key is neither a canonical ref (kind:value) nor a Class.method shorthand: "
                    + text);
        }
        int dot = text.lastIndexOf('.');
        return new Shorthand(text.substring(0, dot), text.substring(dot + 1));
    }

    /**
     * A canonical reference key ({@code op:…}, {@code entity:…}, {@code attr:…}).
     *
     * @param ref the reference
     */
    record Canonical(CatalogElementRef ref) implements PolicyKey {

        /** Validates the component. */
        public Canonical {
            Objects.requireNonNull(ref, "ref");
        }

        @Override
        public String text() {
            return ref.toString();
        }
    }

    /**
     * {@code Class.method} shorthand for an operation.
     *
     * @param className  fully qualified class (the bean's user class or the interface it is invoked through)
     * @param methodName method name
     */
    record Shorthand(String className, String methodName) implements PolicyKey {

        /** Validates components. */
        public Shorthand {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(methodName, "methodName");
        }

        @Override
        public String text() {
            return className + "." + methodName;
        }
    }
}
