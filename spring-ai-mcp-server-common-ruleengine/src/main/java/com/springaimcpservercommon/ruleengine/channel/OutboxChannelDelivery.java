package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.ruleengine.channel.DispatchResult.Status;
import com.springaimcpservercommon.ruleengine.model.ChannelType;
import com.springaimcpservercommon.ruleengine.response.EvaluationResponse;
import com.springaimcpservercommon.ruleengine.store.OutboxStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Queues planned communications in the outbox instead of sending them on the caller's thread: the transaction that
 * asked for a rule decision is never slowed down or failed by a slow mail server, and a failed send is retried by
 * {@link OutboxWorker}. The same checks as direct dispatch decide what is queued (no recipient, missing template or an
 * endpoint the environment guard refuses are reported at once and never queued).
 */
public final class OutboxChannelDelivery implements ChannelDelivery {

    private static final Logger log = LoggerFactory.getLogger(OutboxChannelDelivery.class);

    private final OutboxStore outbox;
    private final ApiEnvironmentPolicy policy;
    private final int maxAttempts;

    /**
     * Creates the delivery.
     *
     * @param outbox      the outbox
     * @param policy      API environment guard (checked now and again when sending)
     * @param maxAttempts attempts per communication before it is dead (1..50)
     */
    public OutboxChannelDelivery(OutboxStore outbox, ApiEnvironmentPolicy policy, int maxAttempts) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.maxAttempts = maxAttempts;
    }

    @Override
    public List<DispatchResult> dispatch(UUID tenantId, List<PlannedChannel> planned, EvaluationResponse response) {
        List<DispatchResult> out = new ArrayList<>(planned.size());
        for (PlannedChannel p : planned) {
            try {
                out.add(queue(tenantId, p, response));
            } catch (RuntimeException e) {
                log.warn("could not queue channel {} {}: {}", p.binding().type(), p.binding().id(),
                        e.getClass().getSimpleName());
                out.add(result(p, Status.FAILED, e.getClass().getSimpleName()));
            }
        }
        return out;
    }

    private DispatchResult queue(UUID tenantId, PlannedChannel p, EvaluationResponse response) {
        ChannelType type = p.binding().type();
        UUID id = OutboxStore.newId();
        switch (type) {
            case EMAIL -> {
                if (p.emailTemplate() == null) {
                    return result(p, Status.SKIPPED, "e-mail template missing or inactive");
                }
                if (p.recipient() == null) {
                    return result(p, Status.SKIPPED, "no recipient");
                }
                outbox.enqueue(id, tenantId, type, p.binding().id(), null, CanonicalJson.write(DispatchPayloads.email(
                        new EmailMessage(p.emailTemplate().templateRef(), p.emailTemplate().name(), p.recipient(),
                                p.language(), response.moduleCode(), response.groupCode(), response.decision()))),
                        maxAttempts);
            }
            case PUSH -> {
                if (p.recipient() == null || p.pushBody() == null) {
                    return result(p, Status.SKIPPED, "no recipient or body");
                }
                outbox.enqueue(id, tenantId, type, p.binding().id(), null, CanonicalJson.write(DispatchPayloads.push(
                        new PushMessage(p.recipient(), p.pushTitle() == null ? "" : p.pushTitle(), p.pushBody(),
                                p.language()))), maxAttempts);
            }
            case API -> {
                if (p.apiEndpoint() == null) {
                    return result(p, Status.SKIPPED, "endpoint missing or inactive");
                }
                ApiCheck check = policy.checkForDispatch(p.apiEndpoint());
                if (!check.allowed()) {
                    return result(p, Status.REFUSED, check.reasonKey());
                }
                outbox.enqueue(id, tenantId, type, p.binding().id(), p.apiEndpoint().id(),
                        CanonicalJson.write(DispatchPayloads.api(response, id)), maxAttempts);
            }
        }
        return result(p, Status.QUEUED, null);
    }

    private static DispatchResult result(PlannedChannel p, Status status, @Nullable String reason) {
        return new DispatchResult(p.binding().id(), p.binding().type(), status, reason);
    }
}
