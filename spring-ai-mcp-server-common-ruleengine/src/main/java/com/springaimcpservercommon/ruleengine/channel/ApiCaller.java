package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.model.ApiEndpoint;

/** Port: performs the HTTP call of an API channel. {@link HttpApiCaller} is the default. */
@FunctionalInterface
public interface ApiCaller {

    /**
     * Calls the endpoint. Called only after {@link ApiEnvironmentPolicy} allowed the endpoint.
     *
     * @param endpoint the endpoint
     * @param jsonBody JSON request body (decision and messages; never input values)
     * @throws Exception on a transport error or a non-2xx answer
     */
    void call(ApiEndpoint endpoint, String jsonBody) throws Exception;
}
