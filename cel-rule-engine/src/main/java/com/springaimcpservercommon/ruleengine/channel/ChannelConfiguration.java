package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.channel.Gateways.EmailGateway;
import com.springaimcpservercommon.ruleengine.channel.Gateways.PushGateway;
import com.springaimcpservercommon.ruleengine.config.RuleEngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** The HTTP client the channels use and the default gateways. */
@Configuration
public class ChannelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChannelConfiguration.class);

    @Bean
    RestClient channelRestClient(RuleEngineProperties properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.channels().apiTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.channels().apiTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    EmailGateway emailGateway(RuleEngineProperties properties, RestClient channelRestClient) {
        String url = properties.channels().emailGatewayUrl();
        if (url.isBlank()) {
            return m -> {
                log.info("e-mail recorded (no gateway configured): template {} ({}) to {}", m.templateId(),
                        m.templateName(), m.to());
                return "recorded (ruleengine.channels.email-gateway-url is not set)";
            };
        }
        return m -> {
            channelRestClient.post().uri(url).body(m).retrieve().toBodilessEntity();
            return "posted to the e-mail gateway";
        };
    }

    @Bean
    PushGateway pushGateway(RuleEngineProperties properties, RestClient channelRestClient) {
        String url = properties.channels().pushGatewayUrl();
        if (url.isBlank()) {
            return m -> {
                log.info("push recorded (no gateway configured): topic {}", m.topic());
                return "recorded (ruleengine.channels.push-gateway-url is not set)";
            };
        }
        return m -> {
            channelRestClient.post().uri(url).body(m).retrieve().toBodilessEntity();
            return "posted to the push gateway";
        };
    }
}
