package com.springaimcpservercommon.ruleengine.cache;

import com.springaimcpservercommon.ruleengine.cel.CompiledExpression;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.cel.RuleCompilationException;
import com.springaimcpservercommon.ruleengine.model.ChannelBinding;
import com.springaimcpservercommon.ruleengine.model.OwnerType;
import com.springaimcpservercommon.ruleengine.model.Rule;
import com.springaimcpservercommon.ruleengine.model.RuleGroup;
import com.springaimcpservercommon.ruleengine.model.TriggerPoint;
import com.springaimcpservercommon.ruleengine.model.TriggerType;
import com.springaimcpservercommon.ruleengine.store.TenantData;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One tenant's rules as an immutable in-memory snapshot, bound to a parameter-library snapshot. Rules compile
 * lazily on first use and are then reused for every evaluation. Thread-safe.
 */
public final class TenantCatalog {

    private final TenantData data;
    private final ParameterLibrary library;
    private final MessageCatalog messages;
    private final Map<UUID, CompiledRule> compiledRules = new ConcurrentHashMap<>();
    private final Map<UUID, Optional<CompiledExpression>> recipients = new ConcurrentHashMap<>();

    TenantCatalog(TenantData data, ParameterLibrary library, MessageCatalog messages) {
        this.data = data;
        this.library = library;
        this.messages = messages;
    }

    /**
     * The tenant's raw data.
     *
     * @return tenant data
     */
    public TenantData data() {
        return data;
    }

    /**
     * The parameter library this snapshot compiles against.
     *
     * @return the library
     */
    public ParameterLibrary library() {
        return library;
    }

    /**
     * The messages of this snapshot.
     *
     * @return the message catalog
     */
    public MessageCatalog messages() {
        return messages;
    }

    /**
     * Finds a group by module and code. An organization-specific group shadows the tenant-wide one of the same code.
     *
     * @param moduleCode     module
     * @param groupCode      group code
     * @param organizationId caller's organization, or {@code null}
     * @return the group, if any
     */
    public Optional<RuleGroup> group(String moduleCode, String groupCode, @Nullable UUID organizationId) {
        RuleGroup tenantWide = null;
        for (RuleGroup g : data.groups()) {
            if (g.moduleCode().equals(moduleCode) && g.code().equals(groupCode)) {
                if (organizationId != null && organizationId.equals(g.organizationId())) {
                    return Optional.of(g);
                }
                if (g.organizationId() == null) {
                    tenantWide = g;
                }
            }
        }
        return Optional.ofNullable(tenantWide);
    }

    /**
     * Finds a group by id.
     *
     * @param groupId group id
     * @return the group, if active in this tenant
     */
    public Optional<RuleGroup> group(UUID groupId) {
        return data.groups().stream().filter(g -> g.id().equals(groupId)).findFirst();
    }

    /**
     * The trigger points matching a place in an application, in sequence order. Organization-specific and
     * tenant-wide triggers both apply.
     *
     * @param application    integrating application
     * @param type           trigger type
     * @param formCode       form
     * @param actionCode     action or field event
     * @param fieldCode      field for FORM_FIELD, otherwise {@code null}
     * @param organizationId caller's organization, or {@code null}
     * @return matching triggers
     */
    public List<TriggerPoint> triggers(String application, TriggerType type, String formCode, String actionCode,
                                       @Nullable String fieldCode, @Nullable UUID organizationId) {
        List<TriggerPoint> out = new ArrayList<>();
        for (TriggerPoint t : data.triggers()) {
            boolean orgOk = t.organizationId() == null || t.organizationId().equals(organizationId);
            if (orgOk && t.application().equals(application) && t.type() == type && t.formCode().equals(formCode)
                    && t.actionCode().equals(actionCode) && Objects.equals(t.fieldCode(), fieldCode)) {
                out.add(t);
            }
        }
        out.sort(Comparator.comparingInt(TriggerPoint::sequence));
        return out;
    }

    /**
     * The channel bindings of a rule or group, in sequence order.
     *
     * @param ownerType rule or group
     * @param ownerId   its id
     * @return bindings
     */
    public List<ChannelBinding> channels(OwnerType ownerType, UUID ownerId) {
        return data.channels().stream()
                .filter(c -> c.ownerType() == ownerType && c.ownerId().equals(ownerId))
                .sorted(Comparator.comparingInt(ChannelBinding::sequence))
                .toList();
    }

    /**
     * The compiled form of a rule (compiled once per snapshot).
     *
     * @param rule the rule
     * @return the compiled rule, or the compile error
     */
    public CompiledRule compiled(Rule rule) {
        return compiledRules.computeIfAbsent(rule.id(), id -> {
            try {
                return new CompiledRule(rule, library.compileBoolean(rule.expression()), null);
            } catch (RuleCompilationException e) {
                return new CompiledRule(rule, null, e.getMessage());
            }
        });
    }

    /**
     * The compiled recipient expression of a channel binding.
     *
     * @param binding the binding
     * @return the expression, or empty if the binding has none or it does not compile
     */
    public Optional<CompiledExpression> recipient(ChannelBinding binding) {
        if (binding.recipientExpression() == null) {
            return Optional.empty();
        }
        return recipients.computeIfAbsent(binding.id(), id -> {
            try {
                return Optional.of(library.compileString(Objects.requireNonNull(binding.recipientExpression())));
            } catch (RuleCompilationException e) {
                return Optional.empty();
            }
        });
    }
}
