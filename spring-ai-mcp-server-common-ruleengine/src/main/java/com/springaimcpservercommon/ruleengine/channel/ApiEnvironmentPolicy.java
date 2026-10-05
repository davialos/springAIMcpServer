package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.core.environment.EnvironmentTier;
import com.springaimcpservercommon.ruleengine.channel.ApiCheck.Verdict;
import com.springaimcpservercommon.ruleengine.model.ApiEndpoint;
import com.springaimcpservercommon.ruleengine.model.ApiEnvironment;

/**
 * Keeps each environment on its own APIs: DEV may call DEV endpoints, QA (TEST and STAGE tiers) QA endpoints, and
 * production (PROD and UNKNOWN, LLD-12) production endpoints. An endpoint outside our environments
 * ({@link ApiEnvironment#EXTERNAL}) cannot be validated, so it needs a human confirmation that is recorded with it;
 * an unconfirmed one is never called.
 */
public final class ApiEnvironmentPolicy {

    private final ApiEnvironment running;

    /**
     * Creates the policy for the environment this node runs in.
     *
     * @param tier the node's tier
     */
    public ApiEnvironmentPolicy(EnvironmentTier tier) {
        this.running = switch (tier) {
            case DEV -> ApiEnvironment.DEV;
            case TEST, STAGE -> ApiEnvironment.QA;
            case PROD, UNKNOWN -> ApiEnvironment.PROD;
        };
    }

    /**
     * The API environment this node may call.
     *
     * @return DEV, QA or PROD
     */
    public ApiEnvironment runningEnvironment() {
        return running;
    }

    /**
     * Checks an endpoint at configuration time (admin UI): drives the confirmation pop-up.
     *
     * @param declared  the environment the user picked or the classifier derived
     * @param confirmed whether the user already confirmed the pop-up
     * @return the verdict
     */
    public ApiCheck checkForSave(ApiEnvironment declared, boolean confirmed) {
        if (declared == ApiEnvironment.EXTERNAL) {
            return confirmed ? allowed() : confirmationRequired();
        }
        return declared == running ? allowed() : mismatch(declared);
    }

    /**
     * Checks an endpoint before calling it.
     *
     * @param endpoint the endpoint
     * @return the verdict; only ALLOWED may be called
     */
    public ApiCheck checkForDispatch(ApiEndpoint endpoint) {
        return checkForSave(endpoint.environment(), endpoint.externalConfirmed());
    }

    private static ApiCheck allowed() {
        return new ApiCheck(Verdict.ALLOWED, "api.allowed", "The endpoint may be used.");
    }

    private static ApiCheck confirmationRequired() {
        return new ApiCheck(Verdict.CONFIRMATION_REQUIRED, "api.external.confirm",
                "You are trying to integrate an external API that we cannot validate as belonging to this "
                        + "environment. Please confirm the API once again and proceed.");
    }

    private ApiCheck mismatch(ApiEnvironment declared) {
        return new ApiCheck(Verdict.REJECTED_ENVIRONMENT_MISMATCH, "api.environment.mismatch",
                "This is a " + declared + " API; this environment only allows " + running + " APIs.");
    }
}
