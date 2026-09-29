package com.springaimcpservercommon.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.assertj.core.api.Assertions.assertThat;

class AdminExceptionHandlerTest {

    private final AdminExceptionHandler handler = new AdminExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dynamic-ai/admin/api/v1/audit/denials");

    @Test
    void illegalArgumentBecomes400ProblemWithType() {
        ResponseEntity<String> response = handler.badArgument(new IllegalArgumentException("limit must be between 1 and 200: 500"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody())
                .contains("https://dynamic-ai/problems/invalid-argument")
                .contains("limit must be between 1 and 200: 500")
                .contains("/dynamic-ai/admin/api/v1/audit/denials");
    }

    @Test
    void missingParameterBecomesFieldViolation() {
        ResponseEntity<String> response = handler.missingParameter(
                new MissingServletRequestParameterException("since", "String"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("\"field\":\"since\"").contains("is required");
    }

    @Test
    void unexpectedErrorDoesNotLeakTheMessage() {
        ResponseEntity<String> response = handler.unexpected(new IllegalStateException("jdbc:postgresql://secret-host/db"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).doesNotContain("secret-host").contains("An unexpected error occurred.");
    }
}
