package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.channel.ChannelDispatcher.Result;
import com.springaimcpservercommon.ruleengine.channel.Gateways.EmailGateway;
import com.springaimcpservercommon.ruleengine.channel.Gateways.EmailMessage;
import com.springaimcpservercommon.ruleengine.channel.Gateways.PushGateway;
import com.springaimcpservercommon.ruleengine.channel.Gateways.PushMessage;
import com.springaimcpservercommon.ruleengine.domain.Enums.ChannelType;
import com.springaimcpservercommon.ruleengine.domain.Model.ApiEndpoint;
import com.springaimcpservercommon.ruleengine.domain.Model.Channel;
import com.springaimcpservercommon.ruleengine.domain.Model.EmailTemplate;
import com.springaimcpservercommon.ruleengine.eval.Results.FinalMessage;
import com.springaimcpservercommon.ruleengine.repo.ChannelRepository;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The three channel kinds: e-mail with a caller-side template, push, and a call to an API. */
public final class Dispatchers {

    private Dispatchers() {
    }

    private static JsonNode config(JsonMapper mapper, Channel channel) {
        try {
            return channel.config() == null || channel.config().isBlank() ? mapper.createObjectNode()
                    : mapper.readTree(channel.config());
        } catch (RuntimeException e) {
            return mapper.createObjectNode();
        }
    }

    private static List<String> texts(Notification n) {
        return n.messages().stream().map(FinalMessage::text).toList();
    }

    /** E-mail: names the caller-side template (id and name) and sends the variables; the caller's service renders it. */
    @Component
    public static class Email implements ChannelDispatcher {

        private final ChannelRepository channels;
        private final EmailGateway gateway;
        private final JsonMapper mapper;

        public Email(ChannelRepository channels, EmailGateway gateway, JsonMapper mapper) {
            this.channels = channels;
            this.gateway = gateway;
            this.mapper = mapper;
        }

        @Override
        public ChannelType type() {
            return ChannelType.EMAIL;
        }

        @Override
        public Result dispatch(Channel channel, Notification n) {
            EmailTemplate template = channel.emailTemplateId() == null ? null
                    : channels.template(channel.emailTemplateId()).orElse(null);
            if (template == null || !template.active()) {
                return Result.skipped("the e-mail template is missing or inactive");
            }
            JsonNode cfg = config(mapper, channel);
            List<String> to = new ArrayList<>();
            cfg.path("recipients").forEach(r -> to.add(r.asString()));
            String path = cfg.path("recipientPath").asString("");
            if (!path.isEmpty() && n.context().get(path) != null) {
                to.add(n.context().get(path));
            }
            if (to.isEmpty()) {
                return Result.skipped("no recipient: configure recipients or a recipientPath that the context fills");
            }
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("tenant", n.tenant());
            variables.put("module", n.module());
            variables.put("trigger", n.trigger());
            variables.put("subject", n.subject());
            variables.put("outcome", n.outcome());
            variables.put("action", n.action().name());
            variables.put("messages", texts(n));
            try {
                String result = gateway.send(new EmailMessage(template.externalTemplateId(), template.name(), to,
                        n.language(), variables));
                return Result.sent("template " + template.externalTemplateId() + " (" + template.name() + ") to "
                        + to.size() + " recipient(s): " + result);
            } catch (RuntimeException e) {
                return Result.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /** Push notification: the first message as the body. */
    @Component
    public static class Push implements ChannelDispatcher {

        private final PushGateway gateway;
        private final JsonMapper mapper;

        public Push(PushGateway gateway, JsonMapper mapper) {
            this.gateway = gateway;
            this.mapper = mapper;
        }

        @Override
        public ChannelType type() {
            return ChannelType.PUSH;
        }

        @Override
        public Result dispatch(Channel channel, Notification n) {
            JsonNode cfg = config(mapper, channel);
            String topic = cfg.path("topic").asString("");
            String path = cfg.path("topicPath").asString("");
            if (topic.isEmpty() && !path.isEmpty()) {
                topic = n.context().getOrDefault(path, "");
            }
            if (topic.isEmpty()) {
                return Result.skipped("no topic: configure topic or a topicPath that the context fills");
            }
            String body = texts(n).isEmpty() ? n.action().name() : texts(n).getFirst();
            try {
                return Result.sent(gateway.send(new PushMessage(topic, cfg.path("title").asString(channel.name()), body,
                        Map.of("action", n.action().name(), "subject", n.subject(), "evaluationId",
                                n.evaluationId().toString()))));
            } catch (RuntimeException e) {
                return Result.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /** API call: only to an API that passes the environment guard at call time. */
    @Component
    public static class Api implements ChannelDispatcher {

        private final ChannelRepository channels;
        private final ApiEndpointService endpoints;
        private final RestClient client;
        private final JsonMapper mapper;

        public Api(ChannelRepository channels, ApiEndpointService endpoints, RestClient channelRestClient,
                   JsonMapper mapper) {
            this.channels = channels;
            this.endpoints = endpoints;
            this.client = channelRestClient;
            this.mapper = mapper;
        }

        @Override
        public ChannelType type() {
            return ChannelType.API;
        }

        @Override
        public Result dispatch(Channel channel, Notification n) {
            ApiEndpoint endpoint = channel.apiEndpointId() == null ? null
                    : channels.endpoint(channel.apiEndpointId()).orElse(null);
            if (endpoint == null) {
                return Result.skipped("the API endpoint is missing");
            }
            String blocker = endpoints.callBlocker(endpoint);
            if (blocker != null) {
                return Result.skipped("not called: " + blocker);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("evaluationId", n.evaluationId().toString());
            body.put("tenant", n.tenant());
            body.put("module", n.module());
            body.put("trigger", n.trigger());
            body.put("subjectType", n.subjectType());
            body.put("subject", n.subject());
            body.put("outcome", n.outcome());
            body.put("action", n.action().name());
            body.put("language", n.language());
            body.put("messages", texts(n));
            try {
                RestClient.RequestBodySpec request = client.method(HttpMethod.valueOf(endpoint.httpMethod()))
                        .uri(endpoint.url()).header("Content-Type", "application/json");
                if (endpoint.headers() != null && !endpoint.headers().isBlank()) {
                    mapper.readTree(endpoint.headers()).properties()
                            .forEach(e -> request.header(e.getKey(), e.getValue().asString()));
                }
                var status = request.body(body).retrieve().toBodilessEntity().getStatusCode();
                return Result.sent(endpoint.httpMethod() + " " + endpoint.name() + " -> HTTP " + status.value());
            } catch (RuntimeException e) {
                return Result.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }
}
