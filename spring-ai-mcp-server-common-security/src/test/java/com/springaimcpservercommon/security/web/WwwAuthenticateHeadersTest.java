package com.springaimcpservercommon.security.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WwwAuthenticateHeadersTest {

    private static final String PRM = "https://app.example.com/.well-known/oauth-protected-resource/dynamic-ai/mcp";

    @Test
    void unauthorizedChallengeMatchesMcpSpecExample() {
        assertThat(WwwAuthenticateHeaders.bearer().resourceMetadata(PRM).scopes(List.of("dai.mcp.read")).build())
                .isEqualTo("Bearer scope=\"dai.mcp.read\", resource_metadata=\"" + PRM + "\"");
    }

    @Test
    void insufficientScopeChallengeListsAllScopes() {
        String header = WwwAuthenticateHeaders.bearer()
                .error(WwwAuthenticateHeaders.BearerError.INSUFFICIENT_SCOPE)
                .scopes(List.of("dai.mcp.read", "dai.mcp.propose"))
                .resourceMetadata(PRM)
                .errorDescription("Additional scope dai.mcp.propose required")
                .build();
        assertThat(header).isEqualTo("Bearer error=\"insufficient_scope\", scope=\"dai.mcp.read dai.mcp.propose\", "
                + "resource_metadata=\"" + PRM + "\", error_description=\"Additional scope dai.mcp.propose required\"");
    }

    @Test
    void bareChallengeAndRealm() {
        assertThat(WwwAuthenticateHeaders.bearer().build()).isEqualTo("Bearer");
        assertThat(WwwAuthenticateHeaders.bearer().realm("dynamic-ai").error(WwwAuthenticateHeaders.BearerError.INVALID_TOKEN).build())
                .isEqualTo("Bearer realm=\"dynamic-ai\", error=\"invalid_token\"");
    }

    @Test
    void injectionAttemptsAreRejected() {
        assertThatThrownBy(() -> WwwAuthenticateHeaders.bearer().scopes(List.of("a\" error=\"x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WwwAuthenticateHeaders.bearer().scopes(List.of("a b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WwwAuthenticateHeaders.bearer().errorDescription("line\r\nSet-Cookie: x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WwwAuthenticateHeaders.bearer().resourceMetadata("http://evil.example.com/prm"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WwwAuthenticateHeaders.bearer().resourceMetadata("/relative"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(WwwAuthenticateHeaders.bearer().resourceMetadata("http://localhost:8080/prm").build()).contains("localhost");
    }
}
