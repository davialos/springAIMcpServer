package com.springaimcpservercommon.core.catalog;

import com.springaimcpservercommon.annotations.Classification;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Descriptive context from {@code @AiContext} on a Spring service or controller (class or controller method).
 * Contexts are never tools; they describe a tool group or background knowledge for prompts (LLD-02 §2).
 *
 * @param ref            {@code ctx:<type>} or, for a controller method, {@code ctx:<type>#<method>(<params>)}
 * @param beanName       bean that carries the context
 * @param javaType       user class of the bean
 * @param methodName     method name for method-level context, else {@code null}
 * @param name           logical name ({@code @AiContext.name}, the simple class name or the method name)
 * @param description    plain-English description (≤ 1024 chars)
 * @param keywords       domain terms
 * @param classification classification (never {@link Classification#INHERIT})
 * @param controller     whether the bean is a web controller (context only, never tools)
 */
public record ContextDescriptor(CatalogElementRef ref, String beanName, String javaType,
                                @Nullable String methodName, String name, String description,
                                List<String> keywords, Classification classification, boolean controller) {

    /** Validates components and copies collections. */
    public ContextDescriptor {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(beanName, "beanName");
        Objects.requireNonNull(javaType, "javaType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(classification, "classification");
        if (ref.kind() != CatalogElementRef.Kind.CTX) {
            throw new IllegalArgumentException("context ref must be of kind CTX: " + ref);
        }
        if (classification == Classification.INHERIT) {
            throw new IllegalArgumentException("context classification must be concrete: " + ref);
        }
        keywords = List.copyOf(keywords);
    }
}
