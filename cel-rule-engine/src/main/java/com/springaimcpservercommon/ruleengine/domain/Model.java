package com.springaimcpservercommon.ruleengine.domain;

import com.springaimcpservercommon.ruleengine.domain.Enums.ChannelType;
import com.springaimcpservercommon.ruleengine.domain.Enums.CompositeMode;
import com.springaimcpservercommon.ruleengine.domain.Enums.OnError;
import com.springaimcpservercommon.ruleengine.domain.Enums.Outcome;
import com.springaimcpservercommon.ruleengine.domain.Enums.Status;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

/** The rows of the schema as immutable values. */
public final class Model {

    private Model() {
    }

    /** A tenant organization. */
    public record Tenant(long id, String code, String name, String defaultLanguage, boolean active) { }

    /** An organization below a tenant. */
    public record Organization(long id, long tenantId, @Nullable Long parentId, String code, String name,
                               boolean active) { }

    /** A product module (lending, payments …) a tenant can select. */
    public record Module(long id, String code, String name, @Nullable String description, boolean active) { }

    /** A parameter-library object: becomes the CEL variable {@code code}. */
    public record SysObject(long id, String code, String name, @Nullable String description, @Nullable Long moduleId,
                            boolean active, List<SysAttribute> attributes) { }

    /** An attribute of a sys object: the CEL expression {@code object.attribute}. */
    public record SysAttribute(long id, long objectId, String code, String name, DataType dataType, boolean required,
                               @Nullable String description) { }

    /** A rule: a CEL expression with messages and actions for both results. */
    public record Rule(long id, long tenantId, @Nullable Long organizationId, long moduleId, String code, String name,
                       @Nullable String description, String expression, @Nullable Long trueBundleId,
                       @Nullable Long falseBundleId, Action trueAction, Action falseAction, Status status,
                       int version) { }

    /** A group of rules evaluated by a policy. */
    public record RuleGroup(long id, long tenantId, @Nullable Long organizationId, long moduleId, String code,
                            String name, @Nullable String description, Policy policy, boolean matchOnTrue,
                            CompositeMode compositeMode, OnError onError, @Nullable Long trueBundleId,
                            @Nullable Long falseBundleId, Action trueAction, Action falseAction, Status status,
                            int version) { }

    /** A rule's place in a group. */
    public record GroupMember(long id, long groupId, long ruleId, int sequence, boolean active) { }

    /** Where applications ask for evaluations: a form action, optionally one field. */
    public record TriggerPoint(long id, long tenantId, long moduleId, String code, String name, String formCode,
                               String actionType, @Nullable String fieldCode, boolean active) { }

    /** A rule group bound to a trigger point. */
    public record TriggerBinding(long id, long triggerPointId, long ruleGroupId, int sequence, boolean active) { }

    /** A caller-side e-mail template. */
    public record EmailTemplate(long id, long tenantId, String externalTemplateId, String name,
                                @Nullable String description, boolean active) { }

    /** An API an API channel calls. */
    public record ApiEndpoint(long id, long tenantId, String name, String url, String httpMethod,
                              @Nullable String headers, String environmentClass, boolean externalConfirmed,
                              @Nullable String confirmedBy, @Nullable OffsetDateTime confirmedAt, boolean active) { }

    /** A way to communicate an outcome. */
    public record Channel(long id, long tenantId, String name, ChannelType type, @Nullable Long emailTemplateId,
                          @Nullable Long apiEndpointId, @Nullable String config, boolean active) { }

    /** "On this outcome of this rule or group, use that channel." */
    public record ActionBinding(long id, long tenantId, @Nullable Long ruleId, @Nullable Long ruleGroupId,
                                Outcome outcome, long channelId) { }
}
