package com.springaimcpservercommon.core.guard;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.AttributeDescriptor;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EntityDescriptor;
import com.springaimcpservercommon.core.catalog.JsonSchema;
import com.springaimcpservercommon.core.catalog.OperationDescriptor;
import com.springaimcpservercommon.core.catalog.ParamDescriptor;
import com.springaimcpservercommon.core.catalog.ResultBounding;
import com.springaimcpservercommon.core.catalog.ScannedCatalog;
import com.springaimcpservercommon.core.policy.PolicyMerger;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A small shop catalog (customers, orders, invoices) and request helpers for the guard tests. */
final class GuardFixtures {

    static final DaiPrincipal PRINCIPAL = new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "local", "alice",
            "Alice", Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of());

    private GuardFixtures() {
    }

    static EffectiveCatalog shopCatalog(long generation) {
        String customerType = "com.shop.Customer";
        String orderType = "com.shop.PurchaseOrder";
        EntityDescriptor customer = new EntityDescriptor(CatalogElementRef.entity(customerType), customerType,
                "Customer", "A person or company that buys from the shop", List.of("client", "buyer"),
                Classification.INTERNAL, 50, List.of(),
                List.of(attr(customerType, "loyaltyTier", "Loyalty programme tier: bronze, silver or gold", false),
                        attr(customerType, "taxNumber", "Tax number", true)),
                List.of(), "default");
        EntityDescriptor order = new EntityDescriptor(CatalogElementRef.entity(orderType), orderType, "Order",
                "A purchase order placed by a customer, with its status and delivery", List.of("purchase", "shipment"),
                Classification.INTERNAL, 50, List.of(),
                List.of(attr(orderType, "status", "Order status such as open, shipped or cancelled", false),
                        attr(orderType, "grandTotal", "Order total including tax, in EUR", false)),
                List.of(), "default");
        CatalogElementRef opRef = CatalogElementRef.operation("com.shop.InvoiceService", "findInvoices",
                List.of("java.lang.Long"));
        OperationDescriptor op = new OperationDescriptor(opRef, "invoiceService", "com.shop.InvoiceService",
                "com.shop.InvoiceService", "findInvoices", List.of("java.lang.Long"), "find_invoices",
                "Finds unpaid and overdue invoices of a customer", List.of("billing", "invoice"),
                List.of(new ParamDescriptor("customerId", 0, "java.lang.Long", null, true, false,
                        ParamDescriptor.Kind.VALUE)),
                JsonSchema.emptyObject(), null, "java.lang.Object", true, false, Classification.INTERNAL,
                new ResultBounding(ResultBounding.Kind.NOT_A_LIST, null), null, null);
        ScannedCatalog scanned = ScannedCatalog.of(Instant.parse("2026-09-30T10:00:00Z"), "1.0",
                List.of(customer, order), List.of(op), List.of(), List.of());
        return new PolicyMerger(200, false).merge(scanned, List.of(), generation);
    }

    private static AttributeDescriptor attr(String type, String name, String meaning, boolean sensitive) {
        return new AttributeDescriptor(AttributeDescriptor.refOf(type, name), name, "java.lang.String", meaning,
                sensitive, false, Classification.INHERIT, false);
    }

    static PromptValidationRequest request(String prompt, InputValidationPolicy policy, EffectiveCatalog catalog) {
        return request(prompt, policy, catalog, List.of());
    }

    static PromptValidationRequest request(String prompt, InputValidationPolicy policy, EffectiveCatalog catalog,
                                           List<String> topics) {
        return new PromptValidationRequest(prompt, UUID.randomUUID(), "shop-assistant", PRINCIPAL, policy, topics,
                () -> catalog);
    }

    static InputValidationPolicy threatsOnly() {
        return new InputValidationPolicy(true, false, 0.25, 2, List.of());
    }

    static InputValidationPolicy scopeOnly() {
        return new InputValidationPolicy(false, true, 0.25, 2, List.of());
    }
}
