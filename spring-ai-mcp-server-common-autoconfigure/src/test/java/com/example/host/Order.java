package com.example.host;

import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import com.springaimcpservercommon.annotations.AiQueryConstraints;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A host JPA entity that the host chose to make understandable to the AI. */
@Entity
@Table(name = "host_order")
@AiContext(description = "A customer order")
@AiQueryConstraints(maxLimit = 20)
public class Order {

    @Id
    private String id;

    @AiEntityProperty(meaning = "The customer who placed the order")
    private String customerId;

    @AiEntityProperty(meaning = "OPEN, CANCELLED or SHIPPED")
    private String status;

    @AiEntityProperty(meaning = "Card number used to pay", sensitive = true)
    private String cardNumber;

    protected Order() {
    }

    public Order(String id, String customerId, String status, String cardNumber) {
        this.id = id;
        this.customerId = customerId;
        this.status = status;
        this.cardNumber = cardNumber;
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCardNumber() {
        return cardNumber;
    }
}
