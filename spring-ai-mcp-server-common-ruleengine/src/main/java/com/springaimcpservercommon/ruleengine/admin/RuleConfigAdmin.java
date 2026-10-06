package com.springaimcpservercommon.ruleengine.admin;

import com.springaimcpservercommon.core.id.Ids;
import com.springaimcpservercommon.ruleengine.admin.AdminException.Conflict;
import com.springaimcpservercommon.ruleengine.admin.AdminException.Invalid;
import com.springaimcpservercommon.ruleengine.admin.AdminException.NotFound;
import com.springaimcpservercommon.ruleengine.cel.ParameterLibrary;
import com.springaimcpservercommon.ruleengine.cel.RuleCompilationException;
import com.springaimcpservercommon.ruleengine.channel.ApiCheck;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentClassifier;
import com.springaimcpservercommon.ruleengine.channel.ApiEnvironmentPolicy;
import com.springaimcpservercommon.ruleengine.model.ApiEnvironment;
import com.springaimcpservercommon.ruleengine.model.ChannelTrigger;
import com.springaimcpservercommon.ruleengine.model.ChannelType;
import com.springaimcpservercommon.ruleengine.model.DataType;
import com.springaimcpservercommon.ruleengine.model.OwnerType;
import com.springaimcpservercommon.ruleengine.model.TriggerType;
import com.springaimcpservercommon.ruleengine.store.JdbcRunner;
import com.springaimcpservercommon.ruleengine.store.JdbcRunner.StoreFailure;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static com.springaimcpservercommon.ruleengine.store.JdbcRunner.query;
import static com.springaimcpservercommon.ruleengine.store.JdbcRunner.queryOne;
import static com.springaimcpservercommon.ruleengine.store.JdbcRunner.update;

/**
 * Authoring of everything around rules that is not versioned: the platform-wide parameter library (modules, sys
 * objects and attributes, platform message bundles), and a tenant's message bundles, e-mail templates, API endpoints,
 * outcome channels and trigger points (OQ-65).
 *
 * <p>Tenant-owned things are always addressed with the tenant id and another tenant's rows read as "not found". Library
 * changes that could break live rules (deactivating or retyping a parameter) are refused while an active rule reads it.
 * API endpoints go through the {@link ApiEnvironmentPolicy}: the environment is derived from the URL by the
 * {@link ApiEnvironmentClassifier}, an endpoint of another environment is rejected, and one that cannot be classified
 * needs an explicit, recorded confirmation (the "are you sure this is the right API" pop-up).
 */
public final class RuleConfigAdmin {

    // ---- views and inputs ---------------------------------------------------------------------------------------

    /** A module. */
    public record ModuleView(UUID id, String code, String name, @Nullable String description, boolean active) {
    }

    /** A parameter. */
    public record AttributeView(UUID id, String code, String celName, String name, @Nullable String description,
                                DataType dataType, boolean required, @Nullable String sampleValue, boolean active) {
    }

    /** A sys object with its attributes. */
    public record ObjectView(UUID id, String code, String name, @Nullable String description, @Nullable String moduleCode,
                             boolean active, List<AttributeView> attributes) {
    }

    /** A message bundle with all its translations. */
    public record BundleView(UUID id, @Nullable UUID tenantId, String code, @Nullable String description,
                             Map<String, String> texts) {
    }

    /** An e-mail template as the caller's mail system knows it. */
    public record EmailTemplateView(UUID id, String templateRef, String name, @Nullable String description, boolean active) {
    }

    /** An API endpoint. */
    public record ApiEndpointView(UUID id, String name, String method, String url, ApiEnvironment environment,
                                  int timeoutMs, @Nullable String authSecretRef, @Nullable String externalConfirmedBy,
                                  @Nullable Instant externalConfirmedAt, boolean active) {
    }

    /** What the API environment guard says about a URL. */
    public record ApiCheckView(ApiEnvironment environment, String verdict, String reasonKey, String message) {
    }

    /** An outcome channel. */
    public record ChannelView(UUID id, OwnerType ownerType, UUID ownerId, ChannelTrigger onResult, ChannelType channelType,
                              int sequence, boolean enabled, @Nullable UUID emailTemplateId, @Nullable UUID apiEndpointId,
                              @Nullable UUID pushTitleBundleId, @Nullable UUID pushBodyBundleId,
                              @Nullable String recipientExpression) {
    }

    /** Input for a channel. */
    public record ChannelInput(OwnerType ownerType, UUID ownerId, ChannelTrigger onResult, ChannelType channelType,
                               int sequence, boolean enabled, @Nullable UUID emailTemplateId,
                               @Nullable UUID apiEndpointId, @Nullable UUID pushTitleBundleId,
                               @Nullable UUID pushBodyBundleId, @Nullable String recipientExpression) {
    }

    /** A trigger point. */
    public record TriggerView(UUID id, @Nullable UUID organizationId, String application, String moduleCode,
                              TriggerType triggerType, String formCode, String actionCode, @Nullable String fieldCode,
                              UUID ruleGroupId, int sequence, boolean enabled) {
    }

    /** Input for a trigger point. */
    public record TriggerInput(@Nullable UUID organizationId, String application, String moduleCode,
                               TriggerType triggerType, String formCode, String actionCode, @Nullable String fieldCode,
                               UUID ruleGroupId, int sequence, boolean enabled) {
    }

    /** A logged evaluation (no input values exist to show). */
    public record EvaluationView(UUID id, Instant evaluatedAt, UUID ruleGroupId, @Nullable UUID triggerPointId,
                                 String policy, String decision, @Nullable String language, long durationMicros) {
    }

    // ---- state -------------------------------------------------------------------------------------------------

    private final JdbcRunner jdbc;
    private final Supplier<ParameterLibrary> library;
    private final ApiEnvironmentPolicy apiPolicy;
    private final ApiEnvironmentClassifier classifier;

    /**
     * Creates the service.
     *
     * @param dataSource data source (not closed)
     * @param schema     schema holding the tables
     * @param library    current parameter library (validates recipient expressions)
     * @param apiPolicy  API environment guard
     * @param classifier derives an endpoint's environment from its URL
     */
    public RuleConfigAdmin(DataSource dataSource, String schema, Supplier<ParameterLibrary> library,
                           ApiEnvironmentPolicy apiPolicy, ApiEnvironmentClassifier classifier) {
        this.jdbc = new JdbcRunner(dataSource, schema);
        this.library = Objects.requireNonNull(library, "library");
        this.apiPolicy = Objects.requireNonNull(apiPolicy, "apiPolicy");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    // ---- modules ------------------------------------------------------------------------------------------------

    /**
     * All modules.
     *
     * @return modules ordered by code
     */
    public List<ModuleView> modules() {
        return jdbc.read(c -> query(c, "SELECT id, code, name, description, active FROM dai_re_module ORDER BY code",
                rs -> new ModuleView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5))));
    }

    /**
     * Creates a module.
     *
     * @param code        upper-case code, for example {@code LOAN}
     * @param name        display name
     * @param description free text
     * @return the module
     */
    public ModuleView createModule(String code, String name, @Nullable String description) {
        requireText("name", name, 200);
        return jdbc.write(c -> {
            UUID id = Ids.newId();
            insert(c, "module " + code, "INSERT INTO dai_re_module (id, code, name, description) VALUES (?, ?, ?, ?)",
                    id, code, name, description);
            return new ModuleView(id, code, name, description, true);
        });
    }

    /**
     * Activates or deactivates a module (inactive modules take their groups out of the engine's view).
     *
     * @param code   module code
     * @param active new state
     */
    public void setModuleActive(String code, boolean active) {
        jdbc.write(c -> {
            if (update(c, "UPDATE dai_re_module SET active = ? WHERE code = ?", active, code) == 0) {
                throw new NotFound("module");
            }
            return null;
        });
    }

    // ---- parameter library --------------------------------------------------------------------------------------

    /**
     * The parameter library: every sys object with its attributes.
     *
     * @return objects ordered by code
     */
    public List<ObjectView> objects() {
        return jdbc.read(c -> {
            Map<UUID, List<AttributeView>> attributes = new LinkedHashMap<>();
            query(c, "SELECT a.id, a.object_id, a.code, o.code, a.name, a.description, a.data_type, a.required,"
                    + " a.sample_value, a.active FROM dai_re_sys_object_attribute a JOIN dai_re_sys_object o ON o.id = a.object_id"
                    + " ORDER BY o.code, a.code", rs -> attributes.computeIfAbsent(rs.getObject(2, UUID.class),
                    k -> new ArrayList<>()).add(new AttributeView(rs.getObject(1, UUID.class), rs.getString(3),
                    rs.getString(4) + "." + rs.getString(3), rs.getString(5), rs.getString(6),
                    DataType.valueOf(rs.getString(7)), rs.getBoolean(8), rs.getString(9), rs.getBoolean(10))));
            return query(c, "SELECT o.id, o.code, o.name, o.description, m.code, o.active FROM dai_re_sys_object o"
                    + " LEFT JOIN dai_re_module m ON m.id = o.module_id ORDER BY o.code",
                    rs -> new ObjectView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getBoolean(6),
                            attributes.getOrDefault(rs.getObject(1, UUID.class), List.of())));
        });
    }

    /**
     * Creates a sys object.
     *
     * @param code        CEL identifier starting with a lower-case letter, for example {@code customer}
     * @param name        display name
     * @param description free text
     * @param moduleCode  module the object belongs to, or {@code null} = shared
     * @return its id
     */
    public UUID createObject(String code, String name, @Nullable String description, @Nullable String moduleCode) {
        requireText("name", name, 200);
        return jdbc.write(c -> {
            UUID id = Ids.newId();
            insert(c, "object " + code, "INSERT INTO dai_re_sys_object (id, code, name, description, module_id) VALUES (?, ?, ?, ?, ?)",
                    id, code, name, description, moduleCode == null ? null : moduleId(c, moduleCode));
            return id;
        });
    }

    /**
     * Updates a sys object. Deactivating one is refused while an active rule reads any of its attributes.
     *
     * @param id          object id
     * @param name        display name
     * @param description free text
     * @param moduleCode  module or {@code null}
     * @param active      new state
     */
    public void updateObject(UUID id, String name, @Nullable String description, @Nullable String moduleCode,
                             boolean active) {
        requireText("name", name, 200);
        jdbc.write(c -> {
            if (!active) {
                List<String> users = query(c, "SELECT DISTINCT r.code FROM dai_re_rule_parameter p JOIN dai_re_sys_object_attribute a"
                        + " ON a.id = p.attribute_id JOIN dai_re_rule r ON r.id = p.rule_id WHERE a.object_id = ? AND r.status <> 'RETIRED'"
                        + " ORDER BY r.code", rs -> rs.getString(1), id);
                if (!users.isEmpty()) {
                    throw new Conflict("parameter_in_use", "rules read attributes of this object", users);
                }
            }
            if (update(c, "UPDATE dai_re_sys_object SET name = ?, description = ?, module_id = ?, active = ? WHERE id = ?",
                    name, description, moduleCode == null ? null : moduleId(c, moduleCode), active, id) == 0) {
                throw new NotFound("object");
            }
            return null;
        });
    }

    /**
     * Adds an attribute to a sys object; {@code object.code + "." + code} becomes a CEL variable.
     *
     * @param objectId    object
     * @param code        attribute code (CEL identifier)
     * @param name        display name
     * @param description free text
     * @param dataType    declared type
     * @param required    callers are expected to send it
     * @param sampleValue example for UIs
     * @return its id
     */
    public UUID createAttribute(UUID objectId, String code, String name, @Nullable String description, DataType dataType,
                                boolean required, @Nullable String sampleValue) {
        requireText("name", name, 200);
        return jdbc.write(c -> {
            if (queryOne(c, "SELECT 1 FROM dai_re_sys_object WHERE id = ?", rs -> 1, objectId) == null) {
                throw new NotFound("object");
            }
            UUID id = Ids.newId();
            insert(c, "attribute " + code, "INSERT INTO dai_re_sys_object_attribute (id, object_id, code, name, description,"
                    + " data_type, required, sample_value) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    id, objectId, code, name, description, dataType.name(), required, sampleValue);
            return id;
        });
    }

    /**
     * Updates an attribute. Changing its type or deactivating it is refused while an active rule reads it.
     *
     * @param id          attribute id
     * @param name        display name
     * @param description free text
     * @param dataType    declared type
     * @param required    callers are expected to send it
     * @param sampleValue example for UIs
     * @param active      new state
     */
    public void updateAttribute(UUID id, String name, @Nullable String description, DataType dataType, boolean required,
                                @Nullable String sampleValue, boolean active) {
        requireText("name", name, 200);
        jdbc.write(c -> {
            String[] current = queryOne(c, "SELECT data_type, active::text FROM dai_re_sys_object_attribute WHERE id = ? FOR UPDATE",
                    rs -> new String[]{rs.getString(1), rs.getString(2)}, id);
            if (current == null) {
                throw new NotFound("attribute");
            }
            boolean typeChanged = !current[0].equals(dataType.name());
            boolean deactivated = current[1].equals("true") && !active;
            if (typeChanged || deactivated) {
                List<String> users = usage(c, id);
                if (!users.isEmpty()) {
                    throw new Conflict("parameter_in_use", "rules read this attribute; retire them first", users);
                }
            }
            update(c, "UPDATE dai_re_sys_object_attribute SET name = ?, description = ?, data_type = ?, required = ?,"
                    + " sample_value = ?, active = ? WHERE id = ?", name, description, dataType.name(), required,
                    sampleValue, active, id);
            return null;
        });
    }

    /**
     * Impact analysis: the active rules that read an attribute.
     *
     * @param attributeId attribute
     * @return rule codes
     */
    public List<String> parameterUsage(UUID attributeId) {
        return jdbc.read(c -> usage(c, attributeId));
    }

    private static List<String> usage(Connection c, UUID attributeId) throws SQLException {
        return query(c, "SELECT DISTINCT r.code FROM dai_re_rule_parameter p JOIN dai_re_rule r ON r.id = p.rule_id"
                + " WHERE p.attribute_id = ? AND r.status <> 'RETIRED' ORDER BY r.code", rs -> rs.getString(1), attributeId);
    }

    // ---- bundles ------------------------------------------------------------------------------------------------

    /**
     * Bundles visible to a tenant: the platform's and its own.
     *
     * @param tenantId tenant
     * @return bundles ordered by code
     */
    public List<BundleView> bundles(UUID tenantId) {
        return jdbc.read(c -> loadBundles(c, "WHERE b.tenant_id IS NULL OR b.tenant_id = ?", tenantId));
    }

    /**
     * Platform bundles only (the global administrator's view).
     *
     * @return bundles ordered by code
     */
    public List<BundleView> platformBundles() {
        return jdbc.read(c -> loadBundles(c, "WHERE b.tenant_id IS NULL"));
    }

    private static List<BundleView> loadBundles(Connection c, String where, @Nullable Object... params) throws SQLException {
        Map<UUID, Map<String, String>> texts = new LinkedHashMap<>();
        query(c, "SELECT m.bundle_id, m.language, m.message_text FROM dai_re_sys_bundle_message m"
                + " JOIN dai_re_sys_bundle b ON b.id = m.bundle_id " + where + " ORDER BY m.bundle_id, m.language",
                rs -> texts.computeIfAbsent(rs.getObject(1, UUID.class), k -> new LinkedHashMap<>())
                        .put(rs.getString(2), rs.getString(3)), params);
        return query(c, "SELECT b.id, b.tenant_id, b.code, b.description FROM dai_re_sys_bundle b " + where + " ORDER BY b.code",
                rs -> new BundleView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), texts.getOrDefault(rs.getObject(1, UUID.class), Map.of())), params);
    }

    /**
     * Creates a bundle with its translations.
     *
     * @param tenantId    owner tenant, or {@code null} for a platform bundle (global administrators only)
     * @param code        bundle code, unique per owner
     * @param description free text
     * @param texts       language tag → text (at least one)
     * @return the bundle
     */
    public BundleView createBundle(@Nullable UUID tenantId, String code, @Nullable String description,
                                   Map<String, String> texts) {
        if (texts.isEmpty()) {
            throw new Invalid("invalid_bundle", "texts: at least one language is required");
        }
        return jdbc.write(c -> {
            UUID id = Ids.newId();
            insert(c, "bundle " + code, "INSERT INTO dai_re_sys_bundle (id, tenant_id, code, description) VALUES (?, ?, ?, ?)",
                    id, tenantId, code, description);
            for (Map.Entry<String, String> t : texts.entrySet()) {
                putTextIn(c, id, t.getKey(), t.getValue());
            }
            return new BundleView(id, tenantId, code, description, new LinkedHashMap<>(texts));
        });
    }

    /**
     * Sets or replaces one translation.
     *
     * @param scope    {@code null} for a platform bundle, otherwise the owning tenant
     * @param bundleId bundle
     * @param language BCP 47 tag, for example {@code hi} or {@code pt-BR}
     * @param text     the text
     */
    public void putText(@Nullable UUID scope, UUID bundleId, String language, String text) {
        jdbc.write(c -> {
            requireBundle(c, scope, bundleId);
            putTextIn(c, bundleId, language, text);
            return null;
        });
    }

    /**
     * Removes one translation; the last one cannot be removed.
     *
     * @param scope    {@code null} for a platform bundle, otherwise the owning tenant
     * @param bundleId bundle
     * @param language tag to remove
     */
    public void deleteText(@Nullable UUID scope, UUID bundleId, String language) {
        jdbc.write(c -> {
            requireBundle(c, scope, bundleId);
            long count = queryOne(c, "SELECT count(*) FROM dai_re_sys_bundle_message WHERE bundle_id = ?", rs -> rs.getLong(1), bundleId);
            if (count <= 1) {
                throw new Conflict("last_text", "a bundle needs at least one language", List.of());
            }
            if (update(c, "DELETE FROM dai_re_sys_bundle_message WHERE bundle_id = ? AND language = ?", bundleId, language) == 0) {
                throw new NotFound("translation");
            }
            return null;
        });
    }

    /**
     * Deletes a bundle that no rule, group or channel uses.
     *
     * @param scope    {@code null} for a platform bundle, otherwise the owning tenant
     * @param bundleId bundle
     */
    public void deleteBundle(@Nullable UUID scope, UUID bundleId) {
        jdbc.write(c -> {
            requireBundle(c, scope, bundleId);
            try {
                update(c, "DELETE FROM dai_re_sys_bundle WHERE id = ?", bundleId);
            } catch (SQLException e) {
                if ("23503".equals(e.getSQLState())) {
                    throw new Conflict("bundle_in_use", "rules, groups or channels still use this bundle", List.of());
                }
                throw new StoreFailure(e.getSQLState(), e.getMessage(), e);
            }
            return null;
        });
    }

    private static void requireBundle(Connection c, @Nullable UUID scope, UUID bundleId) throws SQLException {
        if (queryOne(c, "SELECT 1 FROM dai_re_sys_bundle WHERE id = ? AND tenant_id IS NOT DISTINCT FROM ? FOR UPDATE",
                rs -> 1, bundleId, scope) == null) {
            throw new NotFound("bundle");
        }
    }

    private static void putTextIn(Connection c, UUID bundleId, String language, String text) throws SQLException {
        if (language == null || !language.matches("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")) {
            throw new Invalid("invalid_bundle", "language: BCP 47 tag such as en, hi, th or pt-BR");
        }
        requireText("text", text, 2000);
        update(c, "INSERT INTO dai_re_sys_bundle_message (bundle_id, language, message_text) VALUES (?, ?, ?)"
                + " ON CONFLICT (bundle_id, language) DO UPDATE SET message_text = EXCLUDED.message_text", bundleId, language, text);
    }

    // ---- e-mail templates ---------------------------------------------------------------------------------------

    /**
     * A tenant's e-mail templates.
     *
     * @param tenantId tenant
     * @return templates ordered by caller-side id
     */
    public List<EmailTemplateView> emailTemplates(UUID tenantId) {
        return jdbc.read(c -> query(c, "SELECT id, template_ref, name, description, active FROM dai_re_email_template"
                + " WHERE tenant_id = ? ORDER BY template_ref", rs -> new EmailTemplateView(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)), tenantId));
    }

    /**
     * Creates or updates an e-mail template.
     *
     * @param tenantId    tenant
     * @param id          existing template, or {@code null} to create
     * @param templateRef the id the caller's mail system uses
     * @param name        display name
     * @param description free text
     * @param active      whether channels may use it
     * @return the template
     */
    public EmailTemplateView saveEmailTemplate(UUID tenantId, @Nullable UUID id, String templateRef, String name,
                                               @Nullable String description, boolean active) {
        requireText("templateRef", templateRef, 200);
        requireText("name", name, 200);
        return jdbc.write(c -> {
            UUID target = id != null ? id : Ids.newId();
            if (id == null) {
                insert(c, "template " + templateRef, "INSERT INTO dai_re_email_template (id, tenant_id, template_ref, name,"
                        + " description, active) VALUES (?, ?, ?, ?, ?, ?)", target, tenantId, templateRef, name, description, active);
            } else if (update(c, "UPDATE dai_re_email_template SET template_ref = ?, name = ?, description = ?, active = ?"
                    + " WHERE id = ? AND tenant_id = ?", templateRef, name, description, active, id, tenantId) == 0) {
                throw new NotFound("e-mail template");
            }
            return new EmailTemplateView(target, templateRef, name, description, active);
        });
    }

    // ---- API endpoints ------------------------------------------------------------------------------------------

    /**
     * A tenant's API endpoints.
     *
     * @param tenantId tenant
     * @return endpoints ordered by name
     */
    public List<ApiEndpointView> apiEndpoints(UUID tenantId) {
        return jdbc.read(c -> query(c, "SELECT id, name, http_method, url, environment, timeout_ms, auth_secret_ref,"
                + " external_confirmed_by, external_confirmed_at, active FROM dai_re_api_endpoint WHERE tenant_id = ?"
                + " ORDER BY name", RuleConfigAdmin::mapEndpoint, tenantId));
    }

    /**
     * What the guard says about a URL before it is saved: which environment it belongs to and whether the user must
     * confirm it. Drives the confirmation pop-up.
     *
     * @param url endpoint URL
     * @return environment and verdict
     */
    public ApiCheckView checkApiUrl(String url) {
        ApiEnvironment env = classifier.classify(url);
        ApiCheck check = apiPolicy.checkForSave(env, false);
        return new ApiCheckView(env, check.verdict().name(), check.reasonKey(), check.message());
    }

    /**
     * Creates or updates an API endpoint. The environment comes from the URL; an endpoint of another environment is
     * rejected ({@code api_environment_mismatch}); one that cannot be classified is refused with
     * {@code confirmation_required} (HTTP 409) until the caller repeats the request with {@code confirmExternal=true},
     * which records who confirmed and when.
     *
     * @param tenantId        tenant
     * @param id              existing endpoint, or {@code null} to create
     * @param name            display name
     * @param method          POST, PUT, PATCH or GET
     * @param url             absolute http(s) URL
     * @param timeoutMs       per-call timeout (100..30000)
     * @param authSecretRef   reference the host resolves to a credential (never the secret)
     * @param confirmExternal the user confirmed the "external API" pop-up
     * @param actor           who is saving
     * @param active          whether channels may use it
     * @return the endpoint
     */
    public ApiEndpointView saveApiEndpoint(UUID tenantId, @Nullable UUID id, String name, String method, String url,
                                           int timeoutMs, @Nullable String authSecretRef, boolean confirmExternal,
                                           String actor, boolean active) {
        requireText("name", name, 200);
        if (url == null || !url.matches("^https?://[^\\s]+$")) {
            throw new Invalid("invalid_url", "url: absolute http(s) URL required");
        }
        ApiEnvironment env = classifier.classify(url);
        ApiCheck check = apiPolicy.checkForSave(env, confirmExternal);
        switch (check.verdict()) {
            case CONFIRMATION_REQUIRED -> throw new Conflict("confirmation_required", check.message(), List.of(check.reasonKey()));
            case REJECTED_ENVIRONMENT_MISMATCH -> throw new Invalid("api_environment_mismatch", check.message());
            case ALLOWED -> { }
        }
        boolean external = env == ApiEnvironment.EXTERNAL;
        return jdbc.write(c -> {
            UUID target = id != null ? id : Ids.newId();
            try {
                if (id == null) {
                    update(c, "INSERT INTO dai_re_api_endpoint (id, tenant_id, name, http_method, url, environment, timeout_ms,"
                            + " auth_secret_ref, external_confirmed_by, external_confirmed_at, active)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, " + (external ? "now()" : "NULL") + ", ?)",
                            target, tenantId, name, method, url, env.name(), timeoutMs, authSecretRef,
                            external ? actor : null, active);
                } else if (update(c, "UPDATE dai_re_api_endpoint SET name = ?, http_method = ?, url = ?, environment = ?,"
                        + " timeout_ms = ?, auth_secret_ref = ?, external_confirmed_by = ?, external_confirmed_at = "
                        + (external ? "now()" : "NULL") + ", active = ? WHERE id = ? AND tenant_id = ?",
                        name, method, url, env.name(), timeoutMs, authSecretRef, external ? actor : null, active, id,
                        tenantId) == 0) {
                    throw new NotFound("API endpoint");
                }
            } catch (SQLException e) {
                throw mapConstraint(e, "endpoint " + name);
            }
            return queryOne(c, "SELECT id, name, http_method, url, environment, timeout_ms, auth_secret_ref,"
                    + " external_confirmed_by, external_confirmed_at, active FROM dai_re_api_endpoint WHERE id = ?",
                    RuleConfigAdmin::mapEndpoint, target);
        });
    }

    private static ApiEndpointView mapEndpoint(java.sql.ResultSet rs) throws SQLException {
        java.sql.Timestamp at = rs.getTimestamp(9);
        return new ApiEndpointView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                ApiEnvironment.valueOf(rs.getString(5)), rs.getInt(6), rs.getString(7), rs.getString(8),
                at == null ? null : at.toInstant(), rs.getBoolean(10));
    }

    // ---- channels -----------------------------------------------------------------------------------------------

    /**
     * Channels of a tenant, optionally of one owner.
     *
     * @param tenantId  tenant
     * @param ownerType rule or group filter, or {@code null}
     * @param ownerId   owner filter, or {@code null}
     * @return channels ordered by owner and sequence
     */
    public List<ChannelView> channels(UUID tenantId, @Nullable OwnerType ownerType, @Nullable UUID ownerId) {
        return jdbc.read(c -> query(c, "SELECT id, owner_type, owner_id, on_result, channel_type, sequence, enabled,"
                + " email_template_id, api_endpoint_id, push_title_bundle_id, push_body_bundle_id, recipient_expression"
                + " FROM dai_re_outcome_channel WHERE tenant_id = ? AND (?::text IS NULL OR owner_type = ?)"
                + " AND (?::uuid IS NULL OR owner_id = ?) ORDER BY owner_id, sequence, id",
                RuleConfigAdmin::mapChannel, tenantId, ownerType == null ? null : ownerType.name(),
                ownerType == null ? null : ownerType.name(), ownerId, ownerId));
    }

    /**
     * Creates or updates a channel binding. The owner must exist in the tenant, the referenced template / endpoint /
     * bundles must exist, and the recipient expression must compile to a string.
     *
     * @param tenantId tenant
     * @param id       existing binding, or {@code null} to create
     * @param in       the binding
     * @return the binding
     */
    public ChannelView saveChannel(UUID tenantId, @Nullable UUID id, ChannelInput in) {
        List<String> problems = new ArrayList<>();
        switch (in.channelType()) {
            case EMAIL -> {
                if (in.emailTemplateId() == null) {
                    problems.add("emailTemplateId: required for EMAIL");
                }
                if (in.apiEndpointId() != null || in.pushBodyBundleId() != null || in.pushTitleBundleId() != null) {
                    problems.add("an EMAIL channel only takes emailTemplateId");
                }
            }
            case API -> {
                if (in.apiEndpointId() == null) {
                    problems.add("apiEndpointId: required for API");
                }
                if (in.emailTemplateId() != null || in.pushBodyBundleId() != null || in.pushTitleBundleId() != null) {
                    problems.add("an API channel only takes apiEndpointId");
                }
            }
            case PUSH -> {
                if (in.pushBodyBundleId() == null) {
                    problems.add("pushBodyBundleId: required for PUSH");
                }
                if (in.emailTemplateId() != null || in.apiEndpointId() != null) {
                    problems.add("a PUSH channel only takes push bundles");
                }
            }
        }
        if (in.channelType() != ChannelType.API) {
            if (in.recipientExpression() == null || in.recipientExpression().isBlank()) {
                problems.add("recipientExpression: required for EMAIL and PUSH");
            } else {
                try {
                    library.get().compileString(in.recipientExpression());
                } catch (RuleCompilationException e) {
                    problems.add("recipientExpression: " + e.getMessage());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new Invalid("invalid_channel", problems);
        }
        return jdbc.write(c -> {
            String ownerTable = in.ownerType() == OwnerType.RULE ? "dai_re_rule" : "dai_re_rule_group";
            if (queryOne(c, "SELECT 1 FROM " + ownerTable + " WHERE id = ? AND tenant_id = ?", rs -> 1, in.ownerId(), tenantId) == null) {
                throw new Invalid("invalid_channel", "ownerId: no such " + in.ownerType().name().toLowerCase() + " in this tenant");
            }
            if (in.emailTemplateId() != null && queryOne(c, "SELECT 1 FROM dai_re_email_template WHERE id = ? AND tenant_id = ? AND active",
                    rs -> 1, in.emailTemplateId(), tenantId) == null) {
                throw new Invalid("invalid_channel", "emailTemplateId: no such active template");
            }
            if (in.apiEndpointId() != null && queryOne(c, "SELECT 1 FROM dai_re_api_endpoint WHERE id = ? AND tenant_id = ? AND active",
                    rs -> 1, in.apiEndpointId(), tenantId) == null) {
                throw new Invalid("invalid_channel", "apiEndpointId: no such active endpoint");
            }
            for (UUID bundle : new UUID[]{in.pushTitleBundleId(), in.pushBodyBundleId()}) {
                if (bundle != null && queryOne(c, "SELECT 1 FROM dai_re_sys_bundle WHERE id = ? AND (tenant_id IS NULL OR tenant_id = ?)",
                        rs -> 1, bundle, tenantId) == null) {
                    throw new Invalid("invalid_channel", "bundle " + bundle + " does not exist");
                }
            }
            UUID target = id != null ? id : Ids.newId();
            try {
                if (id == null) {
                    update(c, "INSERT INTO dai_re_outcome_channel (id, tenant_id, owner_type, owner_id, on_result, channel_type,"
                            + " sequence, enabled, email_template_id, api_endpoint_id, push_title_bundle_id, push_body_bundle_id,"
                            + " recipient_expression) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            target, tenantId, in.ownerType().name(), in.ownerId(), in.onResult().name(), in.channelType().name(),
                            in.sequence(), in.enabled(), in.emailTemplateId(), in.apiEndpointId(), in.pushTitleBundleId(),
                            in.pushBodyBundleId(), in.recipientExpression());
                } else if (update(c, "UPDATE dai_re_outcome_channel SET owner_type = ?, owner_id = ?, on_result = ?,"
                        + " channel_type = ?, sequence = ?, enabled = ?, email_template_id = ?, api_endpoint_id = ?,"
                        + " push_title_bundle_id = ?, push_body_bundle_id = ?, recipient_expression = ?"
                        + " WHERE id = ? AND tenant_id = ?", in.ownerType().name(), in.ownerId(), in.onResult().name(),
                        in.channelType().name(), in.sequence(), in.enabled(), in.emailTemplateId(), in.apiEndpointId(),
                        in.pushTitleBundleId(), in.pushBodyBundleId(), in.recipientExpression(), id, tenantId) == 0) {
                    throw new NotFound("channel");
                }
            } catch (SQLException e) {
                throw mapConstraint(e, "channel");
            }
            return queryOne(c, "SELECT id, owner_type, owner_id, on_result, channel_type, sequence, enabled, email_template_id,"
                    + " api_endpoint_id, push_title_bundle_id, push_body_bundle_id, recipient_expression"
                    + " FROM dai_re_outcome_channel WHERE id = ?", RuleConfigAdmin::mapChannel, target);
        });
    }

    /**
     * Deletes a channel binding.
     *
     * @param tenantId tenant
     * @param id       binding
     */
    public void deleteChannel(UUID tenantId, UUID id) {
        jdbc.write(c -> {
            if (update(c, "DELETE FROM dai_re_outcome_channel WHERE id = ? AND tenant_id = ?", id, tenantId) == 0) {
                throw new NotFound("channel");
            }
            return null;
        });
    }

    private static ChannelView mapChannel(java.sql.ResultSet rs) throws SQLException {
        return new ChannelView(rs.getObject(1, UUID.class), OwnerType.valueOf(rs.getString(2)), rs.getObject(3, UUID.class),
                ChannelTrigger.valueOf(rs.getString(4)), ChannelType.valueOf(rs.getString(5)), rs.getInt(6), rs.getBoolean(7),
                rs.getObject(8, UUID.class), rs.getObject(9, UUID.class), rs.getObject(10, UUID.class),
                rs.getObject(11, UUID.class), rs.getString(12));
    }

    // ---- trigger points -----------------------------------------------------------------------------------------

    /**
     * Trigger points of a tenant.
     *
     * @param tenantId    tenant
     * @param application application filter, or {@code null}
     * @return triggers ordered by application, form, action and sequence
     */
    public List<TriggerView> triggers(UUID tenantId, @Nullable String application) {
        return jdbc.read(c -> query(c, "SELECT t.id, t.organization_id, t.application, m.code, t.trigger_type, t.form_code,"
                + " t.action_code, t.field_code, t.rule_group_id, t.sequence, t.enabled FROM dai_re_trigger_point t"
                + " JOIN dai_re_module m ON m.id = t.module_id WHERE t.tenant_id = ? AND (?::text IS NULL OR t.application = ?)"
                + " ORDER BY t.application, t.form_code, t.action_code, t.sequence",
                rs -> new TriggerView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        TriggerType.valueOf(rs.getString(5)), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getObject(9, UUID.class), rs.getInt(10), rs.getBoolean(11)), tenantId, application, application));
    }

    /**
     * Creates or updates a trigger point. The group must exist in the tenant and belong to the trigger's module.
     *
     * @param tenantId tenant
     * @param id       existing trigger, or {@code null} to create
     * @param in       the trigger
     * @return the trigger
     */
    public TriggerView saveTrigger(UUID tenantId, @Nullable UUID id, TriggerInput in) {
        if ((in.triggerType() == TriggerType.FORM_FIELD) != (in.fieldCode() != null)) {
            throw new Invalid("invalid_trigger", "fieldCode: required for FORM_FIELD and not allowed for FORM_ACTION");
        }
        return jdbc.write(c -> {
            UUID moduleId = moduleId(c, in.moduleCode());
            UUID groupModule = queryOne(c, "SELECT module_id FROM dai_re_rule_group WHERE id = ? AND tenant_id = ?",
                    rs -> rs.getObject(1, UUID.class), in.ruleGroupId(), tenantId);
            if (groupModule == null) {
                throw new Invalid("invalid_trigger", "ruleGroupId: no such group in this tenant");
            }
            if (!groupModule.equals(moduleId)) {
                throw new Invalid("invalid_trigger", "ruleGroupId: the group belongs to another module");
            }
            UUID target = id != null ? id : Ids.newId();
            try {
                if (id == null) {
                    update(c, "INSERT INTO dai_re_trigger_point (id, tenant_id, organization_id, application, module_id, trigger_type,"
                            + " form_code, action_code, field_code, rule_group_id, sequence, enabled)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", target, tenantId, in.organizationId(),
                            in.application(), moduleId, in.triggerType().name(), in.formCode(), in.actionCode(),
                            in.fieldCode(), in.ruleGroupId(), in.sequence(), in.enabled());
                } else if (update(c, "UPDATE dai_re_trigger_point SET organization_id = ?, application = ?, module_id = ?,"
                        + " trigger_type = ?, form_code = ?, action_code = ?, field_code = ?, rule_group_id = ?, sequence = ?,"
                        + " enabled = ? WHERE id = ? AND tenant_id = ?", in.organizationId(), in.application(), moduleId,
                        in.triggerType().name(), in.formCode(), in.actionCode(), in.fieldCode(), in.ruleGroupId(),
                        in.sequence(), in.enabled(), id, tenantId) == 0) {
                    throw new NotFound("trigger");
                }
            } catch (SQLException e) {
                throw mapConstraint(e, "trigger");
            }
            return new TriggerView(target, in.organizationId(), in.application(), in.moduleCode(), in.triggerType(),
                    in.formCode(), in.actionCode(), in.fieldCode(), in.ruleGroupId(), in.sequence(), in.enabled());
        });
    }

    /**
     * Deletes a trigger point.
     *
     * @param tenantId tenant
     * @param id       trigger
     */
    public void deleteTrigger(UUID tenantId, UUID id) {
        jdbc.write(c -> {
            if (update(c, "DELETE FROM dai_re_trigger_point WHERE id = ? AND tenant_id = ?", id, tenantId) == 0) {
                throw new NotFound("trigger");
            }
            return null;
        });
    }

    // ---- evaluation log -----------------------------------------------------------------------------------------

    /**
     * Recent evaluations of a tenant, newest first.
     *
     * @param tenantId tenant
     * @param groupId  group filter, or {@code null}
     * @param limit    most rows (1..500)
     * @return evaluation rows (decisions only; input values are never stored)
     */
    public List<EvaluationView> evaluations(UUID tenantId, @Nullable UUID groupId, int limit) {
        return jdbc.read(c -> query(c, "SELECT id, evaluated_at, rule_group_id, trigger_point_id, policy, decision, language,"
                + " duration_micros FROM dai_re_evaluation WHERE tenant_id = ? AND (?::uuid IS NULL OR rule_group_id = ?)"
                + " ORDER BY evaluated_at DESC LIMIT ?", rs -> new EvaluationView(rs.getObject(1, UUID.class),
                rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getLong(8)), tenantId, groupId, groupId, Math.min(Math.max(limit, 1), 500)));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static UUID moduleId(Connection c, String code) throws SQLException {
        UUID id = queryOne(c, "SELECT id FROM dai_re_module WHERE code = ? AND active", rs -> rs.getObject(1, UUID.class), code);
        if (id == null) {
            throw new Invalid("unknown_module", "module: " + code + " does not exist or is inactive");
        }
        return id;
    }

    private static void requireText(String field, @Nullable String value, int max) {
        if (value == null || value.isBlank()) {
            throw new Invalid("invalid_" + field, field + ": required");
        }
        if (value.length() > max) {
            throw new Invalid("invalid_" + field, field + ": longer than " + max + " characters");
        }
    }

    private static void insert(Connection c, String what, String sql, @Nullable Object... params) {
        try {
            update(c, sql, params);
        } catch (SQLException e) {
            throw mapConstraint(e, what);
        }
    }

    /** Unique / check / foreign-key violations become 409 / 422 answers instead of server errors. */
    private static RuntimeException mapConstraint(SQLException e, String what) {
        return switch (String.valueOf(e.getSQLState())) {
            case "23505" -> new Conflict("duplicate", what + " already exists", List.of());
            case "23514", "23502" -> new Invalid("invalid_value", what + ": " + constraint(e));
            case "23503" -> new Invalid("invalid_reference", what + ": refers to something that does not exist");
            default -> new StoreFailure(e.getSQLState(), e.getMessage(), e);
        };
    }

    private static String constraint(SQLException e) {
        String m = String.valueOf(e.getMessage());
        int i = m.indexOf("constraint \"");
        return i < 0 ? "value rejected" : "violates " + m.substring(i + 12, m.indexOf('"', i + 12));
    }
}
