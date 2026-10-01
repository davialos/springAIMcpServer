package com.example.shop.web;

import com.example.shop.api.ApiPaths;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping(path = ApiPaths.ORDERS)
public class OrderController {

    @RequestMapping(value = "/{orderId:\\d+}", method = RequestMethod.GET)
    public Object get(@PathVariable Long orderId) {
        return null;
    }

    @GetMapping("/search")
    public List<Object> search(@ModelAttribute OrderFilter filter, @RequestParam(defaultValue = "20") int limit) {
        return List.of();
    }

    @PostMapping
    public Object create(@RequestBody CreateOrderRequest request) {
        return null;
    }

    @PostMapping("/{orderId}/attachments")
    public void upload(@PathVariable Long orderId, @RequestParam("file") MultipartFile file) {
    }
}
