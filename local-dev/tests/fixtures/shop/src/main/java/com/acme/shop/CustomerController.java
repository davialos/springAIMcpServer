package com.acme.shop;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    public record CustomerRequest(@NotBlank @Size(max = 40) String firstName, @NotBlank String lastName,
                                  @Email @NotNull String email, @Pattern(regexp = "^[A-Z]{2}$") String countryCode, Tier tier) {}

    public record CustomerDto(UUID id, String firstName, String lastName, String email, String countryCode, Tier tier) {}

    public enum Tier { STANDARD, GOLD, PLATINUM }

    /** creates a customer */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerDto create(@Valid @RequestBody CustomerRequest request) { return null; }

    @GetMapping("/{id}")
    public CustomerDto get(@PathVariable UUID id) { return null; }

    @GetMapping
    public List<CustomerDto> list(@RequestParam(required = false) String country, @RequestParam(defaultValue = "20") int size) { return null; }
}
