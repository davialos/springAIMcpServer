package com.acme.vet;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class RootController {
    @GetMapping("/")
    public void redirectToSwagger(HttpServletResponse response) { }
}
