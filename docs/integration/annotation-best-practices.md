# `@Ai*` Annotations — Best Practices, Centralized Configuration and Worked Scenarios

> **Audience.** Developers of a **host** Spring Boot application that adopts the springAIMcpServerCommon starter.
> Read [host-integration-guide.md](host-integration-guide.md) §1–§6 first; this guide goes deeper on *how to
> annotate well* and *where each piece of configuration should live* so it stays in one place.
>
> **Version scope.** `0.1.0-SNAPSHOT`. Every behaviour stated here was checked against the scanner
> (`core.scan.SpringBeanOperationScanner`), the entity scanner (`query.scan.JpaEntityCatalogSource`), the schema
> mapper (`core.schema.JsonSchemaMapper`) and `DaiProperties`. Things that are designed but **not wired yet** are
> marked **Not yet** with their open-question id — do not rely on them.

## Contents

1. [Mental model: what goes in code and what goes in configuration](#1-mental-model)
2. [The centralization blueprint](#2-the-centralization-blueprint)
3. [Shared building blocks (vocabulary, text, filters, limits)](#3-shared-building-blocks)
4. [`@AiContext`](#4-aicontext)
5. [`@AiEntityProperty`](#5-aientityproperty)
6. [`@AiExposedAction`](#6-aiexposedaction)
7. [`@AiParam`](#7-aiparam)
8. [`@AiQueryConstraints`](#8-aiqueryconstraints)
9. [`Classification`](#9-classification)
10. [End-to-end scenario: a support-desk assistant](#10-end-to-end-scenario-a-support-desk-assistant)
11. [Centralized environment configuration (`application.yml`)](#11-centralized-environment-configuration)
12. [Catalog contract test (the gate that keeps it all honest)](#12-catalog-contract-test)
13. [Runtime policy overrides (roadmap)](#13-runtime-policy-overrides-roadmap)
14. [Review checklist and anti-patterns](#14-review-checklist-and-anti-patterns)

---

## 1. Mental model

The library is **default deny**: nothing reaches a model, an MCP client or the dynamic query engine unless (a) a
developer annotated it **and** (b) an admin published and granted it. That splits the configuration into two
kinds of fact, each with exactly one owner:

| Fact | Owner | Where it lives | Changes with |
|---|---|---|---|
| *What exists and what it means* — descriptions, keywords, which attributes are visible, which are sensitive/writable, which methods are tools, whether they write | Developer | `@Ai*` annotations, with their text in **constant classes** (§3) | A code review + deploy |
| *Hard data-scope floors* — max rows per entity, attributes every query must be scoped by | Developer | `@AiQueryConstraints`, with values in **one constants class** (§3.3) | A code review + deploy |
| *How the library behaves in this environment* — tier, scan scope, strictness, MCP, writes, memory | Operator | `application.yml` + profile files (§11) | A config change / restart |
| *Who may use what, with which server-bound arguments* — tool bindings, grants, `argConstraints`, `mcpExposed`, suspend | Admins | Admin API / dashboard, stored in `dynamic_ai` (§10) | A reviewed publish, no deploy |
| *Runtime overrides of annotation text / disables* | Operator | Policy JSON (L1) and dashboard overlays (L2) | **Not yet** (§13) |

Rules of thumb:

- **Annotations say what is *true about the code*.** They never say who can call it, which tenant it serves, or
  anything environment-specific. Those facts change without a deploy and live in configuration.
- **Configuration can only restrict, never expose** (LLD-03 §4.1). A config file can never turn an unannotated
  method into a tool, a read into a write, or a sensitive attribute into a visible one.
- **One owner per fact.** A description is written once (a constant), a filter attribute name is written once (a
  constant), a limit is written once (a constant). Everything else references it.

### Where each annotation may go

| Host element | Annotation | Becomes |
|---|---|---|
| JPA `@Entity` class | `@AiContext` (+ `@AiQueryConstraints`) | Catalog entity |
| Entity **field** (incl. fields inherited from a `@MappedSuperclass`) | `@AiEntityProperty` | Catalog attribute (unannotated fields stay hidden) |
| Spring service class **or the interface it implements** | `@AiContext` | Tool-group context for its actions |
| Public method of a Spring bean, **or its declaration on an interface** | `@AiExposedAction` | Tool candidate |
| Parameter of such a method (on the class **or** interface declaration) | `@AiParam` | JSON-schema description of the argument |
| Method carrying `@AiExposedAction` | `@AiContext` (optional) | Extra keywords; can *raise* the action's classification |
| `@RestController` class | `@AiContext` | Descriptive context only — never a tool |
| DTO / record returned or accepted by an action | `@AiEntityProperty` on components/getters | Member descriptions; `sensitive=true` removes the member |

---

## 2. The centralization blueprint

Java annotation values must be **compile-time constants**: string literals, `static final String` constants
(also concatenations of them), `static final int` constants, enum constants and arrays of those. That is the key
that makes central management possible — every annotation can reference a constant defined in one place.

Recommended layout of a host application (`com.acme.support` is the example used throughout this guide):

```
com.acme.support
├── ai/                              ← the single home of everything AI-facing
│   ├── AiVocabulary.java            shared domain terms (keywords), written once
│   ├── AiScopes.java                attribute names used in mandatoryFilters + row limits
│   ├── CustomerAiText.java          all descriptions/meanings/intents for the Customer context
│   ├── OrderAiText.java             … for the Order context (tool names too)
│   └── tools/                       AI contracts: interfaces carrying @AiContext/@AiExposedAction/@AiParam
│       ├── CustomerAiTools.java
│       └── OrderAiTools.java
├── api/                             records returned by read tools (the model sees exactly these)
│   ├── CustomerCard.java
│   └── OrderSummary.java
├── domain/                          JPA entities — annotations reference ai.* constants
│   ├── TenantScopedEntity.java      @MappedSuperclass: tenantId/createdAt annotated once
│   ├── Customer.java  Order.java  PaymentCard.java  Country.java
├── service/                         implementations — no @Ai* text here, they implement ai.tools.*
│   ├── CustomerService.java  OrderService.java
└── web/
    └── OrderController.java         @AiContext only (context, never a tool)

src/main/resources/application.yml           ← library behaviour (+ application-dev.yml / -prod.yml)
src/test/java/.../AiCatalogContractTest.java ← the gate: no scan errors, stable tool names
```

The six rules behind the layout:

1. **Text in constant classes, one per bounded context** (`OrderAiText`, `CustomerAiText`). Product owners and
   prompt reviewers edit one file; `git blame` on it is the history of what the model was told.
2. **Shared vocabulary in one class** (`AiVocabulary`). The same term ("order", "purchase") is spelled the same
   way on the entity, the service and the actions, which is what tool search relies on.
3. **AI contracts as interfaces** (`OrderAiTools`). The service implementation stays free of AI text; the
   interface *is* the reviewed surface. It also makes JDK-proxied beans work (§6.6).
4. **Cross-cutting attributes annotated once** in a `@MappedSuperclass` (`TenantScopedEntity`).
5. **Scope attribute names and row limits in one class** (`AiScopes`), so renaming `tenantId` is one change and
   the limits of all entities can be compared side by side.
6. **A contract test** (§12) that fails the build on scan errors and on unintended tool-name changes.

### What cannot be centralized (scanner facts)

| Limitation | Consequence |
|---|---|
| The entity scanner reads `@AiContext` and `@AiQueryConstraints` **directly from the entity class** (`Class.getAnnotation`); neither is `@Inherited` | Put both on every concrete entity. A `@MappedSuperclass` cannot carry them for its subclasses. Composed/meta-annotations are not recognised on entities |
| The entity scanner reads `@AiEntityProperty` from **fields** (walking up superclasses), not getters | Annotate entity fields. Getter annotations only matter for DTOs/records (schema mapper) |
| Enum-valued attributes (`classification`) must be written as an enum constant | You cannot alias `Classification.CONFIDENTIAL` through a constant; write it at the use site |
| Array-valued attributes cannot reference a `static final String[]` | Write `keywords = {AiVocabulary.ORDER, AiVocabulary.PURCHASE}` — each element a constant |
| Tool `name` is taken from code only (LLD-03 §4.1, L0-only) | Keep tool names as constants (§3.2) and pin them in the contract test |

---

## 3. Shared building blocks

### 3.1 Vocabulary

```java
// src/main/java/com/acme/support/ai/AiVocabulary.java
package com.acme.support.ai;

/**
 * Domain terms shared by entities, services and actions. Keywords feed tool search and help the model map a
 * user's words to the right tool, so each concept is spelled exactly once here.
 * Limits enforced by the scanner: at most 32 keywords per element, 64 characters each.
 */
public final class AiVocabulary {

    public static final String CUSTOMER = "customer";
    public static final String CLIENT = "client";
    public static final String ACCOUNT = "account";

    public static final String ORDER = "order";
    public static final String PURCHASE = "purchase";
    public static final String SALES_ORDER = "sales order";
    public static final String SHIPMENT = "shipment";
    public static final String DELIVERY = "delivery";
    public static final String CANCELLATION = "cancellation";
    public static final String REFUND = "refund";

    public static final String PAYMENT = "payment";
    public static final String COUNTRY = "country";

    private AiVocabulary() {
    }
}
```

### 3.2 Descriptions, meanings, intents and tool names — one class per bounded context

```java
// src/main/java/com/acme/support/ai/OrderAiText.java
package com.acme.support.ai;

/**
 * Everything the model is told about orders. Limits enforced at startup (the element is excluded otherwise):
 * descriptions and intents ≤ 1024 chars, attribute meanings ≤ 256 chars, no secrets, not blank.
 */
public final class OrderAiText {

    /** Entity and service descriptions. */
    public static final class Context {
        public static final String ORDER_ENTITY =
                "A customer's purchase order: what was bought, how much it cost and where it is in its lifecycle "
                        + "(PLACED → PAID → SHIPPED → DELIVERED, or CANCELLED before shipping).";
        public static final String ORDER_TOOLS =
                "Look up customer orders and prepare order changes. Changes are never applied directly: they are "
                        + "proposed and must be confirmed by the user.";
        public static final String ORDER_CONTROLLER =
                "HTTP API used by the web shop to show a customer their own orders.";
        private Context() {
        }
    }

    /** Attribute meanings (≤ 256 chars each). */
    public static final class Attr {
        public static final String ID = "Order id (UUID); pass it to the order change tools.";
        public static final String ORDER_NUMBER = "Human-readable order number printed on invoices, e.g. ACME-2026-000123.";
        public static final String CUSTOMER = "The customer who placed the order.";
        public static final String STATUS = "Lifecycle status: PLACED, PAID, SHIPPED, DELIVERED or CANCELLED.";
        public static final String TOTAL_MINOR = "Order total in minor currency units (cents), tax included.";
        public static final String CURRENCY = "ISO 4217 currency code of the order total, e.g. EUR.";
        public static final String DELIVERY_NOTES = "Free-text delivery instructions written by the customer.";
        public static final String PLACED_AT = "When the customer placed the order (UTC).";
        private Attr() {
        }
    }

    /** Stable tool names — evaluations, audit, tool bindings and MCP clients key on these. */
    public static final class Tool {
        public static final String FIND_RECENT_ORDERS = "find_recent_orders";
        public static final String GET_ORDER = "get_order";
        public static final String UPDATE_DELIVERY_NOTES = "update_delivery_notes";
        public static final String CANCEL_ORDER = "cancel_order";
        private Tool() {
        }
    }

    /** Action intents: what the action accomplishes and when (not) to use it. */
    public static final class Intent {
        public static final String FIND_RECENT_ORDERS =
                "List a customer's most recent orders, newest first, optionally only those in one status. "
                        + "Use this to answer 'where is my order' or 'what did I buy' questions.";
        public static final String GET_ORDER =
                "Get one order by its order number, including its status and total.";
        public static final String UPDATE_DELIVERY_NOTES =
                "Propose new delivery instructions for an order that has not shipped yet. "
                        + "The user reviews and confirms the change before it is applied.";
        public static final String CANCEL_ORDER =
                "Propose cancelling an order that has not shipped yet. Do not use for refunds of delivered orders. "
                        + "The user reviews and confirms the cancellation before it is applied.";
        private Intent() {
        }
    }

    /** Parameter descriptions: meaning + valid values. */
    public static final class Param {
        public static final String CUSTOMER_ID = "Id (UUID) of the customer whose orders to list.";
        public static final String STATUS_FILTER =
                "Only return orders in this status (PLACED, PAID, SHIPPED, DELIVERED, CANCELLED). Omit for all.";
        public static final String LIMIT = "Maximum number of orders to return, 1 to 20.";
        public static final String ORDER_NUMBER = "Order number as printed on the invoice, e.g. ACME-2026-000123.";
        public static final String ORDER_ID = "Id (UUID) of the order to change, as returned by the order lookup tools.";
        public static final String NEW_NOTES = "The new delivery instructions, at most 500 characters.";
        public static final String CANCEL_REASON = "Why the customer wants to cancel, in their own words.";
        private Param() {
        }
    }

    private OrderAiText() {
    }
}
```

```java
// src/main/java/com/acme/support/ai/CustomerAiText.java
package com.acme.support.ai;

/** Everything the model is told about customers, payment cards and countries. */
public final class CustomerAiText {

    public static final class Context {
        public static final String CUSTOMER_ENTITY =
                "A person or company that buys from us. One customer belongs to exactly one tenant (shop).";
        public static final String PAYMENT_CARD_ENTITY =
                "A payment card saved by a customer. Only the brand and expiry are ever shown.";
        public static final String COUNTRY_ENTITY = "Reference list of countries we ship to.";
        public static final String CUSTOMER_TOOLS = "Look up customers by e-mail or id to identify who is asking.";
        public static final String CUSTOMER_LOOKUP_METHOD =
                "Customer lookups read personal data; results are masked according to the caller's clearance.";
        private Context() {
        }
    }

    public static final class Attr {
        public static final String DISPLAY_NAME = "Name the customer wants to be addressed by.";
        public static final String EMAIL = "Customer's e-mail address; personal data.";
        public static final String SEGMENT = "Commercial segment: RETAIL or B2B. B2B customers get invoice payment.";
        public static final String COUNTRY = "Country the customer lives in (ISO 3166-1 alpha-2).";
        public static final String PASSWORD_HASH = "Password hash used for login.";
        public static final String CARD_BRAND = "Card brand, e.g. VISA or MASTERCARD.";
        public static final String CARD_EXPIRY = "Card expiry month (YYYY-MM).";
        public static final String CARD_NUMBER = "Full primary account number of the card.";
        public static final String COUNTRY_CODE = "ISO 3166-1 alpha-2 country code, e.g. DE.";
        public static final String COUNTRY_NAME = "English country name.";
        public static final String TENANT = "The shop (tenant) this record belongs to.";
        public static final String CREATED_AT = "When the record was created (UTC).";
        private Attr() {
        }
    }

    public static final class Tool {
        public static final String FIND_CUSTOMER_BY_EMAIL = "find_customer_by_email";
        private Tool() {
        }
    }

    public static final class Intent {
        public static final String FIND_CUSTOMER_BY_EMAIL =
                "Find the customer record for an e-mail address. Returns nothing if there is no such customer.";
        private Intent() {
        }
    }

    public static final class Param {
        public static final String EMAIL = "The customer's e-mail address, exactly as registered.";
        private Param() {
        }
    }

    private CustomerAiText() {
    }
}
```

### 3.3 Scopes and limits — one class for every entity

```java
// src/main/java/com/acme/support/ai/AiScopes.java
package com.acme.support.ai;

/**
 * Data-scope floors applied to every dynamic/AI query (@AiQueryConstraints). Kept together so the limits of all
 * entities can be reviewed side by side, and so a renamed scope attribute is one change.
 * The effective row cap is always min(this, the query definition, policy layers, global cap 500).
 */
public final class AiScopes {

    /** Attribute every tenant-owned entity must be filtered by, with a server-bound value. */
    public static final String TENANT_ID = "tenantId";

    /** Attribute that scopes customer-owned records. */
    public static final String CUSTOMER = "customer";

    public static final int MAX_ROWS_TRANSACTIONAL = 20;   // orders, payments: small pages, many columns
    public static final int MAX_ROWS_PERSONAL_DATA = 10;   // customers: keep PII exposure small
    public static final int MAX_ROWS_RESTRICTED = 5;       // payment cards
    public static final int MAX_ROWS_REFERENCE = 250;      // reference data (countries)

    private AiScopes() {
    }
}
```

---

## 4. `@AiContext`

**Purpose.** Plain-English meaning of an entity, service, controller or method for the LLM, plus domain keywords
and the data classification that drives access control, masking and model-provider routing.

| Attribute | Default | Rules |
|---|---|---|
| `description` | — (mandatory) | Not blank, ≤ 1024 chars, no secret-looking values — otherwise the element is **excluded** (`MISSING_DESCRIPTION`, `DESCRIPTION_TOO_LONG`, `SECRET_IN_DESCRIPTION`) |
| `keywords` | `{}` | ≤ 32 entries, ≤ 64 chars each |
| `name` | simple class/method name | Stable logical name (display name of an entity) |
| `classification` | `INTERNAL` | `INHERIT` is invalid on types (`INVALID_CLASSIFICATION`, treated as `INTERNAL`) |

### Scenario 4.1 — Make an entity visible (and define its logical name)

```java
// src/main/java/com/acme/support/domain/Order.java
package com.acme.support.domain;

import com.acme.support.ai.AiScopes;
import com.acme.support.ai.AiVocabulary;
import com.acme.support.ai.OrderAiText;
import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import com.springaimcpservercommon.annotations.AiQueryConstraints;
import com.springaimcpservercommon.annotations.Classification;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
@AiContext(
        name = "Order",
        description = OrderAiText.Context.ORDER_ENTITY,
        keywords = {AiVocabulary.ORDER, AiVocabulary.PURCHASE, AiVocabulary.SALES_ORDER},
        classification = Classification.INTERNAL)
@AiQueryConstraints(
        maxLimit = AiScopes.MAX_ROWS_TRANSACTIONAL,
        mandatoryFilters = {AiScopes.TENANT_ID})
public class Order extends TenantScopedEntity {

    @Id
    @AiEntityProperty(meaning = OrderAiText.Attr.ID)
    private UUID id;                       // exposed because write tools take it (entityIdArgument, §6.4)

    @Version
    private long version;                  // used by reviewed writes to detect concurrent changes

    @AiEntityProperty(meaning = OrderAiText.Attr.ORDER_NUMBER)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @AiEntityProperty(meaning = OrderAiText.Attr.CUSTOMER)
    private Customer customer;             // relation to another @AiContext entity → relation path in the catalog

    @Enumerated(EnumType.STRING)
    @AiEntityProperty(meaning = OrderAiText.Attr.STATUS)
    private OrderStatus status;

    @AiEntityProperty(meaning = OrderAiText.Attr.TOTAL_MINOR)
    private long totalMinor;

    @AiEntityProperty(meaning = OrderAiText.Attr.CURRENCY)
    private String currency;

    @AiEntityProperty(meaning = OrderAiText.Attr.DELIVERY_NOTES, writable = true)
    private String deliveryNotes;          // may appear as an editable field in a reviewed proposal

    @AiEntityProperty(meaning = OrderAiText.Attr.PLACED_AT)
    private Instant placedAt;

    private int internalRiskScore;         // deliberately NOT annotated: invisible to AI and the query engine

    protected Order() {
    }

    public UUID getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public Customer getCustomer() { return customer; }
    public OrderStatus getStatus() { return status; }
    public long getTotalMinor() { return totalMinor; }
    public String getCurrency() { return currency; }
    public String getDeliveryNotes() { return deliveryNotes; }
    public Instant getPlacedAt() { return placedAt; }

    public void changeDeliveryNotes(String notes) {
        if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED) {
            throw new IllegalStateException("order already shipped");
        }
        this.deliveryNotes = notes;
    }

    public void cancel(String reason) {
        if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED) {
            throw new IllegalStateException("order already shipped");
        }
        this.status = OrderStatus.CANCELLED;
    }
}
```

```java
// src/main/java/com/acme/support/domain/OrderStatus.java
package com.acme.support.domain;

public enum OrderStatus { PLACED, PAID, SHIPPED, DELIVERED, CANCELLED }
```

Notes:
- Expose a technical id only when the model must pass it back — here the write tools identify the order by its
  id, which the tool binding names as `entityIdArgument` (§6.4). Otherwise prefer a business key such as
  `orderNumber`.
- Relations are only followed when the **target** entity also carries `@AiContext`.

### Scenario 4.2 — Give a service its context, on the AI contract interface

The service-level `@AiContext` is found through the type hierarchy, so it can sit on the interface that holds the
actions (§6). The implementation class carries no AI text at all.

```java
// src/main/java/com/acme/support/ai/tools/CustomerAiTools.java
package com.acme.support.ai.tools;

import com.acme.support.ai.AiVocabulary;
import com.acme.support.ai.CustomerAiText;
import com.acme.support.api.CustomerCard;
import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiExposedAction;
import com.springaimcpservercommon.annotations.AiParam;
import com.springaimcpservercommon.annotations.Classification;
import java.util.Optional;

@AiContext(
        description = CustomerAiText.Context.CUSTOMER_TOOLS,
        keywords = {AiVocabulary.CUSTOMER, AiVocabulary.CLIENT, AiVocabulary.ACCOUNT})
public interface CustomerAiTools {

    @AiExposedAction(
            name = CustomerAiText.Tool.FIND_CUSTOMER_BY_EMAIL,
            intent = CustomerAiText.Intent.FIND_CUSTOMER_BY_EMAIL,
            idempotent = true)
    @AiContext(                                                   // Scenario 4.3: method-level context
            description = CustomerAiText.Context.CUSTOMER_LOOKUP_METHOD,
            classification = Classification.CONFIDENTIAL)
    Optional<CustomerCard> findCustomerByEmail(
            @AiParam(description = CustomerAiText.Param.EMAIL, sensitive = true) String email);
}
```

```java
// src/main/java/com/acme/support/service/CustomerService.java
package com.acme.support.service;

import com.acme.support.ai.tools.CustomerAiTools;
import com.acme.support.api.CustomerCard;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CustomerService implements CustomerAiTools {

    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    @PreAuthorize("hasAuthority('customers:read')")   // host security still applies: tools run as the caller
    public Optional<CustomerCard> findCustomerByEmail(String email) {
        return customers.findByEmail(email).map(CustomerCard::of);
    }
}
```

### Scenario 4.3 — Method-level `@AiContext`: extra keywords and a *stricter* classification

On a method that also carries `@AiExposedAction` (see `findCustomerByEmail` above), `@AiContext`:

- **adds** its keywords to the action's keywords (duplicates removed);
- **raises** the action's classification to the more sensitive of the service's and the method's
  (`Classification.max`). It can never lower it: a method-level `PUBLIC` on a `CONFIDENTIAL` service stays
  `CONFIDENTIAL`.

Use it when one action on an otherwise `INTERNAL` service reads personal or regulated data.

### Scenario 4.4 — Controllers: context only, never tools

```java
// src/main/java/com/acme/support/web/OrderController.java
package com.acme.support.web;

import com.acme.support.ai.OrderAiText;
import com.acme.support.api.OrderSummary;
import com.acme.support.service.OrderService;
import com.springaimcpservercommon.annotations.AiContext;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AiContext(description = OrderAiText.Context.ORDER_CONTROLLER)
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/customers/{customerId}/orders")
    public List<OrderSummary> recent(@PathVariable UUID customerId) {
        return orders.findRecentOrders(customerId, null, 20);   // the TOOL is the service method, not this
    }
}
```

`@AiExposedAction` on a controller method is ignored with `CONTROLLER_ACTION_IGNORED`: controllers take
HTTP-bound arguments and sit outside the service boundary. Expose the service method the controller calls.

### Anti-patterns

| Don't | Why | Do |
|---|---|---|
| `description = "Order entity"` | Tells the model nothing it can't read from the name | Say what it is *in the business*, its lifecycle and when it matters |
| Put a system prompt, policy text or an example credential in a description | Descriptions go into prompts; secret-looking text excludes the element | Keep descriptions factual; agent instructions belong in the agent definition |
| `@AiContext` on a DTO record returned by a tool | The scanner treats an `@AiContext` return type as the action's *record type*; a DTO is not an entity | Describe DTO members with `@AiEntityProperty`; describe the action with `intent` |
| Rely on `@AiContext` of a `@MappedSuperclass` | Not inherited; the entity is not visible | Annotate every concrete entity |

---

## 5. `@AiEntityProperty`

**Purpose.** Expose one attribute of an `@AiContext` entity (or a DTO/record member) and explain what it means.
Unannotated attributes of an annotated entity stay hidden.

| Attribute | Default | Rules |
|---|---|---|
| `meaning` | — (mandatory) | Not blank, ≤ 256 chars, no secrets |
| `sensitive` | `false` | `true` ⇒ never sent to the LLM, never shown, removed from schemas, masked everywhere |
| `writable` | `false` | `true` ⇒ may appear as an editable field in a user-reviewed proposal (LLD-11). It never lets a model write |
| `classification` | `INHERIT` | Inherits the entity's; set it to make one attribute stricter |

### Scenario 5.1 — Cross-cutting attributes annotated once (`@MappedSuperclass`)

The entity scanner looks for the attribute's field up the superclass chain, so annotating shared fields once is
safe and keeps `tenantId` described identically everywhere.

```java
// src/main/java/com/acme/support/domain/TenantScopedEntity.java
package com.acme.support.domain;

import com.acme.support.ai.CustomerAiText;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class of every tenant-owned entity. Its fields are annotated once here. Note: @AiContext and
 * @AiQueryConstraints are NOT inherited — each concrete entity declares its own.
 */
@MappedSuperclass
public abstract class TenantScopedEntity {

    @Column(nullable = false, updatable = false)
    @AiEntityProperty(meaning = CustomerAiText.Attr.TENANT)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    @AiEntityProperty(meaning = CustomerAiText.Attr.CREATED_AT)
    private Instant createdAt;

    public UUID getTenantId() { return tenantId; }
    public Instant getCreatedAt() { return createdAt; }
}
```

### Scenario 5.2 — Personal data: visible but stricter, sensitive values never shown

```java
// src/main/java/com/acme/support/domain/Customer.java
package com.acme.support.domain;

import com.acme.support.ai.AiScopes;
import com.acme.support.ai.AiVocabulary;
import com.acme.support.ai.CustomerAiText;
import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import com.springaimcpservercommon.annotations.AiQueryConstraints;
import com.springaimcpservercommon.annotations.Classification;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

@Entity
@AiContext(
        description = CustomerAiText.Context.CUSTOMER_ENTITY,
        keywords = {AiVocabulary.CUSTOMER, AiVocabulary.CLIENT, AiVocabulary.ACCOUNT},
        classification = Classification.INTERNAL)
@AiQueryConstraints(maxLimit = AiScopes.MAX_ROWS_PERSONAL_DATA, mandatoryFilters = {AiScopes.TENANT_ID})
public class Customer extends TenantScopedEntity {

    @Id
    @AiEntityProperty(meaning = "Customer id (UUID); pass it to order tools.")
    private UUID id;                                   // exposed: order tools need it

    @AiEntityProperty(meaning = CustomerAiText.Attr.DISPLAY_NAME)
    private String displayName;

    @AiEntityProperty(meaning = CustomerAiText.Attr.EMAIL, classification = Classification.CONFIDENTIAL)
    private String email;                              // one attribute stricter than its INTERNAL entity

    @AiEntityProperty(meaning = CustomerAiText.Attr.SEGMENT)
    private String segment;

    @AiEntityProperty(meaning = CustomerAiText.Attr.COUNTRY)
    private String country;

    @AiEntityProperty(meaning = CustomerAiText.Attr.PASSWORD_HASH, sensitive = true)
    private String passwordHash;                       // documented for reviewers, never reaches a model

    protected Customer() {
    }

    public UUID getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public String getSegment() { return segment; }
    public String getCountry() { return country; }
}
```

Why annotate `passwordHash` with `sensitive = true` instead of leaving it unannotated? Both keep it hidden; the
annotation documents the decision in the code under review and survives someone later flipping the
`expose-unannotated-attributes` default (OQ-20).

### Scenario 5.3 — A RESTRICTED entity where only harmless attributes are visible

```java
// src/main/java/com/acme/support/domain/PaymentCard.java
package com.acme.support.domain;

import com.acme.support.ai.AiScopes;
import com.acme.support.ai.AiVocabulary;
import com.acme.support.ai.CustomerAiText;
import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import com.springaimcpservercommon.annotations.AiQueryConstraints;
import com.springaimcpservercommon.annotations.Classification;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.util.UUID;

@Entity
@AiContext(
        description = CustomerAiText.Context.PAYMENT_CARD_ENTITY,
        keywords = {AiVocabulary.PAYMENT},
        classification = Classification.RESTRICTED)   // only approved providers, four-eyes for writes
@AiQueryConstraints(
        maxLimit = AiScopes.MAX_ROWS_RESTRICTED,
        mandatoryFilters = {AiScopes.TENANT_ID, AiScopes.CUSTOMER})   // union: both must be server-bound
public class PaymentCard extends TenantScopedEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @AiEntityProperty(meaning = "The customer who owns the card.")
    private Customer customer;

    @AiEntityProperty(meaning = CustomerAiText.Attr.CARD_BRAND)
    private String brand;

    @AiEntityProperty(meaning = CustomerAiText.Attr.CARD_EXPIRY)
    private String expiry;

    @AiEntityProperty(meaning = CustomerAiText.Attr.CARD_NUMBER, sensitive = true)
    private String cardNumber;                         // sensitive name AND sensitive=true: never exposed

    protected PaymentCard() {
    }
}
```

### Scenario 5.4 — DTO records returned by read tools (what the model actually sees)

A tool's **return schema is derived from its Java return type**: every record component (or public getter/field
of a POJO) is included, minus members that are `sensitive = true`, `@JsonIgnore`d, `@Transient`/`transient`,
or whose name trips the sensitive-name heuristic. Unannotated members are **not** hidden here — the entity-level
opt-in (§5 intro) applies to the catalog and the query engine, not to arbitrary return types.

So: **read tools return purpose-built records**, not entities. The record is the exact contract with the model.

```java
// src/main/java/com/acme/support/api/OrderSummary.java
package com.acme.support.api;

import com.acme.support.ai.OrderAiText;
import com.acme.support.domain.Order;
import com.acme.support.domain.OrderStatus;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import java.time.Instant;
import java.util.UUID;

public record OrderSummary(
        @AiEntityProperty(meaning = OrderAiText.Attr.ID) UUID orderId,
        @AiEntityProperty(meaning = OrderAiText.Attr.ORDER_NUMBER) String orderNumber,
        @AiEntityProperty(meaning = OrderAiText.Attr.STATUS) OrderStatus status,
        @AiEntityProperty(meaning = OrderAiText.Attr.TOTAL_MINOR) long totalMinor,
        @AiEntityProperty(meaning = OrderAiText.Attr.CURRENCY) String currency,
        @AiEntityProperty(meaning = OrderAiText.Attr.PLACED_AT) Instant placedAt) {

    public static OrderSummary of(Order o) {
        return new OrderSummary(o.getId(), o.getOrderNumber(), o.getStatus(), o.getTotalMinor(), o.getCurrency(),
                o.getPlacedAt());
    }
}
```

```java
// src/main/java/com/acme/support/api/CustomerCard.java
package com.acme.support.api;

import com.acme.support.ai.CustomerAiText;
import com.acme.support.domain.Customer;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import java.util.UUID;

public record CustomerCard(
        @AiEntityProperty(meaning = "Customer id (UUID); pass it to order tools.") UUID id,
        @AiEntityProperty(meaning = CustomerAiText.Attr.DISPLAY_NAME) String displayName,
        @AiEntityProperty(meaning = CustomerAiText.Attr.SEGMENT) String segment) {

    public static CustomerCard of(Customer c) {
        return new CustomerCard(c.getId(), c.getDisplayName(), c.getSegment());
    }
}
```

### Scenario 5.5 — The sensitive-name heuristic

Member names are split into camelCase/snake_case tokens and checked against `password, secret, token, apiKey,
credential, ssn, iban, cardNumber, cvv, pin`. A match that is not `sensitive = true` is **removed** from the schema
and reported as `SENSITIVE_NAME_UNCONFIRMED` — it guards against a copy-pasted `sensitive = false`.

| Member | Tokens | Result |
|---|---|---|
| `cardNumber` with `sensitive = true` | card, number | removed (sensitive) — correct |
| `pinCode` | pin, code | removed + warning |
| `tokenCount` (an LLM usage counter, harmless) | token, count | removed + warning |
| `shippingAddress` | shipping, address | kept |

For a harmless name that trips the heuristic, **rename the member** (`usedTokens` → still matches; `llmUsage`
does not). Confirming names through `dynamic.ai.agent.scan.confirm-sensitive-names` is designed (LLD-02 §4) but
**Not yet** bindable (OQ-54).

### Anti-patterns

| Don't | Why | Do |
|---|---|---|
| Annotate an entity **getter** | The entity scanner reads fields only; the attribute stays hidden | Annotate the field |
| `writable = true` "just in case" | Every writable attribute widens what a proposal can change | Mark only what a reviewed AI proposal should edit |
| Rely on `writable = true` to let the model change data | It never does; writes are proposals the user confirms | See §6.4 |
| Return the entity from a read tool | All public getters (including `getInternalRiskScore()`) go into the schema | Return a record (Scenario 5.4) |
| Put units/format only in your head | The model guesses | State units, format and valid values in `meaning` ("minor units (cents)", "ISO 4217") |

---

## 6. `@AiExposedAction`

**Purpose.** Expose a public method of a Spring bean as a tool candidate for agents and MCP clients.

| Attribute | Default | Rules |
|---|---|---|
| `intent` | — (mandatory) | Not blank, ≤ 1024 chars, no secrets |
| `readOnly` | `true` | `false` ⇒ proposal-only tool: the model can only *propose*; the user confirms |
| `name` | `snake_case(methodName)` | `^[a-z][a-z0-9_]{2,63}$`, unique across the scan (`INVALID_TOOL_NAME`, `DUPLICATE_TOOL_NAME` → excluded) |
| `idempotent` | `false` | `true` enables result caching; only for pure lookups |
| `keywords` | `{}` | Merged with method-level `@AiContext.keywords` |

An annotated method becomes a **candidate** only. It is callable once an admin publishes a `TOOL_BINDING` for it
and grants `tool:invoke` (§10).

### Scenario 6.1 — The AI contract interface (recommended pattern)

```java
// src/main/java/com/acme/support/ai/tools/OrderAiTools.java
package com.acme.support.ai.tools;

import com.acme.support.ai.AiVocabulary;
import com.acme.support.ai.OrderAiText;
import com.acme.support.api.OrderSummary;
import com.acme.support.domain.Order;
import com.acme.support.domain.OrderStatus;
import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiExposedAction;
import com.springaimcpservercommon.annotations.AiParam;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The complete AI surface of the order context. Reviewed as one file; the implementation carries no AI text. */
@AiContext(
        description = OrderAiText.Context.ORDER_TOOLS,
        keywords = {AiVocabulary.ORDER, AiVocabulary.PURCHASE, AiVocabulary.SHIPMENT, AiVocabulary.DELIVERY})
public interface OrderAiTools {

    // Scenario 6.2 — bounded list read
    @AiExposedAction(
            name = OrderAiText.Tool.FIND_RECENT_ORDERS,
            intent = OrderAiText.Intent.FIND_RECENT_ORDERS,
            idempotent = true)
    List<OrderSummary> findRecentOrders(
            @AiParam(description = OrderAiText.Param.CUSTOMER_ID) UUID customerId,
            @AiParam(description = OrderAiText.Param.STATUS_FILTER, required = false) OrderStatus status,
            @AiParam(description = OrderAiText.Param.LIMIT) int limit);

    // Scenario 6.3 — single-record read
    @AiExposedAction(
            name = OrderAiText.Tool.GET_ORDER,
            intent = OrderAiText.Intent.GET_ORDER,
            idempotent = true)
    Optional<OrderSummary> getOrder(@AiParam(description = OrderAiText.Param.ORDER_NUMBER) String orderNumber);

    // Scenario 6.4 — write ⇒ reviewed proposal; returns the entity so the proposal knows its record type
    @AiExposedAction(
            name = OrderAiText.Tool.UPDATE_DELIVERY_NOTES,
            intent = OrderAiText.Intent.UPDATE_DELIVERY_NOTES,
            readOnly = false,
            keywords = {AiVocabulary.DELIVERY})
    Order updateDeliveryNotes(
            @AiParam(description = OrderAiText.Param.ORDER_ID) UUID orderId,
            @AiParam(description = OrderAiText.Param.NEW_NOTES) String notes);

    @AiExposedAction(
            name = OrderAiText.Tool.CANCEL_ORDER,
            intent = OrderAiText.Intent.CANCEL_ORDER,
            readOnly = false,
            keywords = {AiVocabulary.CANCELLATION})
    Order cancelOrder(
            @AiParam(description = OrderAiText.Param.ORDER_ID) UUID orderId,
            @AiParam(description = OrderAiText.Param.CANCEL_REASON) String reason);
}
```

```java
// src/main/java/com/acme/support/service/OrderService.java
package com.acme.support.service;

import com.acme.support.ai.AiScopes;
import com.acme.support.ai.tools.OrderAiTools;
import com.acme.support.api.OrderSummary;
import com.acme.support.domain.Order;
import com.acme.support.domain.OrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService implements OrderAiTools {

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('orders:read')")
    public List<OrderSummary> findRecentOrders(UUID customerId, OrderStatus status, int limit) {
        int capped = Math.clamp(limit, 1, AiScopes.MAX_ROWS_TRANSACTIONAL);   // never trust the model's number
        return orders.findRecent(customerId, status, capped).stream().map(OrderSummary::of).toList();
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('orders:read')")
    public Optional<OrderSummary> getOrder(String orderNumber) {
        return orders.findByOrderNumber(orderNumber).map(OrderSummary::of);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('orders:write')")
    public Order updateDeliveryNotes(UUID orderId, String notes) {
        Order order = orders.findById(orderId).orElseThrow();
        order.changeDeliveryNotes(notes);
        return order;
    }

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('orders:write')")
    public Order cancelOrder(UUID orderId, String reason) {
        Order order = orders.findById(orderId).orElseThrow();
        order.cancel(reason);
        return order;
    }
}
```

Why this is the best default:
- `@AiExposedAction`, `@AiContext` and `@AiParam` are all read through the type hierarchy, so the interface is a
  complete, reviewable AI contract; the implementation is ordinary code.
- With **JDK dynamic proxies** only interface methods are invocable through the proxy; an annotated method that is
  not on an interface is reported `NOT_A_SPRING_BEAN`. The interface pattern works for both JDK and CGLIB proxies.
- **One implementation per contract interface in the scan scope.** Two beans implementing `OrderAiTools` produce
  the same tool names → `DUPLICATE_TOOL_NAME`, both excluded (no silent winner).
- The operation reference (`op:<class>#<method>(<param types>)`) names the bean's class; read it from
  `GET /dynamic-ai/admin/api/v1/catalog/operations` rather than writing it by hand.

### Scenario 6.2 — Bounded list reads

A list-returning action needs a bound, or the scanner reports `UNBOUNDED_LIST_ACTION` (and **excludes** it with
`scan.strict=true`). Accepted bounds: a Spring Data `Pageable` or `Limit` parameter, a `Page`/`Slice`/`Window`
return type, or an `@AiParam`-annotated `int`/`long`/`short` parameter named `limit`, `maxResults`, `pageSize`,
`max` or `top`. The scanner only checks that the bound exists; **cap it in the method** as `findRecentOrders`
does — truncating after the query does not prevent the heap allocation.

### Scenario 6.3 — Idempotent single-record reads

`getOrder` is `idempotent = true`: same arguments, same answer, no side effect — safe to cache. Do **not** mark
an action idempotent if it records a "last viewed" timestamp, increments a counter, or reads data that changes
within a conversation turn where staleness matters.

### Scenario 6.4 — Writes become reviewed proposals

`readOnly = false` never lets the model execute the method. A model or MCP call:
1. is intercepted before the method runs;
2. creates a change proposal (requires `dynamic.ai.agent.write.enabled=true` and the caller's
   `data:write-propose` grant; otherwise the tool answers `writes_disabled`);
3. returns `{"status":"proposed","proposalId":"…"}` to the model;
4. runs the method **only after the user confirms** it, through the Spring proxy, as that user (your
   `@PreAuthorize`, `@Transactional`, `@Version` and auditing all apply).

**Return the entity** (or a collection of it). The proposal links the operation to its record type through the
`@AiContext` type the method returns; a `void` or DTO-returning write is refused with `operation_without_entity`.
The return value is not shown to the model at proposal time.

Identify the record by its **primary key** and name that argument in the tool binding (`entityIdArgument`
must hold the record's id, not a business key). With `@Version` on the entity (or, without one, a hash of its
exposed non-sensitive attributes), a confirmed proposal is rejected as a `CONFLICT` when the record changed after
the user reviewed it.

### Scenario 6.5 — Prefer outcome actions over CRUD

More than `scan.outcome-action-threshold` (default 8) actions returning one entity produce a
`CONSIDER_OUTCOME_ACTION` hint. `cancelOrder(orderId, reason)` is better than
`setStatus(orderId, "CANCELLED")` + `setCancelReason(…)` + `setCancelledAt(…)`: one round trip, invariants stay in
your domain code, and the reviewer approves an intent they understand.

### Scenario 6.6 — `readOnly = true` inside a read-write transaction

A read action on a class annotated `@Transactional` (read-write) is kept but flagged
`READ_ONLY_ACTION_IN_WRITE_TX`; the runtime write guard vetoes any write it attempts (ADR-0014). Silence the
warning the right way: `@Transactional(readOnly = true)` on the read methods, as `OrderService` does.

### Anti-patterns

| Don't | Why | Do |
|---|---|---|
| Rename a method and let the tool name follow | Evals, audit, bindings and MCP clients key on the tool name | Set `name` from a constant; the contract test pins it |
| `readOnly = true` on a method that writes "a little" (audit row, counter) | The write guard vetoes it at runtime → tool failure | Keep reads pure, or expose a separate read method |
| Generic `execute(String command, Map<String,Object> args)` | `COMPLEX_TOOL_ARGS`; the model produces malformed calls | Flat, typed parameters |
| Expose a repository or a method with `HttpServletRequest` | Tools must be service-boundary methods on beans | Expose the service method |
| `private`/`static`/package-private annotated methods | Not invocable through the proxy | Public instance methods on a Spring bean |

---

## 7. `@AiParam`

**Purpose.** Describe one parameter of an `@AiExposedAction` for the tool's JSON schema.

| Attribute | Default | Rules |
|---|---|---|
| `description` | — (mandatory) | ≤ 1024 chars, no secrets. State meaning **and valid values/format** |
| `name` | reflected name | Needed only if the host compiles without `-parameters` (else `PARAMETER_NAMES_UNAVAILABLE`, action excluded) |
| `required` | `true` | `Optional<T>` parameters, `Pageable` and `Limit` are never required |
| `sensitive` | `false` | `true` ⇒ value redacted in traces and audit |

### Scenario 7.1 — Valid values in the description

```java
@AiParam(description = "Only return orders in this status (PLACED, PAID, SHIPPED, DELIVERED, CANCELLED). Omit for all.",
         required = false) OrderStatus status
```

Enums already produce an `enum` list in the schema; repeating the values plus *when to omit* in the description
measurably reduces wrong calls.

### Scenario 7.2 — Sensitive arguments

`findCustomerByEmail(@AiParam(description = …, sensitive = true) String email)` — the e-mail is still passed to
your method, but traces and audit records carry it redacted.

### Scenario 7.3 — Arguments the model must NOT choose

Never let the model pick a tenant id, an owner id or a customer id that determines *whose* data is read. Keep the
parameter in the Java signature (your service needs it), then pin it **server-side** in the tool binding
(§10): `"argConstraints": {"customerId": {"kind": "principalAttr", "attr": "customerId"}}`. The model cannot
override a constrained argument, and a caller without the attribute is refused (`constraint_unsatisfied`). The
`@AiParam.description` should still be accurate — it documents the parameter for agents that are not constrained,
e.g. an internal support-staff agent.

### Scenario 7.4 — Hosts compiled without `-parameters`

```java
List<OrderSummary> findRecentOrders(
        @AiParam(name = "customerId", description = OrderAiText.Param.CUSTOMER_ID) UUID customerId, …);
```

Spring Boot's Maven and Gradle plugins enable `-parameters` by default; prefer fixing the build over adding
`name` everywhere.

---

## 8. `@AiQueryConstraints`

**Purpose.** Bound the data scope of **dynamic queries** (AI-originated and dynamic-endpoint) on an entity.
It does not constrain `@AiExposedAction` methods — those are scoped by your own code, `@PreAuthorize` and tool
binding `argConstraints`.

| Attribute | Default | Merge rule across layers |
|---|---|---|
| `maxLimit` | `50` (min 1) | **min** of this, the query definition, policy layers and the global cap (500) |
| `mandatoryFilters` | `{}` | **union** across layers |

A mandatory filter is satisfied only by an `EQ`/`IN` predicate whose value is **bound server-side**: a
principal-attribute operand, a row policy, or a parameter pinned by an `argConstraint`. A value supplied only by
the model does not count (it could choose another tenant). Missing at publish → rejected; missing at runtime →
query refused.

### Scenario 8.1 — Multi-tenant SaaS

Every tenant-owned entity declares `mandatoryFilters = {AiScopes.TENANT_ID}` (see `Order`, `Customer`). Map the
tenant from the caller's token so query definitions can bind it:

```yaml
dynamic.ai.agent.security:
  attribute-claims:
    tenantId: tid        # identity attribute "tenantId" ← token claim "tid"
    customerId: cust     # used by argConstraints of self-service tools
```

A query definition then filters `tenantId EQ principal.tenantId`; one that forgets it cannot be published.

### Scenario 8.2 — Restricted data: tighter rows, more filters

`PaymentCard` uses `maxLimit = AiScopes.MAX_ROWS_RESTRICTED` (5) and `mandatoryFilters = {TENANT_ID, CUSTOMER}`:
a query must be pinned to one tenant **and** one customer.

### Scenario 8.3 — Public reference data

```java
// src/main/java/com/acme/support/domain/Country.java
package com.acme.support.domain;

import com.acme.support.ai.AiScopes;
import com.acme.support.ai.AiVocabulary;
import com.acme.support.ai.CustomerAiText;
import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import com.springaimcpservercommon.annotations.AiQueryConstraints;
import com.springaimcpservercommon.annotations.Classification;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
@AiContext(
        description = CustomerAiText.Context.COUNTRY_ENTITY,
        keywords = {AiVocabulary.COUNTRY},
        classification = Classification.PUBLIC)
@AiQueryConstraints(maxLimit = AiScopes.MAX_ROWS_REFERENCE)   // no tenant: shared reference data
public class Country {

    @Id
    @AiEntityProperty(meaning = CustomerAiText.Attr.COUNTRY_CODE)
    private String code;

    @AiEntityProperty(meaning = CustomerAiText.Attr.COUNTRY_NAME)
    private String name;

    protected Country() {
    }
}
```

The effective limit is still capped by the global 500 and by each query definition's page size.

### Anti-patterns

| Don't | Why | Do |
|---|---|---|
| Omit `@AiQueryConstraints` on tenant data and "remember" to filter in each query | One forgotten query leaks across tenants | Declare the mandatory filter once on the entity |
| `maxLimit = 10000` "for reports" | Heap and prompt blow-up; the global cap wins anyway | Keep AI queries small; use your reporting stack for exports |
| Mandatory filter on an attribute that is not annotated `@AiEntityProperty` | It cannot be referenced by a query definition | Annotate the scope attribute (as `TenantScopedEntity` does) |

---

## 9. `Classification`

Ordered from least to most sensitive: `PUBLIC < INTERNAL < CONFIDENTIAL < RESTRICTED` (`INHERIT` = use the
enclosing type's, members only).

| Level | Use for | Effect (annotation Javadoc, SEC-01 §7, LLD-06 §6) |
|---|---|---|
| `PUBLIC` | Reference data, product catalog | May be shown to anyone who can call the tool |
| `INTERNAL` (default) | Ordinary business data | Internal business data |
| `CONFIDENTIAL` | Personal data (e-mail, address), commercially sensitive data | Approvals required for writes |
| `RESTRICTED` | Regulated data (payments, health, national ids) | Only on-prem/approved model providers; four-eyes approvals |

At invocation, a resource's classification is compared with the caller's clearance: above it, the call is denied
or the data masked, per policy (SEC-01 §7 step 5).

Resolution rules:
- Attribute: `INHERIT` → the entity's classification; an explicit value on an attribute can be stricter.
- Action: service `@AiContext.classification`, raised (`max`) by a method-level `@AiContext.classification`.
- Policy layers may only raise classification; lowering needs an approved dashboard overlay with
  `catalog:declassify` (LLD-03 §4.1).

Callers' clearance comes from your token: `dynamic.ai.agent.security.clearance-claim` and
`default-clearance` (default `INTERNAL`).

---

## 10. End-to-end scenario: a support-desk assistant

**Goal.** Customers chat with a support assistant in the web shop (and power users connect over MCP). It must
answer "where is my order?", and may *propose* changing delivery notes or cancelling an unshipped order — only
ever for the caller's own orders.

**Step 1 — Code** (already shown): `AiVocabulary`, `OrderAiText`, `CustomerAiText`, `AiScopes`,
`TenantScopedEntity`, `Order`, `Customer`, `PaymentCard`, `Country`, `OrderSummary`, `CustomerCard`,
`OrderAiTools` + `OrderService`, `CustomerAiTools` + `CustomerService`, `OrderController`.

**Step 2 — Environment config** (`application.yml`, §11): tier, scan strictness, `write.enabled`,
`attribute-claims` (`customerId`, `tenantId`), MCP.

**Step 3 — Verify the catalog** (DEV/TEST; catalog browsing is off in PROD):

```bash
curl -H "Authorization: Bearer $ADMIN" "$BASE/dynamic-ai/admin/api/v1/catalog/operations?q=order"
# find_recent_orders, get_order (readOnly=true); update_delivery_notes, cancel_order (readOnly=false)
```

The contract test (§12) already failed the build if any of them was excluded.

**Step 4 — Publish tool bindings** (admin API, reviewed: author submits, a *different* approver approves). The
self-service bindings pin whose data is read:

```json
{"toolName": "find_recent_orders",
 "source": {"kind": "operation", "ref": "op:com.acme.support.service.OrderService#findRecentOrders(java.util.UUID,com.acme.support.domain.OrderStatus,int)"},
 "argConstraints": {
   "customerId": {"kind": "principalAttr", "attr": "customerId"},
   "limit": {"kind": "range", "min": 1, "max": 10}},
 "mcpExposed": true}
```

```json
{"toolName": "cancel_order",
 "source": {"kind": "operation", "ref": "op:com.acme.support.service.OrderService#cancelOrder(java.util.UUID,java.lang.String)"},
 "writeMode": "PROPOSE", "change": "update", "entityIdArgument": "orderId",
 "mcpExposed": true}
```

> `cancelOrder` takes an order id, not a customer id, so ownership must be enforced in your service
> (`@PreAuthorize` with an ownership check, or a repository lookup scoped to the caller). `argConstraints` can only
> pin arguments that exist. When you design self-service write actions, prefer signatures whose scope argument can
> be pinned (`cancelOrder(customerId, orderId, reason)`).

**Step 5 — Grant** `tool:invoke` (and `data:write-propose` + `data:write-confirm` for the write tools) to the
customer group, and `agent:invoke` for the support agent.

**Step 6 — A conversation.**

| Turn | What happens |
|---|---|
| User: "Where is my last order?" | Model calls `find_recent_orders{limit:1}`; the server fills `customerId` from the token; `OrderService` runs as the user, read-only; the model receives an `OrderSummary` record — no risk score, no customer data |
| User: "Please cancel it, I ordered the wrong size." | Model calls `cancel_order{orderId, reason}` with the id from the summary → a proposal is created (remembering the order's `@Version`), model gets `status: proposed` |
| User reviews and confirms in the UI | `cancelOrder` runs through the proxy as the user; `@Version` guards against a concurrent change (`CONFLICT` if it shipped in between) |

**Step 7 — Operate.** Take a misbehaving tool away immediately with `:suspend` on its binding (allowed in every
environment); roll back a generation with `…/cluster/generations/{n}:rollback`. Neither needs a deploy.

---

## 11. Centralized environment configuration

All library behaviour lives under `dynamic.ai.agent.*` in `application.yml`, with per-environment differences in
profile files. Only keys that exist in `DaiProperties` are shown (Spring silently ignores misspelled keys).

```yaml
# src/main/resources/application.yml — shared by every environment
dynamic:
  ai:
    agent:
      environment:
        application-name: support-desk
      scan:
        base-packages: [com.acme.support]   # default: the @SpringBootApplication package; set it explicitly when
                                            # annotated code lives in several roots
        strict: false                        # overridden to true in CI/test (below)
        outcome-action-threshold: 8
      security:
        groups-claim: groups
        clearance-claim: clearance
        default-clearance: INTERNAL
        attribute-claims:
          tenantId: tid
          customerId: cust
      write:
        enabled: false                       # opt in per environment
```

```yaml
# src/main/resources/application-dev.yml
dynamic.ai.agent:
  environment:
    tier: DEV                                # never leave unset: UNKNOWN is treated as PROD
  write:
    enabled: true
  mcp:
    enabled: true
    require-approved-client: false           # trusted dev network only
```

```yaml
# src/test/resources/application-test.yml — CI
dynamic.ai.agent:
  environment:
    tier: TEST
  scan:
    strict: true                             # unbounded list actions are excluded → the contract test catches it
```

```yaml
# src/main/resources/application-prod.yml
dynamic.ai.agent:
  environment:
    tier: PROD
    id: support-desk-prod-eu
  write:
    enabled: true
  mcp:
    enabled: true
    resource-uri: https://support.acme.example/dynamic-ai/mcp
    authorization-servers: [ "https://login.acme.example" ]
```

What does **not** belong in YAML: descriptions, keywords, tool names, `sensitive`/`writable`, row limits. Those
are facts about the code and live in the constant classes next to it (§3).

---

## 12. Catalog contract test

The scanner never fails the host's startup (it excludes the broken element and records a `ScanIssue`). Make the
**build** fail instead, and pin the public tool surface so a rename is a conscious decision:

```java
// src/test/java/com/acme/support/ai/AiCatalogContractTest.java
package com.acme.support.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveOperation;
import com.springaimcpservercommon.core.catalog.MetadataRegistry;
import com.springaimcpservercommon.core.catalog.ScanIssue;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")                      // scan.strict=true
class AiCatalogContractTest {

    @Autowired
    MetadataRegistry registry;

    @Test
    void scanHasNoErrors() {
        EffectiveCatalog catalog = registry.current();
        assertThat(catalog.issues())
                .filteredOn(i -> i.severity() == ScanIssue.Severity.ERROR || i.excluded())
                .as("excluded @Ai* elements (see ScanIssueCode)")
                .isEmpty();
    }

    @Test
    void toolSurfaceIsStable() {
        Map<String, Boolean> readOnlyByTool = registry.current().operations().values().stream()
                .collect(Collectors.toMap(EffectiveOperation::toolName, EffectiveOperation::readOnly));

        assertThat(readOnlyByTool).containsExactlyInAnyOrderEntriesOf(Map.of(
                OrderAiText.Tool.FIND_RECENT_ORDERS, true,
                OrderAiText.Tool.GET_ORDER, true,
                OrderAiText.Tool.UPDATE_DELIVERY_NOTES, false,
                OrderAiText.Tool.CANCEL_ORDER, false,
                CustomerAiText.Tool.FIND_CUSTOMER_BY_EMAIL, true));
    }
}
```

A removed tool, a new tool, a renamed tool or a read flipped to a write now needs an explicit test change in the
same pull request — which is where a reviewer should see it. A canonical-JSON golden-file export
(`catalog:export`, LLD-02 §5) is designed but **Not yet** available; the registry assertion above covers the same
ground today.

---

## 13. Runtime policy overrides (roadmap)

LLD-03 §4 specifies layers on top of the annotations, merged "restrictive wins" for safety and "latest wins" for
text: **L1** classpath/file policy JSON (`dynamic.ai.agent.policy.locations`), **L2** approved dashboard overlays,
**L3** kill switches. The parser and merger exist in `core` (`PolicyDocumentParser`, `PolicyMerger`), but the
startup bootstrap currently merges the annotations only, and `policy.locations` is not a bindable property
(**Not yet**, OQ-54). Until then:

| Need | Today |
|---|---|
| Change a description / keyword | Edit the constant (§3), deploy |
| Disable one tool now | `:suspend` its tool binding, or roll back the generation |
| Restrict arguments per caller | Tool binding `argConstraints` |
| Reduce rows | Lower the constant in `AiScopes`, or the query definition's page size |

When L1 lands, the file will be the natural central place for per-environment *restrictions* (never exposure):

```json
{
  "schemaVersion": 1,
  "overrides": {
    "op:com.acme.support.service.OrderService#cancelOrder(java.util.UUID,java.lang.String)": {
      "enabled": false, "reason": "Cancellations handled by phone during the peak season."
    },
    "entity:com.acme.support.domain.Customer": { "maxLimit": 5 },
    "attr:com.acme.support.domain.Customer#email": { "sensitive": true }
  }
}
```

---

## 14. Review checklist and anti-patterns

### Pull-request checklist for `@Ai*` changes

- [ ] Every string in an `@Ai*` annotation references a constant in `com.acme.support.ai.*` (no inline literals
      except one-off, self-evident meanings).
- [ ] New entity: `@AiContext` **and** `@AiQueryConstraints` on the concrete class; tenant data has
      `mandatoryFilters = {AiScopes.TENANT_ID}`; row limit taken from `AiScopes`.
- [ ] Only attributes the model needs are annotated; secrets and credentials are `sensitive = true`; personal data
      has `classification = CONFIDENTIAL` (or stricter).
- [ ] `writable = true` only on attributes a reviewed proposal should edit.
- [ ] New action is declared on its context's AI contract interface, has a `name` constant, an `intent` that says
      when *not* to use it, and `@AiParam` descriptions with valid values.
- [ ] Read actions return records; list reads have a limit parameter that the method caps.
- [ ] Write actions are `readOnly = false`, return the entity, and the entity has `@Version`.
- [ ] Scope arguments (tenant, customer, owner) are pinned by the tool binding's `argConstraints`, not chosen by
      the model.
- [ ] The contract test was updated deliberately if the tool surface changed.

### Scan issues you will meet and their fix

| Code | Excluded? | Fix |
|---|---|---|
| `MISSING_DESCRIPTION`, `DESCRIPTION_TOO_LONG`, `SECRET_IN_DESCRIPTION` | yes | Fix the constant |
| `INVALID_TOOL_NAME`, `DUPLICATE_TOOL_NAME` | yes | Set a unique `name` matching `^[a-z][a-z0-9_]{2,63}$` |
| `PARAMETER_NAMES_UNAVAILABLE` | yes | Compile with `-parameters` or set `@AiParam(name)` |
| `AMBIGUOUS_BEAN_TYPE` | yes | One bean per annotated class in scan scope |
| `UNBOUNDED_LIST_ACTION` | with `strict` | Add a limit/`Pageable` parameter or a paged return type |
| `NOT_A_SPRING_BEAN` | yes | Declare the action on an interface (JDK proxy) or make the class a bean |
| `CONTROLLER_ACTION_IGNORED` | — | Move `@AiExposedAction` to the service |
| `SENSITIVE_NAME_UNCONFIRMED` | member removed | `sensitive = true`, or rename the member |
| `READ_ONLY_ACTION_IN_WRITE_TX` | no | `@Transactional(readOnly = true)` on the read |
| `COMPLEX_TOOL_ARGS` | no | Flatten the parameters |
| `CONSIDER_OUTCOME_ACTION` | no | Merge CRUD-style actions into outcome actions |
| `INVALID_CLASSIFICATION` | no | Don't use `INHERIT` on a type |

### Summary of anti-patterns

| Anti-pattern | Consequence | Instead |
|---|---|---|
| Inline strings scattered over entities, services and DTOs | Inconsistent wording, no single review point | Constant classes per bounded context (§3.2) |
| Annotations on the implementation *and* the interface | Two sources of truth | Interface only (§6.1) |
| Environment-specific facts in annotations | A deploy for a config change | `application.yml` / tool bindings |
| Tenant/owner ids chosen by the model | Cross-tenant data access | `argConstraints` + `mandatoryFilters` |
| Entities returned from read tools | Unintended getters reach the model | Records (§5.4) |
| `void` write actions | `operation_without_entity`, no proposal | Return the entity (§6.4) |
