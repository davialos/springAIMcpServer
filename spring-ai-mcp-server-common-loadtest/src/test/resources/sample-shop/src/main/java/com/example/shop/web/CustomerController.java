package com.example.shop.web;

import com.example.shop.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.CUSTOMERS)
public class CustomerController {

    @GetMapping
    @Operation(operationId = "listCustomers", summary = "Page through customers")
    public List<Object> list(Pageable pageable, @RequestParam(required = false) String email, Authentication auth) {
        return List.of();
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable Long id) {
        return null;
    }

    @PostMapping
    public ResponseEntity<Object> create(@Valid @RequestBody CreateCustomerRequest request) {
        return null;
    }

    @PutMapping("/{id}")
    public Object update(@PathVariable("id") Long customerId, @RequestBody CreateCustomerRequest request,
                         @RequestHeader(name = "X-Request-Id", required = false) String requestId) {
        return null;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
    }
}
