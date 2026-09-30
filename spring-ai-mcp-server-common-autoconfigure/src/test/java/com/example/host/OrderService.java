package com.example.host;

import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiExposedAction;
import com.springaimcpservercommon.annotations.AiParam;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A host service with one read and one write operation exposed to the AI. */
@Service
@AiContext(description = "Order management")
public class OrderService {

    /** Counts real executions of the write, so the test can prove the model never ran it. */
    static final AtomicInteger CANCELLATIONS = new AtomicInteger();

    private final EntityManager entityManager;

    OrderService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @AiExposedAction(intent = "Find a customer's orders, optionally by status")
    @Transactional(readOnly = true)
    public List<String> find(@AiParam(description = "The customer id") String customerId,
                             @AiParam(description = "Order status", required = false) String status,
                             @AiParam(description = "Maximum rows") int limit) {
        return entityManager.createQuery(
                        "select o.id from Order o where o.customerId = :c and (:s is null or o.status = :s) order by o.id",
                        String.class)
                .setParameter("c", customerId).setParameter("s", status).setMaxResults(limit).getResultList();
    }

    @AiExposedAction(intent = "Cancel an order", readOnly = false, idempotent = true)
    @Transactional
    public String cancel(@AiParam(description = "The order id") String orderId) {
        CANCELLATIONS.incrementAndGet();
        Order order = entityManager.find(Order.class, orderId);
        order.setStatus("CANCELLED");
        return orderId;
    }
}
