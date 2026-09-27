package com.springaimcpservercommon.core.scan;

import com.example.host.Fixtures;
import com.example.thirdparty.ThirdPartyService;
import com.springaimcpservercommon.annotations.AiExposedAction;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.OperationDescriptor;
import com.springaimcpservercommon.core.catalog.ParamDescriptor;
import com.springaimcpservercommon.core.catalog.ResultBounding;
import com.springaimcpservercommon.core.catalog.ScanIssue;
import com.springaimcpservercommon.core.catalog.ScanIssueCode;
import com.springaimcpservercommon.core.catalog.ScannedCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.autoproxy.BeanNameAutoProxyCreator;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SpringBeanOperationScannerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private static final String BASE = "com.example.host";

    private GenericApplicationContext ctx;

    @BeforeEach
    void setUp() {
        Fixtures.LazyService.CREATED.set(false);
        ctx = new GenericApplicationContext();
        ctx.registerBean("proxyCreator", BeanNameAutoProxyCreator.class, bd -> {
            bd.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            bd.getPropertyValues().add("beanNames", new String[] {"pricingService", "inventoryApi"});
        });
        ctx.registerBean("orderService", Fixtures.OrderService.class);
        ctx.registerBean("reportService", Fixtures.ReportService.class);
        ctx.registerBean("catalogApi", Fixtures.CatalogApiImpl.class);
        ctx.registerBean("pricingService", Fixtures.PricingService.class);
        ctx.registerBean("inventoryApi", Fixtures.InventoryApiImpl.class);
        ctx.registerBean("lazyService", Fixtures.LazyService.class, bd -> bd.setLazyInit(true));
        ctx.registerBean("duplicateA", Fixtures.DuplicateA.class);
        ctx.registerBean("duplicateB", Fixtures.DuplicateB.class);
        ctx.registerBean("orderController", Fixtures.OrderController.class);
        ctx.registerBean("infrastructureService", Fixtures.InfrastructureService.class,
                bd -> bd.setRole(BeanDefinition.ROLE_INFRASTRUCTURE));
        ctx.registerBean("productService", Fixtures.ProductService.class);
        ctx.registerBean("thirdPartyService", ThirdPartyService.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private ScannedCatalog scan(ScanOptions options) {
        ctx.refresh();
        return new SpringBeanOperationScanner(options, CLOCK).scan(ctx);
    }

    private ScannedCatalog scan() {
        return scan(ScanOptions.defaults(List.of(BASE)));
    }

    private static Optional<OperationDescriptor> tool(ScannedCatalog catalog, String toolName) {
        return catalog.operationsByToolName(toolName).stream().findFirst();
    }

    private static List<ScanIssue> issues(ScannedCatalog catalog, ScanIssueCode code) {
        return catalog.issues().stream().filter(i -> i.code() == code).toList();
    }

    @Test
    void proxiesAreRealInThisFixture() {
        ctx.refresh();
        assertThat(ctx.getBean("pricingService").getClass().getName()).contains("$$");
        assertThat(Proxy.isProxyClass(ctx.getBean("inventoryApi").getClass())).isTrue();
    }

    @Test
    void scansPlainServiceActionWithSchemaContextAndEntity() {
        ScannedCatalog catalog = scan();
        OperationDescriptor op = tool(catalog, "find_order").orElseThrow();
        assertThat(op.ref().toString()).isEqualTo("op:" + Fixtures.OrderService.class.getName() + "#findOrder(java.lang.Long)");
        assertThat(op.beanName()).isEqualTo("orderService");
        assertThat(op.readOnly()).isTrue();
        assertThat(op.keywords()).containsExactly("lookup");
        assertThat(op.params()).extracting(ParamDescriptor::name).containsExactly("id");
        assertThat(op.inputSchema().json()).contains("\"id\":{\"description\":\"Order number\"");
        assertThat(op.returnSchema()).isNotNull();
        assertThat(op.returnSchema().json()).contains("Order total").doesNotContain("cardNumber");
        assertThat(op.context()).isEqualTo(new CatalogElementRef(CatalogElementRef.Kind.CTX, Fixtures.OrderService.class.getName()));
        assertThat(op.entity()).isEqualTo(CatalogElementRef.entity(Fixtures.Order.class.getName()));
        assertThat(op.bounding().kind()).isEqualTo(ResultBounding.Kind.NOT_A_LIST);
        assertThat(catalog.contexts()).containsKey(op.context());
    }

    @Test
    void writeActionsAreProposalOnlyAndSensitiveParamsFlagged() {
        OperationDescriptor op = tool(scan(), "cancel_order").orElseThrow();
        assertThat(op.readOnly()).isFalse();
        assertThat(op.proposalOnly()).isTrue();
        assertThat(op.returnSchema()).isNull();
        assertThat(op.params().get(1).sensitive()).isTrue();
    }

    @Test
    void listBoundingAndUnboundedLint() {
        ScannedCatalog catalog = scan();
        OperationDescriptor recent = tool(catalog, "find_recent_orders").orElseThrow();
        assertThat(recent.bounding()).isEqualTo(new ResultBounding(ResultBounding.Kind.LIMIT_PARAMETER, "limit"));
        assertThat(recent.params().get(1).kind()).isEqualTo(ParamDescriptor.Kind.LIMIT);
        assertThat(tool(catalog, "list_all_orders")).isPresent();
        assertThat(issues(catalog, ScanIssueCode.UNBOUNDED_LIST_ACTION))
                .singleElement().satisfies(i -> assertThat(i.excluded()).isFalse());
    }

    @Test
    void strictModeExcludesUnboundedListActions() {
        ScannedCatalog catalog = scan(ScanOptions.defaults(List.of(BASE)).withStrict(true));
        assertThat(tool(catalog, "list_all_orders")).isEmpty();
        assertThat(issues(catalog, ScanIssueCode.UNBOUNDED_LIST_ACTION)).singleElement()
                .satisfies(i -> assertThat(i.excluded()).isTrue());
    }

    @Test
    void secretsInDescriptionsExcludeTheAction() {
        ScannedCatalog catalog = scan();
        assertThat(tool(catalog, "leaky_secret")).isEmpty();
        assertThat(issues(catalog, ScanIssueCode.SECRET_IN_DESCRIPTION)).singleElement()
                .satisfies(i -> assertThat(i.message()).doesNotContain("hunter2"));
    }

    @Test
    void staticAnnotatedMethodsAreReportedNotExposed() {
        ScannedCatalog catalog = scan();
        assertThat(tool(catalog, "static_action")).isEmpty();
        assertThat(issues(catalog, ScanIssueCode.NOT_A_SPRING_BEAN))
                .anySatisfy(i -> assertThat(i.subject()).isEqualTo("orderService#staticAction"));
    }

    @Test
    void flagsReadOnlyActionsInReadWriteTransactions() {
        ScannedCatalog catalog = scan();
        List<ScanIssue> tx = issues(catalog, ScanIssueCode.READ_ONLY_ACTION_IN_WRITE_TX);
        assertThat(tx).extracting(i -> i.element().toString()).containsExactly(
                "op:" + Fixtures.ReportService.class.getName() + "#dailyReport()");
        assertThat(tool(catalog, "daily_report")).isPresent(); // kept; runtime guard enforces
    }

    @Test
    void findsInterfaceDeclaredAnnotations() {
        OperationDescriptor op = tool(scan(), "describe_catalog").orElseThrow();
        assertThat(op.declaringType()).isEqualTo(Fixtures.CatalogApiImpl.class.getName());
        assertThat(op.inputSchema().json()).contains("Section name");
    }

    @Test
    void unwrapsCglibProxies() {
        OperationDescriptor op = tool(scan(), "price_of").orElseThrow();
        assertThat(op.declaringType()).isEqualTo(Fixtures.PricingService.class.getName());
        assertThat(op.invocationType()).isEqualTo(Fixtures.PricingService.class.getName());
        assertThat(op.idempotent()).isTrue();
        assertThat(op.classification()).isEqualTo(Classification.PUBLIC);
    }

    @Test
    void jdkProxiesUseInterfaceForInvocationAndTargetForAnnotations() {
        ScannedCatalog catalog = scan();
        OperationDescriptor op = tool(catalog, "stock_level").orElseThrow();
        assertThat(op.declaringType()).isEqualTo(Fixtures.InventoryApiImpl.class.getName());
        assertThat(op.invocationType()).isEqualTo(Fixtures.InventoryApi.class.getName());
        assertThat(op.inputSchema().json()).contains("Product SKU");
        assertThat(tool(catalog, "hidden_from_proxy")).isEmpty();
        assertThat(issues(catalog, ScanIssueCode.NOT_A_SPRING_BEAN))
                .anySatisfy(i -> assertThat(i.subject()).isEqualTo("inventoryApi#hiddenFromProxy"));
    }

    @Test
    void lazyBeansStayUninitialised() {
        ScannedCatalog catalog = scan();
        assertThat(tool(catalog, "lazy_action")).isPresent();
        assertThat(Fixtures.LazyService.CREATED).isFalse();
    }

    @Test
    void duplicateToolNamesExcludeBothActions() {
        ScannedCatalog catalog = scan();
        assertThat(tool(catalog, "find_things")).isEmpty();
        assertThat(issues(catalog, ScanIssueCode.DUPLICATE_TOOL_NAME)).hasSize(2)
                .allSatisfy(i -> assertThat(i.message()).contains("searchA").contains("searchB"));
    }

    @Test
    void controllersContributeContextOnly() {
        ScannedCatalog catalog = scan();
        assertThat(tool(catalog, "controller_action")).isEmpty();
        assertThat(issues(catalog, ScanIssueCode.CONTROLLER_ACTION_IGNORED)).hasSize(1);
        assertThat(catalog.contexts().values()).anySatisfy(c -> {
            assertThat(c.controller()).isTrue();
            assertThat(c.methodName()).isEqualTo("showOrder");
        });
        assertThat(catalog.contexts()).containsKey(
                new CatalogElementRef(CatalogElementRef.Kind.CTX, Fixtures.OrderController.class.getName()));
    }

    @Test
    void skipsInfrastructureBeansAndTypesOutsideBasePackages() {
        ScannedCatalog catalog = scan();
        assertThat(tool(catalog, "infra_action")).isEmpty();
        assertThat(tool(catalog, "sneaky")).isEmpty();
    }

    @Test
    void hintsOutcomeActionsForManyActionsOnOneEntity() {
        ScannedCatalog catalog = scan();
        assertThat(issues(catalog, ScanIssueCode.CONSIDER_OUTCOME_ACTION)).singleElement()
                .satisfies(i -> assertThat(i.element()).isEqualTo(CatalogElementRef.entity(Fixtures.Product.class.getName())));
    }

    @Test
    void scanIsDeterministic() {
        ScannedCatalog first = scan();
        ctx.close();
        setUp();
        ScannedCatalog second = scan();
        assertThat(second.scanFingerprint()).isEqualTo(first.scanFingerprint());
        assertThat(second.canonicalJson()).isEqualTo(first.canonicalJson());
        assertThat(List.copyOf(second.operations().keySet())).isEqualTo(List.copyOf(first.operations().keySet()));
    }

    @Test
    void emptyBasePackagesScanNothing() {
        ScannedCatalog catalog = scan(ScanOptions.defaults(List.of()));
        assertThat(catalog.operations()).isEmpty();
        assertThat(catalog.contexts()).isEmpty();
    }

    @Test
    void missingParameterNamesExcludeTheAction(@TempDir Path dir) throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "a JDK compiler is required");
        Path src = dir.resolve("com/example/host/noparams/NoParamNamesService.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package com.example.host.noparams;
                import com.springaimcpservercommon.annotations.AiExposedAction;
                public class NoParamNamesService {
                    @AiExposedAction(intent = "Looks up a thing by its code")
                    public String lookupThing(String code) { return code; }
                }
                """);
        String annotationsLocation = Path.of(AiExposedAction.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toString();
        int rc = javac.run(null, null, null, "-proc:none", "-cp", annotationsLocation, "-d", dir.toString(),
                src.toString());
        assertThat(rc).isZero();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = loader.loadClass("com.example.host.noparams.NoParamNamesService");
            assertThat(type.getMethod("lookupThing", String.class).getParameters()[0].isNamePresent()).isFalse();
            registerRaw(type);
            ScannedCatalog catalog = scan();
            assertThat(tool(catalog, "lookup_thing")).isEmpty();
            assertThat(issues(catalog, ScanIssueCode.PARAMETER_NAMES_UNAVAILABLE)).singleElement()
                    .satisfies(i -> assertThat(i.excluded()).isTrue());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerRaw(Class<?> type) {
        ctx.registerBean("noParamNamesService", (Class) type);
    }
}
