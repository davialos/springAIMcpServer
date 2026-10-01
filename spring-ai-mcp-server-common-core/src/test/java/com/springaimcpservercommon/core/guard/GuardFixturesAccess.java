package com.springaimcpservercommon.core.guard;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.AttributeDescriptor;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EntityDescriptor;
import com.springaimcpservercommon.core.catalog.ScannedCatalog;
import com.springaimcpservercommon.core.policy.PolicyMerger;

import java.time.Instant;
import java.util.List;

/** Catalog fixtures shared with tests in other packages. */
public final class GuardFixturesAccess {

    private GuardFixturesAccess() {
    }

    /**
     * A one-entity catalog with a single sensitive attribute.
     *
     * @param sensitiveAttribute attribute name marked sensitive
     * @return the effective catalog
     */
    public static EffectiveCatalog catalogWithSensitive(String sensitiveAttribute) {
        String type = "com.shop.Customer";
        EntityDescriptor customer = new EntityDescriptor(CatalogElementRef.entity(type), type, "Customer",
                "A customer", List.of(), Classification.INTERNAL, 50, List.of(),
                List.of(new AttributeDescriptor(AttributeDescriptor.refOf(type, sensitiveAttribute), sensitiveAttribute,
                        "java.lang.String", "Internal", true, false, Classification.INHERIT, false)),
                List.of(), "default");
        return new PolicyMerger(200, false).merge(ScannedCatalog.of(Instant.EPOCH, "1.0", List.of(customer),
                List.of(), List.of(), List.of()), List.of(), 1);
    }
}
