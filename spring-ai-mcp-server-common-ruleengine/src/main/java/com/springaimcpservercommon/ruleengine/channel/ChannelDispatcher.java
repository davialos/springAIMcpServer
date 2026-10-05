package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.ruleengine.channel.DispatchResult.Status;
import com.springaimcpservercommon.ruleengine.model.ChannelType;
import com.springaimcpservercommon.ruleengine.response.EvaluationResponse;
import com.springaimcpservercommon.ruleengine.response.ResponseMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sends planned communications through the ports. Each channel is isolated: one failing sender never stops the
 * others and never fails the evaluation (release-it: contain failures). API channels pass the
 * {@link ApiEnvironmentPolicy} first. A missing port means SKIPPED, not an error. Recipients and payloads are never
 * logged. Thread-safe if the ports are.
 */
public final class ChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ChannelDispatcher.class);

    private final @Nullable EmailSender email;
    private final @Nullable PushSender push;
    private final ApiCaller api;
    private final ApiEnvironmentPolicy policy;

    /**
     * Creates the dispatcher.
     *
     * @param email  mail port, or {@code null} if the host has none
     * @param push   push port, or {@code null}
     * @param api    HTTP port (normally {@link HttpApiCaller})
     * @param policy API environment guard
     */
    public ChannelDispatcher(@Nullable EmailSender email, @Nullable PushSender push, ApiCaller api,
                             ApiEnvironmentPolicy policy) {
        this.email = email;
        this.push = push;
        this.api = Objects.requireNonNull(api, "api");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Sends every planned channel.
     *
     * @param planned     what the evaluation asked for
     * @param response    the response the caller gets (decision and messages go into API payloads)
     * @return one result per planned channel, in order
     */
    public List<DispatchResult> dispatch(List<PlannedChannel> planned, EvaluationResponse response) {
        List<DispatchResult> out = new ArrayList<>(planned.size());
        for (PlannedChannel p : planned) {
            out.add(send(p, response));
        }
        return out;
    }

    private DispatchResult send(PlannedChannel p, EvaluationResponse response) {
        ChannelType type = p.binding().type();
        try {
            return switch (type) {
                case EMAIL -> sendEmail(p, response);
                case PUSH -> sendPush(p);
                case API -> callApi(p, response);
            };
        } catch (Exception e) {
            log.warn("channel {} {} failed: {}", type, p.binding().id(), e.getClass().getSimpleName());
            return result(p, Status.FAILED, e.getClass().getSimpleName());
        }
    }

    private DispatchResult sendEmail(PlannedChannel p, EvaluationResponse response) throws Exception {
        if (email == null) {
            return result(p, Status.SKIPPED, "no EmailSender configured");
        }
        if (p.emailTemplate() == null) {
            return result(p, Status.SKIPPED, "e-mail template missing or inactive");
        }
        if (p.recipient() == null) {
            return result(p, Status.SKIPPED, "no recipient");
        }
        email.send(new EmailMessage(p.emailTemplate().templateRef(), p.emailTemplate().name(), p.recipient(),
                p.language(), response.moduleCode(), response.groupCode(), response.decision()));
        return result(p, Status.SENT, null);
    }

    private DispatchResult sendPush(PlannedChannel p) throws Exception {
        if (push == null) {
            return result(p, Status.SKIPPED, "no PushSender configured");
        }
        if (p.recipient() == null || p.pushBody() == null) {
            return result(p, Status.SKIPPED, "no recipient or body");
        }
        push.send(new PushMessage(p.recipient(), p.pushTitle() == null ? "" : p.pushTitle(), p.pushBody(),
                p.language()));
        return result(p, Status.SENT, null);
    }

    private DispatchResult callApi(PlannedChannel p, EvaluationResponse response) throws Exception {
        if (p.apiEndpoint() == null) {
            return result(p, Status.SKIPPED, "endpoint missing or inactive");
        }
        ApiCheck check = policy.checkForDispatch(p.apiEndpoint());
        if (!check.allowed()) {
            log.warn("API channel {} refused: {}", p.binding().id(), check.reasonKey());
            return result(p, Status.REFUSED, check.reasonKey());
        }
        api.call(p.apiEndpoint(), CanonicalJson.write(payload(response)));
        return result(p, Status.SENT, null);
    }

    private static Map<String, Object> payload(EvaluationResponse r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("module", r.moduleCode());
        body.put("group", r.groupCode());
        body.put("policy", r.policy().name());
        body.put("decision", r.decision().name());
        body.put("matched", r.matched());
        List<Object> messages = new ArrayList<>();
        for (ResponseMessage m : r.messages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", m.source().name());
            item.put("rule", m.ruleCode());
            item.put("outcome", m.outcome().name());
            item.put("action", m.action().name());
            item.put("language", m.language());
            item.put("text", m.text());
            messages.add(item);
        }
        body.put("messages", messages);
        return body;
    }

    private static DispatchResult result(PlannedChannel p, Status status, @Nullable String reason) {
        return new DispatchResult(p.binding().id(), p.binding().type(), status, reason);
    }
}
