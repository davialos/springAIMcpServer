package com.example.crm.web;

import com.example.crm.support.ApiController;
import org.springframework.web.bind.annotation.*;

@ApiController
@RequestMapping("/api/deals")
public class DealController {

    @PostMapping
    public Object create(@RequestBody CreateDealRequest request) {
        return null;
    }

    @GetMapping("/{dealId}")
    public Object get(@PathVariable Long dealId) {
        return null;
    }

    @DeleteMapping("/{dealId}")
    public void delete(@PathVariable Long dealId) {
    }
}
