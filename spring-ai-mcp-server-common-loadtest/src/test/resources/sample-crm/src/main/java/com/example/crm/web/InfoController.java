package com.example.crm.web;

import com.example.crm.support.ApiController;
import org.springframework.web.bind.annotation.GetMapping;

/** No @RequestMapping of its own: the base path comes from the composed @ApiController (/api). */
@ApiController
public class InfoController {

    @GetMapping("/info")
    public Object info() {
        return null;
    }
}
