package com.example.crm.web;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController implements StatusApi {
    @Override
    public String version() {
        return "1";
    }
}
