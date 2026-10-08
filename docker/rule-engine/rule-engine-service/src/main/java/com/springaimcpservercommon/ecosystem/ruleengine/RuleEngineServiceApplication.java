package com.springaimcpservercommon.ecosystem.ruleengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Rule-engine microservice of the local ecosystem (ADR-0026): authoring, evaluation and admin logs over the CEL engine. */
@SpringBootApplication(scanBasePackages = {"com.springaimcpservercommon.ecosystem.ruleengine", "com.example.ruleconsole"})
@ConfigurationPropertiesScan
public class RuleEngineServiceApplication {

    /**
     * Starts the service.
     *
     * @param args command line
     */
    public static void main(String[] args) {
        SpringApplication.run(RuleEngineServiceApplication.class, args);
    }
}
