package com.springaimcpservercommon.security.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Refuses to enable planes that no authentication mechanism can protect (SEC-01 §8, LLD-12): "fail the feature, not
 * the host". Autoconfigure evaluates it at startup and only creates the beans of enabled planes.
 *
 * <ul>
 *   <li>Admin plane: needs a host mechanism (session login, oauth2Login or bearer tokens).</li>
 *   <li>Data plane: needs bearer tokens, API keys, or (with {@code dataPlaneSessions}) a host session login.</li>
 *   <li>MCP: needs bearer tokens or API keys (MCP clients cannot use browser sessions).</li>
 *   <li>{@code allow-insecure} is honoured only in the DEV tier (UNKNOWN tier counts as PROD); it lets the planes start
 *       without a mechanism, with a loud warning. It never disables authorization: the engine still denies callers
 *       without a principal.</li>
 * </ul>
 */
public final class SecurityModeGuard {

    private static final Logger log = LoggerFactory.getLogger(SecurityModeGuard.class);

    private SecurityModeGuard() {
    }

    /**
     * Guard input.
     *
     * @param host              detected host authentication
     * @param apiKeysEnabled    whether API keys are enabled
     * @param dataPlaneSessions whether the data plane accepts host sessions
     * @param allowInsecure     {@code dynamic.ai.agent.security.allow-insecure}
     * @param devTier           whether the resolved environment tier is DEV
     */
    public record Input(HostAuthenticationCapabilities host, boolean apiKeysEnabled, boolean dataPlaneSessions,
                        boolean allowInsecure, boolean devTier) {
        /**
         * Validates components.
         */
        public Input {
            Objects.requireNonNull(host, "host");
        }
    }

    /**
     * Guard decision.
     *
     * @param adminPlaneEnabled whether the admin plane may start
     * @param dataPlaneEnabled  whether the data plane may start
     * @param mcpEnabled        whether the MCP endpoint may start
     * @param insecure          whether a plane starts only because of {@code allow-insecure}
     * @param messages          explanations (for logs and the failure analyzer)
     */
    public record Decision(boolean adminPlaneEnabled, boolean dataPlaneEnabled, boolean mcpEnabled, boolean insecure,
                           List<String> messages) {
        /**
         * Copies messages.
         */
        public Decision {
            messages = List.copyOf(messages);
        }
    }

    /**
     * Evaluates the guard and logs the outcome.
     *
     * @param input input
     * @return decision
     */
    public static Decision evaluate(Input input) {
        HostAuthenticationCapabilities host = input.host();
        boolean admin = host.any();
        boolean data = host.bearerTokens() || input.apiKeysEnabled()
                || (input.dataPlaneSessions() && (host.sessionLogin() || host.oauth2Login()));
        boolean mcp = host.bearerTokens() || input.apiKeysEnabled();

        List<String> messages = new ArrayList<>();
        boolean insecure = false;
        if (input.allowInsecure() && !input.devTier()) {
            messages.add("dynamic.ai.agent.security.allow-insecure is ignored outside the DEV tier");
        }
        if (!(admin && data && mcp) && input.allowInsecure() && input.devTier()) {
            insecure = true;
            messages.add("INSECURE: planes enabled without an authentication mechanism (allow-insecure, DEV tier)");
            admin = true;
            data = true;
            mcp = true;
        }
        if (!admin) {
            messages.add("admin plane disabled: the host has no authentication mechanism (session login, oauth2Login or JWT/opaque tokens)");
        }
        if (!data) {
            messages.add("data plane disabled: no bearer tokens, API keys or (with data-plane sessions) host login available");
        }
        if (!mcp) {
            messages.add("MCP endpoint disabled: needs bearer tokens (resource server) or API keys");
        }
        Decision decision = new Decision(admin, data, mcp, insecure, messages);
        for (String message : messages) {
            if (insecure || !admin || !data || !mcp) {
                log.warn("{}", message);
            } else {
                log.info("{}", message);
            }
        }
        return decision;
    }
}
