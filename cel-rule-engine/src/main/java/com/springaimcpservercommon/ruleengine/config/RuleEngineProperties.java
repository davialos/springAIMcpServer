package com.springaimcpservercommon.ruleengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Settings under {@code ruleengine.*}.
 *
 * @param environment      the environment this deployment is (DEV, QA, PROD): API channels may only call APIs of it
 * @param apiHosts         host patterns per environment ({@code localhost}, {@code *.dev.acme.com}); an API whose host
 *                         matches none of them is "external" and needs an explicit confirmation
 * @param libraryRefresh   how often the cached parameter library is reloaded from the database
 * @param defaultLanguage  language when neither the request nor the tenant names one
 * @param storeContextKeys keep the names of the supplied attributes (never values) in the evaluation log
 * @param channels         channel settings
 */
@ConfigurationProperties(prefix = "ruleengine")
public record RuleEngineProperties(String environment, Map<String, List<String>> apiHosts, Duration libraryRefresh,
                                   String defaultLanguage, boolean storeContextKeys, Channels channels) {

    /**
     * Channel settings.
     *
     * @param emailGatewayUrl  the caller's mail service ({@code POST {templateId, templateName, to, variables,
     *                         language}}); empty = e-mails are only recorded in the dispatch log
     * @param pushGatewayUrl   the push provider's gateway; empty = recorded only
     * @param apiTimeout       timeout of API channel calls
     */
    public record Channels(String emailGatewayUrl, String pushGatewayUrl, Duration apiTimeout) {

        /** Defaults for unset values. */
        public Channels {
            emailGatewayUrl = emailGatewayUrl == null ? "" : emailGatewayUrl;
            pushGatewayUrl = pushGatewayUrl == null ? "" : pushGatewayUrl;
            apiTimeout = apiTimeout == null ? Duration.ofSeconds(5) : apiTimeout;
        }
    }

    /** Defaults for unset values. */
    public RuleEngineProperties {
        environment = environment == null || environment.isBlank() ? "DEV" : environment.toUpperCase();
        apiHosts = apiHosts == null ? Map.of() : apiHosts;
        libraryRefresh = libraryRefresh == null ? Duration.ofMinutes(5) : libraryRefresh;
        defaultLanguage = defaultLanguage == null || defaultLanguage.isBlank() ? "en" : defaultLanguage;
        channels = channels == null ? new Channels("", "", null) : channels;
    }
}
