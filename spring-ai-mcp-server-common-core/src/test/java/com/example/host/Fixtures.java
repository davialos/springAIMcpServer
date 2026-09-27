package com.example.host;

import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import com.springaimcpservercommon.annotations.AiExposedAction;
import com.springaimcpservercommon.annotations.AiParam;
import com.springaimcpservercommon.annotations.Classification;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-application fixtures for the bean scanner tests (outside the library package on purpose).
 */
public final class Fixtures {

    private Fixtures() {
    }

    @AiContext(description = "A customer order", classification = Classification.CONFIDENTIAL)
    public record Order(@AiEntityProperty(meaning = "Order number") long id,
                        @AiEntityProperty(meaning = "Order total") BigDecimal total,
                        @AiEntityProperty(meaning = "Card number", sensitive = true) String cardNumber) {
    }

    @AiContext(description = "A product in the catalog")
    public record Product(String sku, String name) {
    }

    /** Plain service: lint cases. */
    @AiContext(description = "Order management", keywords = {"orders", "purchases"})
    public static class OrderService {

        @AiExposedAction(intent = "Finds one order by its number", keywords = "lookup")
        public Optional<Order> findOrder(@AiParam(description = "Order number") Long id) {
            return Optional.empty();
        }

        @AiExposedAction(intent = "Lists the most recent orders of a customer")
        public List<Order> findRecentOrders(@AiParam(description = "Customer id") Long customerId,
                                            @AiParam(description = "Maximum number of orders") int limit) {
            return List.of();
        }

        @AiExposedAction(intent = "Lists all orders")
        public List<Order> listAllOrders() {
            return List.of();
        }

        @AiExposedAction(intent = "Cancels an order", readOnly = false)
        public void cancelOrder(@AiParam(description = "Order number") Long id,
                                @AiParam(description = "Why", sensitive = true) String reason) {
            // write action: proposal-only
        }

        @AiExposedAction(intent = "Uses password=hunter2secret to reach the backend")
        public String leakySecret() {
            return "";
        }

        @AiExposedAction(intent = "Static helpers cannot be proxied")
        public static String staticAction() {
            return "";
        }

        public String notExposed() {
            return "";
        }
    }

    /** Class-level read-write @Transactional. */
    @Transactional
    public static class ReportService {

        @AiExposedAction(intent = "Builds the daily revenue report")
        public String dailyReport() {
            return "";
        }

        @Transactional(readOnly = true)
        @AiExposedAction(intent = "Builds the weekly revenue report")
        public String weeklyReport() {
            return "";
        }
    }

    /** Annotations declared on the interface only. */
    public interface CatalogApi {
        @AiExposedAction(intent = "Describes a catalog section")
        String describeCatalog(@AiParam(description = "Section name") String section);
    }

    public static class CatalogApiImpl implements CatalogApi {
        @Override
        public String describeCatalog(String section) {
            return section;
        }
    }

    /** Proxied with CGLIB (no interfaces). */
    @AiContext(description = "Prices", classification = Classification.PUBLIC)
    public static class PricingService {
        @AiExposedAction(intent = "Computes the price of a product", idempotent = true)
        public BigDecimal priceOf(@AiParam(description = "Product SKU") String sku) {
            return BigDecimal.ONE;
        }
    }

    /** Proxied with a JDK dynamic proxy; annotations on the implementation. */
    public interface InventoryApi {
        int stockLevel(String sku);
    }

    public static class InventoryApiImpl implements InventoryApi {
        @Override
        @AiExposedAction(intent = "Returns the stock level of a product")
        public int stockLevel(@AiParam(description = "Product SKU") String sku) {
            return 0;
        }

        @AiExposedAction(intent = "Not reachable through the JDK proxy")
        public int hiddenFromProxy() {
            return 0;
        }
    }

    /** Must stay uninitialised. */
    public static class LazyService {
        public static final AtomicBoolean CREATED = new AtomicBoolean();

        public LazyService() {
            CREATED.set(true);
        }

        @AiExposedAction(intent = "A lazily created action")
        public String lazyAction() {
            return "";
        }
    }

    public static class DuplicateA {
        @AiExposedAction(intent = "Searches things (A)", name = "find_things")
        public String searchA() {
            return "";
        }
    }

    public static class DuplicateB {
        @AiExposedAction(intent = "Searches things (B)", name = "find_things")
        public String searchB() {
            return "";
        }
    }

    @Controller
    @AiContext(description = "Order web endpoints")
    public static class OrderController {
        @AiContext(description = "Shows an order page")
        public String showOrder(Long id) {
            return "";
        }

        @AiExposedAction(intent = "Controllers never become tools")
        public String controllerAction() {
            return "";
        }
    }

    public static class InfrastructureService {
        @AiExposedAction(intent = "Infrastructure beans are skipped")
        public String infraAction() {
            return "";
        }
    }

    /** More than eight actions on one entity. */
    public static class ProductService {
        @AiExposedAction(intent = "p1") public Product findProduct1() { return new Product("a", "a"); }
        @AiExposedAction(intent = "p2") public Product findProduct2() { return new Product("a", "a"); }
        @AiExposedAction(intent = "p3") public Product findProduct3() { return new Product("a", "a"); }
        @AiExposedAction(intent = "p4") public Product findProduct4() { return new Product("a", "a"); }
        @AiExposedAction(intent = "p5") public Product findProduct5() { return new Product("a", "a"); }
        @AiExposedAction(intent = "p6") public Product findProduct6() { return new Product("a", "a"); }
        @AiExposedAction(intent = "p7") public Product findProduct7() { return new Product("a", "a"); }
        @AiExposedAction(intent = "p8") public Product findProduct8() { return new Product("a", "a"); }
        @AiExposedAction(intent = "p9") public Optional<Product> findProduct9() { return Optional.empty(); }
    }
}
