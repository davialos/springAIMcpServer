package com.example.host;

import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import com.springaimcpservercommon.annotations.AiQueryConstraints;
import com.springaimcpservercommon.annotations.AiRowContext;
import com.springaimcpservercommon.annotations.Classification;
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
    @AiEntityProperty(meaning = "The order number")
    private String id;

    @AiEntityProperty(meaning = "The customer who placed the order")
    private String customerId;

    @AiEntityProperty(meaning = "OPEN, CANCELLED or SHIPPED")
    private String status;

    @AiEntityProperty(meaning = "Card number used to pay", sensitive = true)
    private String cardNumber;

    @AiEntityProperty(meaning = "Free-text remarks the customer or support left on this order")
    @AiRowContext(label = "Customer notes", maxChars = 60)
    private String notes;

    @AiEntityProperty(meaning = "Fraud team remarks", classification = Classification.RESTRICTED)
    @AiRowContext(label = "Fraud notes")
    private String fraudNotes;

    @AiEntityProperty(meaning = "Private remarks", sensitive = true)
    @AiRowContext(label = "Private notes")
    private String privateNotes;

    protected Order() {
    }

    public Order(String id, String customerId, String status, String cardNumber) {
        this.id = id;
        this.customerId = customerId;
        this.status = status;
        this.cardNumber = cardNumber;
    }

    public Order withNotes(String notes, String fraudNotes, String privateNotes) {
        this.notes = notes;
        this.fraudNotes = fraudNotes;
        this.privateNotes = privateNotes;
        return this;
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
