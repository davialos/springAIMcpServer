package com.example.shop.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface ProductApi {

    @GetMapping("/api/v1/products")
    List<Object> listProducts(@RequestParam(value = "q", required = false) String query);

    @GetMapping("/api/v1/products/{sku}")
    Object getProduct(@PathVariable("sku") String sku);
}
