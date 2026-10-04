package com.example.crm.web;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange("/api/status")
public interface StatusApi {

    @GetExchange("/version")
    String version();
}
