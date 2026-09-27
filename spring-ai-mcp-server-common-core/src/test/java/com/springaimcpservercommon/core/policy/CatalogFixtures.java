package com.springaimcpservercommon.core.policy;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.AttributeDescriptor;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EntityDescriptor;
import com.springaimcpservercommon.core.catalog.JsonSchema;
import com.springaimcpservercommon.core.catalog.OperationDescriptor;
import com.springaimcpservercommon.core.catalog.ParamDescriptor;
import com.springaimcpservercommon.core.catalog.RelationDescriptor;
import com.springaimcpservercommon.core.catalog.ResultBounding;
import com.springaimcpservercommon.core.catalog.ScannedCatalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Hand-built scanned catalog used by the policy tests. */
final class CatalogFixtures {

    static final CatalogElementRef CUSTOMER = CatalogElementRef.entity("com.host.app.Customer");
    static final CatalogElementRef ORDER = CatalogElementRef.entity("com.host.app.Order");
    static final CatalogElementRef TAX_ID = AttributeDescriptor.refOf("com.host.app.Customer", "taxId");
    static final CatalogElementRef EMAIL = AttributeDescriptor.refOf("com.host.app.Customer", "email");
    static final CatalogElementRef FIND_ORDERS = CatalogElementRef.operation("com.host.app.OrderService", "findOrders",
            List.of("java.lang.Long"));
    static final CatalogElementRef FIND_ORDERS_LIMITED = CatalogElementRef.operation("com.host.app.OrderService",
            "findOrders", List.of("java.lang.Long", "int"));
    static final CatalogElementRef DISCOUNT = CatalogElementRef.operation("com.host.app.OrderService",
            "calculateDiscount", List.of("java.lang.Long"));
    static final CatalogElementRef DELETE_USER = CatalogElementRef.operation("com.host.app.UserService", "deleteUser",
            List.of("java.lang.Long"));

    private CatalogFixtures() {
    }

    static ScannedCatalog catalog() {
        EntityDescriptor customer = new EntityDescriptor(CUSTOMER, "com.host.app.Customer", "Customer",
                "A customer", List.of("client"), Classification.INTERNAL, 50, List.of(),
                List.of(new AttributeDescriptor(TAX_ID, "taxId", "java.lang.String", "Tax id", false, false,
                                Classification.INHERIT, false),
                        new AttributeDescriptor(EMAIL, "email", "java.lang.String", "E-mail", false, true,
                                Classification.PUBLIC, false)),
                List.of(new RelationDescriptor("orders", RelationDescriptor.Kind.ONE_TO_MANY, ORDER, "customer", true)),
                "default");
        EntityDescriptor order = new EntityDescriptor(ORDER, "com.host.app.Order", "Order", "An order", List.of(),
                Classification.CONFIDENTIAL, 100, List.of("tenantId"), List.of(), List.of(), "default");
        List<OperationDescriptor> ops = new ArrayList<>();
        ops.add(op(FIND_ORDERS, "com.host.app.OrderService", "findOrders", List.of("java.lang.Long"), "find_orders",
                true, ORDER));
        ops.add(op(FIND_ORDERS_LIMITED, "com.host.app.OrderService", "findOrders", List.of("java.lang.Long", "int"),
                "find_orders_limited", true, ORDER));
        ops.add(op(DISCOUNT, "com.host.app.OrderService", "calculateDiscount", List.of("java.lang.Long"),
                "calculate_discount", true, null));
        ops.add(op(DELETE_USER, "com.host.app.UserService", "deleteUser", List.of("java.lang.Long"), "delete_user",
                false, null));
        return ScannedCatalog.of(Instant.parse("2026-09-28T10:00:00Z"), "1.0", List.of(customer, order), ops,
                List.of(), List.of());
    }

    static OperationDescriptor op(CatalogElementRef ref, String type, String method, List<String> paramTypes,
                                  String tool, boolean readOnly, CatalogElementRef entity) {
        List<ParamDescriptor> params = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            params.add(new ParamDescriptor("p" + i, i, paramTypes.get(i), null, true, false, ParamDescriptor.Kind.VALUE));
        }
        return new OperationDescriptor(ref, type.substring(type.lastIndexOf('.') + 1).toLowerCase(), type, type,
                method, paramTypes, tool, "Intent of " + tool, List.of("kw"), params, JsonSchema.emptyObject(), null,
                "java.lang.Object", readOnly, false, Classification.INTERNAL,
                new ResultBounding(ResultBounding.Kind.NOT_A_LIST, null), null, entity);
    }
}
