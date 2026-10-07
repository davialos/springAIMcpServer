package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.channel.EnvironmentGuard.Classification;
import com.springaimcpservercommon.ruleengine.channel.EnvironmentGuard.Kind;
import com.springaimcpservercommon.ruleengine.domain.Model.ApiEndpoint;
import com.springaimcpservercommon.ruleengine.repo.ChannelRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Registers the APIs that API channels call. A DEV deployment accepts DEV APIs, QA accepts QA, production accepts
 * production; an API of another environment is refused; an API the engine cannot place in any environment is
 * "external" and is stored only after the user confirmed it (the UI shows the pop-up on {@link
 * ConfirmationRequiredException}).
 */
@Service
public class ApiEndpointService {

    /** The text the UI shows in the confirmation pop-up. */
    public static final String EXTERNAL_MESSAGE = "You are trying to integrate an external API that we cannot validate "
            + "as a production or same-environment API. Please confirm the API once again and proceed with this action.";

    private final EnvironmentGuard guard;
    private final ChannelRepository channels;

    public ApiEndpointService(EnvironmentGuard guard, ChannelRepository channels) {
        this.guard = guard;
        this.channels = channels;
    }

    /**
     * Registers an API.
     *
     * @param tenantId        tenant
     * @param name            display name
     * @param url             absolute URL
     * @param method          HTTP method
     * @param headers         JSON object of headers, or {@code null}
     * @param confirmExternal the user confirmed an external API
     * @param confirmedBy     who confirmed
     * @return the stored endpoint
     * @throws ConfirmationRequiredException when the API is external and not confirmed
     * @throws IllegalStateException         when the API belongs to another environment
     */
    public ApiEndpoint register(long tenantId, String name, String url, String method, @Nullable String headers,
                                boolean confirmExternal, @Nullable String confirmedBy) {
        Classification c = guard.classify(url);
        switch (c.kind()) {
            case OTHER_ENVIRONMENT -> throw new IllegalStateException("The host " + c.host() + " belongs to the "
                    + c.environment() + " environment, but this system runs in " + guard.environment()
                    + ": it cannot be used here.");
            case EXTERNAL -> {
                if (!confirmExternal) {
                    throw new ConfirmationRequiredException("EXTERNAL_API_CONFIRMATION_REQUIRED", EXTERNAL_MESSAGE);
                }
                return channels.createEndpoint(tenantId, name, url, method, headers, Kind.EXTERNAL.name(), true,
                        confirmedBy);
            }
            default -> { }
        }
        return channels.createEndpoint(tenantId, name, url, method, headers, Kind.SAME_ENVIRONMENT.name(), false, null);
    }

    /**
     * Whether an endpoint may be called now: the guard is applied again at call time, because the URL's environment
     * (or this deployment's) may have changed since it was registered.
     *
     * @param endpoint the stored endpoint
     * @return {@code null} when it may be called, else why not
     */
    public @Nullable String callBlocker(ApiEndpoint endpoint) {
        if (!endpoint.active()) {
            return "the endpoint is inactive";
        }
        Classification c = guard.classify(endpoint.url());
        return switch (c.kind()) {
            case SAME_ENVIRONMENT -> null;
            case OTHER_ENVIRONMENT -> "the host belongs to " + c.environment() + ", this system runs in "
                    + guard.environment();
            case EXTERNAL -> endpoint.externalConfirmed() ? null : "an external API that was never confirmed";
        };
    }
}
