package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.persistence.proposal.ProposalRuleViolationException;
import com.springaimcpservercommon.persistence.proposal.ProposalRuleViolationException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @ParameterizedTest
    @CsvSource({
            "NOT_OWNER,404",
            "OWNER_CANNOT_APPROVE,403",
            "CONTENT_HASH_MISMATCH,409",
            "EXPIRED,410",
            "STALE_VERSION,412",
            "ILLEGAL_TRANSITION,409",
            "ALREADY_DECIDED,409",
            "IDEMPOTENCY_KEY_REUSED,409"})
    void proposalRuleViolationsMapToStableStatuses(Reason reason, int status) {
        ResponseEntity<String> response = handler.proposalRule(
                new ProposalRuleViolationException(reason, "internal detail with id 42"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).doesNotContain("internal detail");
    }

    @Test
    void missingElementIs404() {
        ResponseEntity<String> response = handler.notFound(new java.util.NoSuchElementException("workspace x"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).doesNotContain("workspace x");
    }
}
