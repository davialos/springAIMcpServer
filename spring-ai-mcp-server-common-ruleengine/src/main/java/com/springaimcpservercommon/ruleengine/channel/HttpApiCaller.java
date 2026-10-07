package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.model.ApiEndpoint;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Default {@link ApiCaller} on the JDK HTTP client: JSON body, the endpoint's timeout, no redirects (a redirect could
 * leave the environment the policy approved), any non-2xx answer is a failure. Credentials are the host's business:
 * wrap this class to add headers from {@link ApiEndpoint#authSecretRef()}.
 */
public final class HttpApiCaller implements ApiCaller {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Override
    public void call(ApiEndpoint endpoint, String jsonBody) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint.url()))
                .timeout(Duration.ofMillis(endpoint.timeoutMillis()))
                .header("Content-Type", "application/json");
        HttpRequest built = "GET".equals(endpoint.method())
                ? request.GET().build()
                : request.method(endpoint.method(), HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        HttpResponse<Void> response = client.send(built, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("endpoint answered HTTP " + response.statusCode());
        }
    }
}
