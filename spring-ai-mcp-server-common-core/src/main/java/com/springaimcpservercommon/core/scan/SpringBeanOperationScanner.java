package com.springaimcpservercommon.core.scan;

import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiExposedAction;
import com.springaimcpservercommon.annotations.AiParam;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.ContextDescriptor;
import com.springaimcpservercommon.core.catalog.EntityCatalogSource;
import com.springaimcpservercommon.core.catalog.EntityDescriptor;
import com.springaimcpservercommon.core.catalog.JsonSchema;
import com.springaimcpservercommon.core.catalog.OperationDescriptor;
import com.springaimcpservercommon.core.catalog.ParamDescriptor;
import com.springaimcpservercommon.core.catalog.ResultBounding;
import com.springaimcpservercommon.core.catalog.ScanIssue;
import com.springaimcpservercommon.core.catalog.ScanIssueCode;
import com.springaimcpservercommon.core.catalog.ScannedCatalog;
import com.springaimcpservercommon.core.catalog.ToolNames;
import com.springaimcpservercommon.core.lint.TextLint;
import com.springaimcpservercommon.core.schema.GenericTypes;
import com.springaimcpservercommon.core.schema.JsonSchemaMapper;
import com.springaimcpservercommon.core.schema.ParameterSpec;
import com.springaimcpservercommon.core.schema.SchemaResult;
import com.springaimcpservercommon.core.schema.SpringDataTypes;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.BaseStream;

/**
 * Scans a {@link ListableBeanFactory} once for {@code @AiExposedAction} and {@code @AiContext} (LLD-02 §3–4) and
 * assembles the {@link ScannedCatalog} together with the entities of the given {@link EntityCatalogSource}s.
 *
 * <p>Guards (LLD-02 §3.2): bean types come from {@code getType(name, false)} so lazy beans and FactoryBeans are
 * never initialised; CGLIB classes are unwrapped with {@link ClassUtils#getUserClass(Class)}; for JDK dynamic
 * proxies the interfaces (the only invocable methods) are scanned and annotations are merged from the target class
 * when the bean definition reveals it; {@code ROLE_INFRASTRUCTURE}, abstract and scoped-target definitions are
 * skipped; only types under the base packages are considered (a dependency cannot expose itself). Methods: public,
 * non-static, non-bridge, non-synthetic; annotations via {@code MergedAnnotations.from(method, TYPE_HIERARCHY)}
 * so interface-declared annotations count. {@code @Transactional} is detected by annotation <em>name</em>
 * (no spring-tx dependency). Web controllers ({@code @Controller}/{@code @RestController}, by name) contribute
 * context only.
 *
 * <p>Lint (LLD-02 §4, LLD-14 §3): see {@link ScanIssueCode}. Failures of a single bean or method are recorded as
 * {@link ScanIssueCode#SCAN_FAILED} and never propagate (fail the feature, not the host).
 *
 * <p>Output ordering is deterministic (sorted by reference), so two scans of the same code produce the same
 * fingerprint.
 */
public final class SpringBeanOperationScanner {

    private static final Logger log = LoggerFactory.getLogger(SpringBeanOperationScanner.class);

    private static final String CONTROLLER = "org.springframework.stereotype.Controller";
    private static final List<String> TRANSACTIONAL = List.of(
            "org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional");
    private static final String SCOPED_TARGET_PREFIX = "scopedTarget.";
    private static final Set<String> LIMIT_PARAMETER_NAMES = Set.of("limit", "maxresults", "pagesize", "max", "top");

    private final ScanOptions options;
    private final JsonSchemaMapper schemaMapper;
    private final TextLint lint;
    private final Clock clock;

    /**
     * Creates the scanner.
     *
     * @param options scan options
     * @param clock   clock for {@link ScannedCatalog#scannedAt()}
     */
    public SpringBeanOperationScanner(ScanOptions options, Clock clock) {
        this.options = Objects.requireNonNull(options, "options");
        this.schemaMapper = new JsonSchemaMapper(options.schemaOptions());
        this.lint = options.textLint();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Scans beans only (no entity sources).
     *
     * @param beanFactory the host bean factory (or application context)
     * @return the scanned catalog
     */
    public ScannedCatalog scan(ListableBeanFactory beanFactory) {
        return scan(beanFactory, List.of());
    }

    /**
     * Scans beans and collects entities from the given sources, then applies cross-element lint (duplicate tool
     * names, outcome-action hint).
     *
     * @param beanFactory   the host bean factory (or application context)
     * @param entitySources entity sources (one per entity manager factory)
     * @return the scanned catalog
     */
    public ScannedCatalog scan(ListableBeanFactory beanFactory, List<EntityCatalogSource> entitySources) {
        Objects.requireNonNull(beanFactory, "beanFactory");
        List<ScanIssue> issues = new ArrayList<>();
        Map<CatalogElementRef, EntityDescriptor> entities = scanEntities(entitySources, issues);

        List<BeanCandidate> candidates = candidates(beanFactory, issues);
        Map<CatalogElementRef, ContextDescriptor> contexts = new TreeMap<>(Comparator.comparing(Object::toString));
        List<OperationDescriptor> operations = new ArrayList<>();
        Map<String, List<BeanCandidate>> byType = new TreeMap<>();
        candidates.forEach(c -> byType.computeIfAbsent(c.declaringType(), k -> new ArrayList<>()).add(c));

        for (Map.Entry<String, List<BeanCandidate>> entry : byType.entrySet()) {
            List<BeanCandidate> beans = entry.getValue();
            BeanCandidate first = beans.getFirst();
            try {
                ContextDescriptor ctx = classContext(first, issues);
                if (ctx != null) {
                    contexts.putIfAbsent(ctx.ref(), ctx);
                }
                if (first.controller()) {
                    controllerContexts(first, contexts, issues);
                }
                List<OperationDescriptor> ops = new ArrayList<>();
                for (Invocable inv : first.invocables()) {
                    try {
                        OperationDescriptor op = operation(first, inv, ctx, issues);
                        if (op != null) {
                            ops.add(op);
                        }
                    } catch (RuntimeException | LinkageError e) {
                        issues.add(ScanIssue.ofSubject(ScanIssueCode.SCAN_FAILED, first.beanName() + "#"
                                + inv.method().getName(), "scanning the method failed ("
                                + e.getClass().getSimpleName() + "); action skipped", true));
                    }
                }
                if (beans.size() > 1 && !ops.isEmpty()) {
                    String names = String.join(", ", beans.stream().map(BeanCandidate::beanName).toList());
                    for (OperationDescriptor op : ops) {
                        issues.add(ScanIssue.of(ScanIssueCode.AMBIGUOUS_BEAN_TYPE, op.ref(), "beans [" + names
                                + "] share the type " + entry.getKey() + "; the action cannot be bound to one bean"
                                + " and is excluded (qualify a single bean or split the type)", true));
                    }
                } else {
                    operations.addAll(ops);
                }
            } catch (RuntimeException | LinkageError e) {
                issues.add(ScanIssue.ofSubject(ScanIssueCode.SCAN_FAILED, first.beanName(), "scanning the bean failed ("
                        + e.getClass().getSimpleName() + "); bean skipped", true));
            }
        }

        List<OperationDescriptor> unique = excludeDuplicateToolNames(operations, issues);
        outcomeHints(unique, issues);
        ScannedCatalog catalog = ScannedCatalog.of(clock.instant(), options.hostVersion(), entities.values(), unique,
                contexts.values(), issues);
        log.debug("AI catalog scan: {} entities, {} operations, {} contexts, {} issues",
                catalog.entities().size(), catalog.operations().size(), catalog.contexts().size(), catalog.issues().size());
        return catalog;
    }

    // ---- entities -------------------------------------------------------------------------------------------

    private static Map<CatalogElementRef, EntityDescriptor> scanEntities(List<EntityCatalogSource> sources,
                                                                        List<ScanIssue> issues) {
        Map<CatalogElementRef, EntityDescriptor> entities = new LinkedHashMap<>();
        for (EntityCatalogSource source : sources) {
            List<EntityDescriptor> scanned;
            try {
                scanned = source.scanEntities(issues::add);
            } catch (RuntimeException | LinkageError e) {
                issues.add(ScanIssue.ofSubject(ScanIssueCode.SCAN_FAILED, source.sourceId(), "entity source failed ("
                        + e.getClass().getSimpleName() + "); it contributes no entities", true));
                continue;
            }
            for (EntityDescriptor e : scanned) {
                if (entities.putIfAbsent(e.ref(), e) != null) {
                    issues.add(ScanIssue.of(ScanIssueCode.DUPLICATE_ELEMENT, e.ref(), "entity also produced by source "
                            + source.sourceId() + "; the first occurrence is kept", false));
                }
            }
        }
        return entities;
    }

    // ---- bean discovery -------------------------------------------------------------------------------------

    /** A method to invoke through the bean proxy, and the method carrying its annotations. */
    private record Invocable(Method method, Method annotated, String invocationType) {
    }

    private record BeanCandidate(String beanName, Class<?> declaringClass, boolean controller,
                                 List<Invocable> invocables) {
        String declaringType() {
            return declaringClass.getName();
        }
    }

    private List<BeanCandidate> candidates(ListableBeanFactory bf, List<ScanIssue> issues) {
        String[] names = bf.getBeanDefinitionNames();
        Arrays.sort(names);
        List<BeanCandidate> out = new ArrayList<>();
        for (String name : names) {
            if (name.startsWith(SCOPED_TARGET_PREFIX)) {
                continue;
            }
            try {
                BeanDefinition bd = definition(bf, name);
                if (bd != null && (bd.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE || bd.isAbstract())) {
                    continue;
                }
                Class<?> type = bf.getType(name, false);
                if (type == null) {
                    continue;
                }
                BeanCandidate candidate = candidate(name, type, bd, issues);
                if (candidate != null) {
                    out.add(candidate);
                }
            } catch (RuntimeException | LinkageError e) {
                issues.add(ScanIssue.ofSubject(ScanIssueCode.SCAN_FAILED, name, "inspecting the bean failed ("
                        + e.getClass().getSimpleName() + "); bean skipped", true));
            }
        }
        return out;
    }

    private @Nullable BeanCandidate candidate(String name, Class<?> type, @Nullable BeanDefinition bd,
                                              List<ScanIssue> issues) {
        if (Proxy.isProxyClass(type)) {
            List<Class<?>> interfaces = Arrays.stream(type.getInterfaces())
                    .filter(i -> !i.getName().startsWith("org.springframework."))
                    .sorted(Comparator.comparing(Class::getName)).toList();
            Class<?> target = jdkProxyTarget(bd, interfaces);
            Class<?> declaring = target != null ? target
                    : interfaces.stream().filter(i -> options.inScope(i.getName())).findFirst().orElse(null);
            if (declaring == null || !options.inScope(declaring.getName())) {
                return null;
            }
            Map<String, Invocable> invocables = new TreeMap<>();
            Set<String> interfaceSignatures = new LinkedHashSet<>();
            for (Class<?> itf : interfaces) {
                for (Method m : itf.getMethods()) {
                    if (!isCandidateMethod(m)) {
                        continue;
                    }
                    String sig = signature(m);
                    interfaceSignatures.add(sig);
                    Method annotated = target != null ? ClassUtils.getMostSpecificMethod(m, target) : m;
                    invocables.putIfAbsent(sig, new Invocable(m, annotated, itf.getName()));
                }
            }
            if (target != null) {
                reportNonInvocable(name, target, issues);
                for (Method m : target.getMethods()) {
                    if (isCandidateMethod(m) && !interfaceSignatures.contains(signature(m))
                            && exposedAction(m).isPresent()) {
                        issues.add(ScanIssue.ofSubject(ScanIssueCode.NOT_A_SPRING_BEAN, name + "#" + m.getName(),
                                "@AiExposedAction method is not declared on any interface of the JDK-proxied bean, so it "
                                        + "cannot be invoked through the proxy; declare it on the interface or use "
                                        + "class-based proxies", true));
                    }
                }
            }
            return new BeanCandidate(name, declaring, isController(declaring), List.copyOf(invocables.values()));
        }
        Class<?> user = ClassUtils.getUserClass(type);
        if (!options.inScope(user.getName())) {
            return null;
        }
        reportNonInvocable(name, user, issues);
        Map<String, Invocable> invocables = new TreeMap<>();
        for (Method m : user.getMethods()) {
            if (isCandidateMethod(m)) {
                invocables.putIfAbsent(signature(m), new Invocable(m, m, user.getName()));
            }
        }
        return new BeanCandidate(name, user, isController(user), List.copyOf(invocables.values()));
    }

    private static @Nullable Class<?> jdkProxyTarget(@Nullable BeanDefinition bd, List<Class<?>> interfaces) {
        if (bd == null) {
            return null;
        }
        Class<?> resolved = bd.getResolvableType().resolve();
        if (resolved == null || resolved.isInterface() || Proxy.isProxyClass(resolved)) {
            return null;
        }
        Class<?> user = ClassUtils.getUserClass(resolved);
        for (Class<?> itf : interfaces) {
            if (!itf.isAssignableFrom(user)) {
                return null;
            }
        }
        return user;
    }

    private void reportNonInvocable(String beanName, Class<?> type, List<ScanIssue> issues) {
        for (Class<?> c = type; c != null && c != Object.class && options.inScope(c.getName()); c = c.getSuperclass()) {
            Method[] declared = c.getDeclaredMethods();
            Arrays.sort(declared, Comparator.comparing(Method::toGenericString));
            for (Method m : declared) {
                if (m.isBridge() || m.isSynthetic() || m.getAnnotation(AiExposedAction.class) == null) {
                    continue;
                }
                if (Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers())) {
                    issues.add(ScanIssue.ofSubject(ScanIssueCode.NOT_A_SPRING_BEAN, beanName + "#" + m.getName(),
                            "@AiExposedAction on a static or non-public method cannot be invoked through the bean proxy"
                                    + " (ADR-0008); ignored", true));
                }
            }
        }
    }

    private static boolean isCandidateMethod(Method m) {
        return !Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers()) && !m.isBridge()
                && !m.isSynthetic() && m.getDeclaringClass() != Object.class;
    }

    private static String signature(Method m) {
        return m.getName() + "(" + String.join(",", Arrays.stream(m.getParameterTypes()).map(Class::getTypeName).toList()) + ")";
    }

    private static boolean isController(Class<?> type) {
        return MergedAnnotations.from(type, SearchStrategy.TYPE_HIERARCHY).isPresent(CONTROLLER);
    }

    private static @Nullable BeanDefinition definition(ListableBeanFactory bf, String name) {
        try {
            if (bf instanceof ConfigurableListableBeanFactory c) {
                return c.getBeanDefinition(name);
            }
            if (bf instanceof BeanDefinitionRegistry r) {
                return r.getBeanDefinition(name);
            }
            if (bf instanceof org.springframework.context.ConfigurableApplicationContext ctx) {
                return ctx.getBeanFactory().getBeanDefinition(name);
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static Optional<AiExposedAction> exposedAction(Method m) {
        MergedAnnotation<AiExposedAction> a = MergedAnnotations.from(m, SearchStrategy.TYPE_HIERARCHY)
                .get(AiExposedAction.class);
        return a.isPresent() ? Optional.of(a.synthesize()) : Optional.empty();
    }

    // ---- contexts -------------------------------------------------------------------------------------------

    private @Nullable ContextDescriptor classContext(BeanCandidate bean, List<ScanIssue> issues) {
        MergedAnnotation<AiContext> merged = MergedAnnotations.from(bean.declaringClass(), SearchStrategy.TYPE_HIERARCHY)
                .get(AiContext.class);
        if (!merged.isPresent()) {
            return null;
        }
        AiContext a = merged.synthesize();
        CatalogElementRef ref = new CatalogElementRef(CatalogElementRef.Kind.CTX, bean.declaringType());
        if (!textOk(ref, a.description(), TextLint.MAX_DESCRIPTION_LENGTH, issues)
                || !keywordsOk(ref, List.of(a.keywords()), issues)) {
            return null;
        }
        String name = a.name().isBlank() ? bean.declaringClass().getSimpleName() : a.name();
        return new ContextDescriptor(ref, bean.beanName(), bean.declaringType(), null, name, a.description(),
                List.of(a.keywords()), typeClassification(ref, a.classification(), issues), bean.controller());
    }

    private void controllerContexts(BeanCandidate bean, Map<CatalogElementRef, ContextDescriptor> contexts,
                                    List<ScanIssue> issues) {
        Classification classDefault = Optional.ofNullable(contexts.get(
                        new CatalogElementRef(CatalogElementRef.Kind.CTX, bean.declaringType())))
                .map(ContextDescriptor::classification).orElse(Classification.INTERNAL);
        for (Invocable inv : bean.invocables()) {
            MergedAnnotations anns = MergedAnnotations.from(inv.annotated(), SearchStrategy.TYPE_HIERARCHY);
            if (anns.isPresent(AiExposedAction.class)) {
                issues.add(ScanIssue.ofSubject(ScanIssueCode.CONTROLLER_ACTION_IGNORED, bean.beanName() + "#"
                        + inv.method().getName(), "controllers contribute context only; put @AiExposedAction on the "
                        + "service method the controller calls", true));
            }
            MergedAnnotation<AiContext> merged = anns.get(AiContext.class);
            if (!merged.isPresent()) {
                continue;
            }
            AiContext a = merged.synthesize();
            CatalogElementRef ref = new CatalogElementRef(CatalogElementRef.Kind.CTX, bean.declaringType() + "#"
                    + signature(inv.annotated()).replace(" ", ""));
            if (!textOk(ref, a.description(), TextLint.MAX_DESCRIPTION_LENGTH, issues)
                    || !keywordsOk(ref, List.of(a.keywords()), issues)) {
                continue;
            }
            Classification c = a.classification() == Classification.INHERIT ? classDefault : a.classification();
            String name = a.name().isBlank() ? inv.method().getName() : a.name();
            contexts.putIfAbsent(ref, new ContextDescriptor(ref, bean.beanName(), bean.declaringType(),
                    inv.method().getName(), name, a.description(), List.of(a.keywords()), c, true));
        }
    }

    private static Classification typeClassification(CatalogElementRef ref, Classification declared,
                                                     List<ScanIssue> issues) {
        if (declared == Classification.INHERIT) {
            issues.add(ScanIssue.of(ScanIssueCode.INVALID_CLASSIFICATION, ref,
                    "Classification.INHERIT is not allowed on types; INTERNAL is used", false));
            return Classification.INTERNAL;
        }
        return declared;
    }

    // ---- operations -----------------------------------------------------------------------------------------

    private @Nullable OperationDescriptor operation(BeanCandidate bean, Invocable inv, @Nullable ContextDescriptor ctx,
                                                    List<ScanIssue> issues) {
        Method m = inv.annotated();
        MergedAnnotations anns = MergedAnnotations.from(m, SearchStrategy.TYPE_HIERARCHY);
        MergedAnnotation<AiExposedAction> exposed = anns.get(AiExposedAction.class);
        if (!exposed.isPresent() || bean.controller()) {
            return null; // controllers were reported in controllerContexts
        }
        AiExposedAction action = exposed.synthesize();
        List<String> parameterTypes = Arrays.stream(m.getParameterTypes()).map(Class::getTypeName).toList();
        CatalogElementRef ref = CatalogElementRef.operation(bean.declaringType(), m.getName(), parameterTypes);

        boolean ok = textOk(ref, action.intent(), TextLint.MAX_DESCRIPTION_LENGTH, issues);
        MergedAnnotation<AiContext> methodContext = anns.get(AiContext.class);
        List<String> keywords = new ArrayList<>(List.of(action.keywords()));
        if (methodContext.isPresent()) {
            keywords.addAll(List.of(methodContext.synthesize().keywords()));
        }
        keywords = keywords.stream().distinct().toList();
        ok &= keywordsOk(ref, keywords, issues);

        String toolName = action.name().isBlank() ? ToolNames.snakeCase(m.getName()) : action.name();
        if (!ToolNames.isValid(toolName)) {
            issues.add(ScanIssue.of(ScanIssueCode.INVALID_TOOL_NAME, ref, "tool name '" + toolName
                    + "' does not match " + ToolNames.PATTERN.pattern() + "; set @AiExposedAction(name=...)", true));
            ok = false;
        }

        Map<TypeVariable<?>, Type> bindings = GenericTypes.bindingsOf(bean.declaringClass());
        List<ParamDescriptor> params = new ArrayList<>();
        List<ParameterSpec> specs = new ArrayList<>();
        Parameter[] parameters = m.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter p = parameters[i];
            AiParam aiParam = parameterAnnotation(m, i);
            String name;
            if (aiParam != null && !aiParam.name().isBlank()) {
                name = aiParam.name();
            } else if (p.isNamePresent()) {
                name = p.getName();
            } else {
                issues.add(ScanIssue.of(ScanIssueCode.PARAMETER_NAMES_UNAVAILABLE, ref, "parameter " + i
                        + " has no name (compiled without -parameters); compile with -parameters or set @AiParam(name)",
                        true));
                ok = false;
                continue;
            }
            String description = aiParam == null ? null : aiParam.description();
            if (description != null && !textOk(ref, description, TextLint.MAX_DESCRIPTION_LENGTH, issues)) {
                ok = false;
            }
            ParamDescriptor.Kind kind = parameterKind(p.getType(), name, aiParam);
            boolean optionalType = p.getType() == Optional.class;
            boolean required = kind == ParamDescriptor.Kind.PAGEABLE || kind == ParamDescriptor.Kind.SPRING_DATA_LIMIT
                    ? false : aiParam != null ? aiParam.required() && !optionalType : !optionalType;
            params.add(new ParamDescriptor(name, i, p.getParameterizedType().getTypeName(),
                    description == null || description.isBlank() ? null : description, required,
                    aiParam != null && aiParam.sensitive(), kind));
            specs.add(new ParameterSpec(name, p.getParameterizedType(), description, required));
        }
        if (!ok) {
            return null;
        }

        SchemaResult input = schemaMapper.inputSchema(specs, bindings);
        if (input.complex()) {
            issues.add(ScanIssue.of(ScanIssueCode.COMPLEX_TOOL_ARGS, ref, "tool arguments are nested deeper than 2, "
                    + "contain maps or polymorphic types; prefer flat parameters (LLD-14 §3.2)", false));
        }
        reportUnconfirmed(ref, input, issues);
        JsonSchema returnSchema = null;
        if (m.getReturnType() != void.class && m.getReturnType() != Void.class) {
            SchemaResult ret = schemaMapper.schemaFor(m.getGenericReturnType(), bindings);
            reportUnconfirmed(ref, ret, issues);
            returnSchema = ret.schema();
        }

        ResultBounding bounding = bounding(m, bindings, params);
        if (!bounding.bounded()) {
            issues.add(ScanIssue.of(ScanIssueCode.UNBOUNDED_LIST_ACTION, ref, "list-returning action without a "
                    + "Pageable/Limit parameter, Page/Slice/Window return or @AiParam limit parameter"
                    + (options.strict() ? "; excluded (scan.strict=true)" : ""), options.strict()));
            if (options.strict()) {
                return null;
            }
        }

        if (action.readOnly() && readWriteTransaction(m, bean.declaringClass())) {
            issues.add(ScanIssue.of(ScanIssueCode.READ_ONLY_ACTION_IN_WRITE_TX, ref, "readOnly action runs in a "
                    + "read-write @Transactional; kept, the runtime write guard enforces read-only (ADR-0014)", false));
        }

        Classification classification = ctx != null ? ctx.classification() : Classification.INTERNAL;
        if (methodContext.isPresent() && methodContext.synthesize().classification() != Classification.INHERIT) {
            classification = classification.max(methodContext.synthesize().classification());
        }

        return new OperationDescriptor(ref, bean.beanName(), bean.declaringType(), inv.invocationType(), m.getName(),
                parameterTypes, toolName, action.intent(), keywords, params, input.schema(), returnSchema,
                m.getGenericReturnType().getTypeName(), action.readOnly(), action.idempotent(), classification,
                bounding, ctx == null ? null : ctx.ref(), subjectEntity(m, bindings));
    }

    private static @Nullable AiParam parameterAnnotation(Method method, int index) {
        AiParam direct = method.getParameters()[index].getAnnotation(AiParam.class);
        if (direct != null) {
            return direct;
        }
        // annotations declared on the same parameter of an interface or superclass method
        for (Class<?> type : ClassUtils.getAllInterfacesForClassAsSet(method.getDeclaringClass())) {
            AiParam found = parameterAnnotationOn(type, method, index);
            if (found != null) {
                return found;
            }
        }
        for (Class<?> c = method.getDeclaringClass().getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            AiParam found = parameterAnnotationOn(c, method, index);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static @Nullable AiParam parameterAnnotationOn(Class<?> type, Method method, int index) {
        try {
            Method other = type.getMethod(method.getName(), method.getParameterTypes());
            return other.getParameters()[index].getAnnotation(AiParam.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static ParamDescriptor.Kind parameterKind(Class<?> type, String name, @Nullable AiParam aiParam) {
        if (SpringDataTypes.isA(type, SpringDataTypes.PAGEABLE)) {
            return ParamDescriptor.Kind.PAGEABLE;
        }
        if (SpringDataTypes.isA(type, SpringDataTypes.LIMIT)) {
            return ParamDescriptor.Kind.SPRING_DATA_LIMIT;
        }
        boolean integral = type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class;
        if (aiParam != null && integral && LIMIT_PARAMETER_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            return ParamDescriptor.Kind.LIMIT;
        }
        return ParamDescriptor.Kind.VALUE;
    }

    private static ResultBounding bounding(Method m, Map<TypeVariable<?>, Type> bindings, List<ParamDescriptor> params) {
        Type returnType = unwrapOptional(m.getGenericReturnType(), bindings);
        Class<?> raw = GenericTypes.raw(returnType, bindings);
        if (SpringDataTypes.isPagedResult(raw)) {
            return new ResultBounding(ResultBounding.Kind.PAGED_RETURN_TYPE, null);
        }
        boolean list = (raw.isArray() && raw != byte[].class) || Collection.class.isAssignableFrom(raw)
                || BaseStream.class.isAssignableFrom(raw)
                || (Iterable.class.isAssignableFrom(raw) && !Map.class.isAssignableFrom(raw));
        if (!list) {
            return new ResultBounding(ResultBounding.Kind.NOT_A_LIST, null);
        }
        for (ParamDescriptor p : params) {
            switch (p.kind()) {
                case PAGEABLE -> {
                    return new ResultBounding(ResultBounding.Kind.PAGEABLE_PARAMETER, p.name());
                }
                case SPRING_DATA_LIMIT -> {
                    return new ResultBounding(ResultBounding.Kind.SPRING_DATA_LIMIT_PARAMETER, p.name());
                }
                case LIMIT -> {
                    return new ResultBounding(ResultBounding.Kind.LIMIT_PARAMETER, p.name());
                }
                case VALUE -> {
                    // not a bound
                }
            }
        }
        return new ResultBounding(ResultBounding.Kind.UNBOUNDED, null);
    }

    private static Type unwrapOptional(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (GenericTypes.raw(type, bindings) == Optional.class
                && type instanceof java.lang.reflect.ParameterizedType p) {
            return p.getActualTypeArguments()[0];
        }
        return type;
    }

    /** The {@code @AiContext}-annotated type an action returns (element type for collections/pages), if any. */
    private static @Nullable CatalogElementRef subjectEntity(Method m, Map<TypeVariable<?>, Type> bindings) {
        Type type = unwrapOptional(m.getGenericReturnType(), bindings);
        for (int guard = 0; guard < 4; guard++) {
            Class<?> raw = GenericTypes.raw(type, bindings);
            if (raw.isArray()) {
                type = Objects.requireNonNull(raw.getComponentType());
                continue;
            }
            if ((Iterable.class.isAssignableFrom(raw) || BaseStream.class.isAssignableFrom(raw)
                    || SpringDataTypes.isPagedResult(raw) || raw == Optional.class)
                    && type instanceof java.lang.reflect.ParameterizedType p && p.getActualTypeArguments().length == 1) {
                type = p.getActualTypeArguments()[0];
                continue;
            }
            if (!raw.isPrimitive() && !raw.getName().startsWith("java.") && raw.isAnnotationPresent(AiContext.class)) {
                return CatalogElementRef.entity(raw.getName());
            }
            return null;
        }
        return null;
    }

    private static boolean readWriteTransaction(Method m, Class<?> declaringClass) {
        MergedAnnotations methodAnns = MergedAnnotations.from(m, SearchStrategy.TYPE_HIERARCHY);
        for (String tx : TRANSACTIONAL) {
            MergedAnnotation<?> onMethod = methodAnns.get(tx);
            if (onMethod.isPresent()) {
                return !isReadOnlyTx(onMethod);
            }
        }
        MergedAnnotations classAnns = MergedAnnotations.from(declaringClass, SearchStrategy.TYPE_HIERARCHY);
        for (String tx : TRANSACTIONAL) {
            MergedAnnotation<?> onClass = classAnns.get(tx);
            if (onClass.isPresent()) {
                return !isReadOnlyTx(onClass);
            }
        }
        return false;
    }

    private static boolean isReadOnlyTx(MergedAnnotation<?> tx) {
        // jakarta.transaction.Transactional has no readOnly attribute: treat as read-write
        return tx.getValue("readOnly").map(Boolean.TRUE::equals).orElse(false);
    }

    // ---- lint helpers ---------------------------------------------------------------------------------------

    private boolean textOk(CatalogElementRef ref, String text, int max, List<ScanIssue> issues) {
        boolean ok = true;
        for (TextLint.Finding f : lint.check(text, max)) {
            ScanIssueCode code = switch (f.kind()) {
                case BLANK -> ScanIssueCode.MISSING_DESCRIPTION;
                case TOO_LONG -> ScanIssueCode.DESCRIPTION_TOO_LONG;
                case SECRET -> ScanIssueCode.SECRET_IN_DESCRIPTION;
            };
            issues.add(ScanIssue.of(code, ref, f.detail() + "; element excluded", true));
            ok = false;
        }
        return ok;
    }

    private boolean keywordsOk(CatalogElementRef ref, List<String> keywords, List<ScanIssue> issues) {
        boolean ok = true;
        for (TextLint.Finding f : lint.checkKeywords(keywords)) {
            ScanIssueCode code = f.kind() == TextLint.Kind.SECRET ? ScanIssueCode.SECRET_IN_DESCRIPTION
                    : f.kind() == TextLint.Kind.BLANK ? ScanIssueCode.MISSING_DESCRIPTION
                    : ScanIssueCode.DESCRIPTION_TOO_LONG;
            issues.add(ScanIssue.of(code, ref, "keyword: " + f.detail() + "; element excluded", true));
            ok = false;
        }
        return ok;
    }

    private static void reportUnconfirmed(CatalogElementRef ref, SchemaResult result, List<ScanIssue> issues) {
        for (String member : result.unconfirmedSensitiveNames()) {
            issues.add(ScanIssue.of(ScanIssueCode.SENSITIVE_NAME_UNCONFIRMED, ref, "member " + member
                    + " looks sensitive; removed from the schema. Mark it @AiEntityProperty(sensitive=true) or list it "
                    + "in dynamic.ai.agent.scan.confirm-sensitive-names", false));
        }
    }

    // ---- cross-element lint ---------------------------------------------------------------------------------

    private static List<OperationDescriptor> excludeDuplicateToolNames(List<OperationDescriptor> operations,
                                                                       List<ScanIssue> issues) {
        Map<String, List<OperationDescriptor>> byTool = new TreeMap<>();
        operations.forEach(op -> byTool.computeIfAbsent(op.toolName(), k -> new ArrayList<>()).add(op));
        List<OperationDescriptor> unique = new ArrayList<>();
        for (Map.Entry<String, List<OperationDescriptor>> e : byTool.entrySet()) {
            if (e.getValue().size() == 1) {
                unique.add(e.getValue().getFirst());
                continue;
            }
            String refs = String.join(", ", e.getValue().stream().map(o -> o.ref().toString()).sorted().toList());
            for (OperationDescriptor op : e.getValue()) {
                issues.add(ScanIssue.of(ScanIssueCode.DUPLICATE_TOOL_NAME, op.ref(), "tool name '" + e.getKey()
                        + "' is used by " + refs + "; all are excluded (no silent winner). Set distinct "
                        + "@AiExposedAction(name=...)", true));
            }
        }
        return unique;
    }

    private void outcomeHints(List<OperationDescriptor> operations, List<ScanIssue> issues) {
        Map<CatalogElementRef, List<String>> byEntity = new TreeMap<>(Comparator.comparing(Object::toString));
        for (OperationDescriptor op : operations) {
            if (op.entity() != null) {
                byEntity.computeIfAbsent(op.entity(), k -> new ArrayList<>()).add(op.toolName());
            }
        }
        byEntity.forEach((entity, tools) -> {
            if (tools.size() > options.outcomeActionThreshold()) {
                issues.add(ScanIssue.of(ScanIssueCode.CONSIDER_OUTCOME_ACTION, entity, tools.size() + " actions on "
                        + "this entity (" + String.join(", ", tools.stream().sorted().toList()) + "); consider "
                        + "outcome-oriented actions to save model round trips (LLD-14 §3.1)", false));
            }
        });
    }
}
