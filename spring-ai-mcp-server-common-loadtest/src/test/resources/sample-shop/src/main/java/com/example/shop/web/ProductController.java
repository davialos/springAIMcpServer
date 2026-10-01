package com.example.shop.web;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ProductController implements ProductApi {

    @Override
    public List<Object> listProducts(String query) {
        return List.of();
    }

    @Override
    public Object getProduct(String sku) {
        return null;
    }
}
