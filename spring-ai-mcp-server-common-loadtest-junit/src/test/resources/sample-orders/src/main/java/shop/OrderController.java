package shop;

import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
class OrderController {
    @GetMapping("/{id}") Object get(@PathVariable Long id) { return null; }
    @GetMapping Object list() { return null; }
    @PostMapping Object create(@RequestBody NewOrder o) { return null; }
}

record NewOrder(@NotBlank String product, int quantity) { }
