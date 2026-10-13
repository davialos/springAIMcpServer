package com.acme.shop;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record OrderLine(@NotBlank String sku, @Min(1) @Max(10) int quantity) {}
    public record OrderRequest(@NotNull UUID customerId, @NotEmpty List<OrderLine> lines, @Size(max = 200) String note) {}
    public record OrderDto(UUID id, UUID customerId, String status, BigDecimal total, List<OrderLine> lines) {}
    public record PaymentRequest(@Positive BigDecimal amount, @Pattern(regexp = "^[A-Z]{3}$") String currency) {}
    public record PaymentDto(UUID id, UUID orderId, String status) {}

    @PostMapping
    public ResponseEntity<OrderDto> place(@RequestBody OrderRequest request) { return null; }

    @GetMapping("/{orderId}")
    public OrderDto get(@PathVariable("orderId") UUID orderId) { return null; }

    @PostMapping("/{orderId}/payments")
    public PaymentDto pay(@PathVariable UUID orderId, @RequestBody PaymentRequest payment) { return null; }

    @DeleteMapping("/{orderId}")
    public void cancel(@PathVariable UUID orderId) { }
}
