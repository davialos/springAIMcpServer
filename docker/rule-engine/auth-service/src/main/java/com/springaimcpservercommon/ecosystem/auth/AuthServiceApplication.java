package com.springaimcpservercommon.ecosystem.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Auth microservice of the local rule-engine ecosystem (ADR-0026). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServiceApplication {

    /**
     * Starts the service.
     *
     * @param args command line
     */
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
