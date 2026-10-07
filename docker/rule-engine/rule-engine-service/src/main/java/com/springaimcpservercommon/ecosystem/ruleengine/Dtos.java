package com.springaimcpservercommon.ecosystem.ruleengine;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The JSON vocabulary of the API. Views are what the UI reads, requests are what it writes. Message texts are maps of
 * language tag to text ({@code {"en": "…", "hi": "…"}}). No view or log ever carries an evaluation input value.
 */
public final class Dtos {

    private Dtos() {
    }

    // ── catalog views ──────────────────────────────────────────────────────────────────────────────────

    /** A business module rules are selected by. */
    public record Module(String code, String name, @Nullable String description) {
    }

    /** One parameter of the library: {@code object.attribute} is the CEL variable. */
    public record Attribute(String code, String name, String dataType, boolean required, @Nullable String sampleValue,
                            String celName, int usedByRules) {
    }

    /** A library object with its attributes. */
    public record LibraryObject(String code, String name, @Nullable String moduleCode, List<Attribute> attributes) {
    }

    /** A rule. {@code scope} is TENANT (every organization) or ORGANIZATION (the caller's own). */
    public record RuleView(UUID id, String moduleCode, String code, String name, @Nullable String description,
                           String expression, String status, String scope, String trueAction, String falseAction,
                           Map<String, String> trueMessage, Map<String, String> falseMessage,
                           List<String> parameters, List<String> groups, long rowVersion, Instant updatedAt) {
    }

    /** A rule inside a group. */
    public record GroupRuleView(String ruleCode, String ruleName, String ruleStatus, int sequence, boolean enabled) {
    }

    /** A trigger point binding an application form action (or field) to a group. */
    public record TriggerView(UUID id, String application, String type, String formCode, String actionCode,
                              @Nullable String fieldCode, String moduleCode, String groupCode, int sequence,
                              boolean enabled, String scope) {
    }

    /** A rule group with its policy, members and triggers. */
    public record GroupView(UUID id, String moduleCode, String code, String name, @Nullable String description,
                            String status, String scope, String policy, String matchOn, String onError,
                            String compositeTrueAction, String compositeFalseAction,
                            Map<String, String> compositeTrueMessage, Map<String, String> compositeFalseMessage,
                            List<GroupRuleView> rules, List<TriggerView> triggers, int channelCount, long rowVersion,
                            Instant updatedAt) {
    }

    /** A communication bound to a rule or group outcome. */
    public record ChannelView(UUID id, String ownerType, @Nullable String ownerCode, String onResult,
                              String channelType, int sequence, boolean enabled, @Nullable String emailTemplate,
                              @Nullable String apiEndpoint, boolean hasRecipientExpression) {
    }

    /** A caller-side e-mail template. */
    public record EmailTemplateView(UUID id, String templateRef, String name, boolean active) {
    }

    /** An API endpoint a channel may call. The credential reference is deliberately not exposed. */
    public record ApiEndpointView(UUID id, String name, String method, String url, String environment, int timeoutMs,
                                  boolean externalConfirmed, boolean active) {
    }

    /** What the setup page shows first. */
    public record SetupSummary(String tenant, @Nullable String organization, String role, int modules, int objects,
                               int parameters, int rules, int activeRules, int groups, int activeGroups, int triggers,
                               int channels, int emailTemplates, int apiEndpoints, int messages,
                               List<String> policies, List<String> actions, List<String> languages) {
    }

    // ── authoring requests ─────────────────────────────────────────────────────────────────────────────

    /** Create a rule. */
    public record CreateRule(String moduleCode, String code, String name, @Nullable String description,
                             String expression, @Nullable Map<String, String> trueMessage,
                             @Nullable Map<String, String> falseMessage, @Nullable String trueAction,
                             @Nullable String falseAction, @Nullable String status, @Nullable String scope) {
    }

    /** A rule referenced by a group; the sequence defaults to 10, 20, 30 … in the order given. */
    public record RuleRef(String ruleCode, @Nullable Integer sequence, @Nullable Boolean enabled) {
    }

    /** A trigger point created together with a group. */
    public record TriggerSpec(String application, String type, String formCode, String actionCode,
                              @Nullable String fieldCode, @Nullable Integer sequence) {
    }

    /** Create or replace a rule group. {@code expectedRowVersion} is required when replacing. */
    public record GroupRequest(String moduleCode, String code, String name, @Nullable String description,
                               String policy, @Nullable String matchOn, @Nullable String onError,
                               @Nullable Map<String, String> compositeTrueMessage,
                               @Nullable Map<String, String> compositeFalseMessage,
                               @Nullable String compositeTrueAction, @Nullable String compositeFalseAction,
                               @Nullable List<RuleRef> rules, @Nullable List<TriggerSpec> triggers,
                               @Nullable String status, @Nullable String scope, @Nullable Long expectedRowVersion) {
    }

    /** Change a status (DRAFT, ACTIVE or RETIRED). */
    public record StatusChange(String status) {
    }

    /** What a write returns: the new object and anything the author should know. */
    public record Written<T>(T value, List<String> warnings) {
    }

    // ── evaluation ─────────────────────────────────────────────────────────────────────────────────────

    /** Evaluate one group with the facts supplied (values for library parameters). */
    public record EvaluateRequest(String moduleCode, String groupCode, @Nullable Map<String, Object> facts,
                                  @Nullable List<String> languages) {
    }

    /** Evaluate whatever is bound to a trigger point. */
    public record TriggerRequestBody(String application, String type, String formCode, String actionCode,
                                     @Nullable String fieldCode, @Nullable Map<String, Object> facts,
                                     @Nullable List<String> languages) {
    }

    /** One message of a decision, already in the requested language. */
    public record MessageView(String source, @Nullable String ruleCode, String outcome, String action,
                              String language, String text) {
    }

    /** One rule's raw outcome. */
    public record RuleOutcome(String ruleCode, String ruleName, int sequence, String outcome, String action,
                              @Nullable String errorCode, @Nullable String errorDetail) {
    }

    /** A communication the decision would trigger; the recipient is never returned. */
    public record PlannedChannelView(String type, String onResult, @Nullable String ruleCode,
                                     boolean recipientResolved, @Nullable String target) {
    }

    /** The decision of one group. */
    public record Decision(String moduleCode, String groupCode, String policy, String decision, boolean matched,
                           @Nullable MessageView primaryMessage, List<MessageView> messages,
                           List<RuleOutcome> results, List<PlannedChannelView> channels, long durationMicros) {
    }

    /** The decision of a trigger: the strictest of its groups. */
    public record TriggerDecision(String decision, List<Decision> groups) {
    }

    // ── admin logs ─────────────────────────────────────────────────────────────────────────────────────

    /** A page of results. */
    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    /** One logged evaluation. */
    public record EvaluationLog(UUID id, Instant at, @Nullable String moduleCode, @Nullable String groupCode,
                                @Nullable String groupName, String policy, String decision, @Nullable String language,
                                long durationMicros, int rulesTrue, int rulesFalse, int rulesError,
                                boolean viaTrigger, @Nullable UUID organizationId) {
    }

    /** An evaluation with each rule's outcome. */
    public record EvaluationDetail(EvaluationLog evaluation, List<RuleOutcome> results) {
    }

    /** One audit trail entry. */
    public record AuditEntry(UUID id, Instant at, @Nullable String actorName, @Nullable String actorRole,
                             String action, String entityType, @Nullable UUID entityId, @Nullable String entityCode,
                             @Nullable String summary, @Nullable String details, @Nullable UUID organizationId) {
    }

    /** One AI chat conversation (metadata only). */
    public record ConversationLog(UUID id, @Nullable String title, String channel, String status, Instant startedAt,
                                  Instant lastActivityAt, long messages) {
    }

    /** One chat message as stored (already redacted). */
    public record ChatMessage(int seq, String role, String content, boolean redacted, @Nullable Integer tokens,
                              Instant at) {
    }

    /** A conversation with its transcript. */
    public record ConversationDetail(ConversationLog conversation, List<ChatMessage> messages) {
    }

    /** One bucket of the evaluation time series. */
    public record Bucket(Instant at, long total, long allow, long warn, long block) {
    }

    /** A group's share of the traffic. */
    public record GroupLoad(@Nullable String moduleCode, String groupCode, long evaluations, long blocked,
                            long errors, double avgMillis) {
    }

    /** The dashboard numbers for a window. */
    public record LogSummary(int hours, long evaluations, long allow, long warn, long block, long withErrors,
                             double p50Millis, double p95Millis, long auditEvents, long authoringChanges,
                             long conversations, List<Bucket> series, List<GroupLoad> topGroups,
                             Map<String, Long> auditByAction) {
    }

    /** The answer to "does this CEL expression compile against the parameter library?". Never contains values. */
    public record ExpressionCheck(boolean valid, @Nullable String error, List<String> parameters) {
    }

    /** Request of {@code POST /api/v1/expressions/check}. */
    public record CheckExpression(String expression) {
    }

    /** What the assistant panel needs to know before it opens. */
    public record AssistantInfo(boolean available, @Nullable String agentSlug, @Nullable String provider,
                                @Nullable String note) {
    }
}
