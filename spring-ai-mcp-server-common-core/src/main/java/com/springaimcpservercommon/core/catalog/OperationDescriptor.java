package com.springaimcpservercommon.core.catalog;

import com.springaimcpservercommon.annotations.Classification;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A scanned {@code @AiExposedAction}: a public method of a Spring bean that may become a tool (LLD-02 §2).
 *
 * <p>Invocation data is kept as names, not reflective handles, so the descriptor is a pure value that can be
 * hashed, exported and compared across deployments. The tool bridge resolves
 * {@code Class.forName(invocationType).getMethod(methodName, parameterTypes…)} and invokes it on
 * {@code getBean(beanName)} — always through the Spring proxy (ADR-0008).
 *
 * @param ref            {@code op:<declaringType>#<method>(<erased parameter types>)}
 * @param beanName       name of the Spring bean that exposes the action
 * @param declaringType  user class of the bean (CGLIB subclass unwrapped); for a JDK-proxied bean whose target
 *                       class cannot be determined, the interface
 * @param invocationType type on which the invocable method must be looked up: the user class, or the proxied
 *                       interface for JDK dynamic proxies
 * @param methodName     Java method name
 * @param parameterTypes erased, fully qualified parameter type names in declaration order
 * @param toolName       tool name ({@code ^[a-z][a-z0-9_]{2,63}$}); code-only, policies cannot change it
 * @param intent         {@code @AiExposedAction.intent}
 * @param keywords       keywords from the action and a method-level {@code @AiContext}
 * @param params         parameters in declaration order
 * @param inputSchema    JSON schema of the tool arguments (an object keyed by parameter name)
 * @param returnSchema   JSON schema of the return value, {@code null} for {@code void}
 * @param returnType     generic return type name
 * @param readOnly       {@code false} ⇒ proposal-only tool (ADR-0009)
 * @param idempotent     repeated calls have no additional effect
 * @param classification classification from the method/class {@code @AiContext} (stricter wins), default INTERNAL
 * @param bounding       how list results are bounded (LLD-14 §3.3)
 * @param context        {@code ctx:} reference of the bean's {@code @AiContext}, if any
 * @param entity         {@code entity:} reference of the {@code @AiContext}-annotated type the action returns
 *                       (element type for collections), if any; used for per-entity lint and kill switches
 */
public record OperationDescriptor(CatalogElementRef ref, String beanName, String declaringType,
                                  String invocationType, String methodName, List<String> parameterTypes,
                                  String toolName, String intent, List<String> keywords,
                                  List<ParamDescriptor> params, JsonSchema inputSchema,
                                  @Nullable JsonSchema returnSchema, String returnType, boolean readOnly,
                                  boolean idempotent, Classification classification, ResultBounding bounding,
                                  @Nullable CatalogElementRef context, @Nullable CatalogElementRef entity) {

    /** Validates components and copies collections. */
    public OperationDescriptor {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(beanName, "beanName");
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(invocationType, "invocationType");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(bounding, "bounding");
        if (ref.kind() != CatalogElementRef.Kind.OP) {
            throw new IllegalArgumentException("operation ref must be of kind OP: " + ref);
        }
        if (!ToolNames.isValid(toolName)) {
            throw new IllegalArgumentException("invalid tool name: " + toolName);
        }
        if (classification == Classification.INHERIT) {
            throw new IllegalArgumentException("operation classification must be concrete: " + ref);
        }
        parameterTypes = List.copyOf(parameterTypes);
        keywords = List.copyOf(keywords);
        params = params.stream().sorted(Comparator.comparingInt(ParamDescriptor::index)).toList();
        if (params.size() != parameterTypes.size()) {
            throw new IllegalArgumentException("params and parameterTypes differ in size: " + ref);
        }
    }

    /**
     * Whether the action is a write, i.e. registered as a proposal-only tool (ADR-0009).
     *
     * @return {@code true} if {@code readOnly == false}
     */
    public boolean proposalOnly() {
        return !readOnly;
    }
}
