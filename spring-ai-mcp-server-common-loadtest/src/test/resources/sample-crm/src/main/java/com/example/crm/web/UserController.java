package com.example.crm.web;

import com.example.crm.support.ApiController;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@ApiController
@RequestMapping("/api/users")
public class UserController {

    public record CreateUserRequest(@NotBlank String username, @NotBlank String password) {
    }

    @PostMapping
    public Object create(@RequestBody CreateUserRequest request) {
        return null;
    }
}
