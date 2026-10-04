package com.example.crm.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.web.servlet.function.RouterFunctions.route;

@Configuration
public class Routes {

    @Bean
    RouterFunction<ServerResponse> healthRoutes() {
        return route()
                .GET("/api/health/ping", request -> ServerResponse.ok().body("pong"))
                .path("/api/reports", b -> b
                        .GET("/{year}", request -> ServerResponse.ok().build())
                        .POST("/rebuild", request -> ServerResponse.accepted().build()))
                .build();
    }
}
