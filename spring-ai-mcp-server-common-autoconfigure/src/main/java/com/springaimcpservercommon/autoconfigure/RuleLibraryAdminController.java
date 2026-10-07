package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ruleengine.admin.RuleConfigAdmin;
import com.springaimcpservercommon.ruleengine.model.DataType;
import com.springaimcpservercommon.security.permission.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The platform-wide parameter library (LLD-18, OQ-65): modules, sys objects and their attributes, and platform message
 * bundles. It is shared by every workspace, so it needs the <b>global</b> {@link Permission#RULES_LIBRARY} permission
 * (workspace roles never hold it) and the AUTHORING capability of the environment. Deactivating or retyping a
 * parameter that an active rule reads is refused with {@code parameter_in_use}. Changes are audited.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1/rule-library")
public final class RuleLibraryAdminController {

    /**
     * A module.
     *
     * @param code        upper-case code
     * @param name        display name
     * @param description free text
     */
    public record Module(String code, String name, @Nullable String description) {}

    /**
     * A module's state.
     *
     * @param active new state
     */
    public record Active(boolean active) {}

    /**
     * A sys object.
     *
     * @param code        CEL identifier
     * @param name        display name
     * @param description free text
     * @param moduleCode  module, or {@code null} = shared
     * @param active      default true (update only)
     */
    public record SysObject(String code, String name, @Nullable String description, @Nullable String moduleCode,
                            @Nullable Boolean active) {}

    /**
     * An attribute.
     *
     * @param code        CEL identifier
     * @param name        display name
     * @param description free text
     * @param dataType    declared type
     * @param required    callers are expected to send it
     * @param sampleValue example
     * @param active      default true (update only)
     */
    public record Attribute(String code, String name, @Nullable String description, DataType dataType,
                            @Nullable Boolean required, @Nullable String sampleValue, @Nullable Boolean active) {}

    /**
     * A platform bundle.
     *
     * @param code        bundle code
     * @param description free text
     * @param texts       language tag → text
     */
    public record Bundle(String code, @Nullable String description, Map<String, String> texts) {}

    /**
     * One translation.
     *
     * @param text the text
     */
    public record Text(String text) {}

    private final RuleConfigAdmin config;
    private final RuleAdminSupport support;

    RuleLibraryAdminController(RuleConfigAdmin config, RuleAdminSupport support) {
        this.config = Objects.requireNonNull(config, "config");
        this.support = Objects.requireNonNull(support, "support");
    }

    /**
     * Modules and sys objects with attributes.
     *
     * @param request current request
     * @return 200 with the library
     */
    @GetMapping
    public ResponseEntity<?> library(HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_LIBRARY, null);
        return gate.open() ? ResponseEntity.ok(Map.of("modules", config.modules(), "objects", config.objects())) : gate.denied();
    }

    /**
     * Creates a module.
     *
     * @param body    the module
     * @param request current request
     * @return 201 with the module
     */
    @PostMapping("/modules")
    public ResponseEntity<?> createModule(@RequestBody Module body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_LIBRARY, null);
        if (!gate.open()) {
            return gate.denied();
        }
        var m = config.createModule(body.code(), body.name(), body.description());
        support.audit(gate, "RULES_MODULE_CREATED", null, "rule_module", m.id().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(m);
    }

    /**
     * Activates or deactivates a module.
     *
     * @param code    module code
     * @param body    new state
     * @param request current request
     * @return 204
     */
    @PutMapping("/modules/{code}/active")
    public ResponseEntity<?> setModuleActive(@PathVariable String code, @RequestBody Active body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_LIBRARY, null);
        if (!gate.open()) {
            return gate.denied();
        }
        config.setModuleActive(code, body.active());
        support.audit(gate, "RULES_MODULE_STATE_SET", null, "rule_module", code, null, Map.of("active", body.active()));
        return ResponseEntity.noContent().build();
    }

    /**
     * Creates a sys object.
     *
     * @param body    the object
     * @param request current request
     * @return 201 with its id
     */
    @PostMapping("/objects")
    public ResponseEntity<?> createObject(@RequestBody SysObject body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_LIBRARY, null);
        if (!gate.open()) {
            return gate.denied();
        }
        UUID id = config.createObject(body.code(), body.name(), body.description(), body.moduleCode());
        support.audit(gate, "RULES_OBJECT_CREATED", null, "rule_sys_object", id.toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    /**
     * Updates a sys object.
     *
     * @param id      object
     * @param body    the object
     * @param request current request
     * @return 204; 409 {@code parameter_in_use} when deactivating one that active rules read
     */
    @PutMapping("/objects/{id}")
    public ResponseEntity<?> updateObject(@PathVariable UUID id, @RequestBody SysObject body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_LIBRARY, null);
        if (!gate.open()) {
            return gate.denied();
        }
        config.updateObject(id, body.name(), body.description(), body.moduleCode(), body.active() == null || body.active());
        support.audit(gate, "RULES_OBJECT_UPDATED", null, "rule_sys_object", id.toString());
        return ResponseEntity.noContent().build();
    }

    /**
     * Adds an attribute to a sys object; {@code object.attribute} becomes a CEL variable.
     *
     * @param objectId object
     * @param body     the attribute
     * @param request  current request
     * @return 201 with its id
     */
    @PostMapping("/objects/{objectId}/attributes")
    public ResponseEntity<?> createAttribute(@PathVariable UUID objectId, @RequestBody Attribute body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_LIBRARY, null);
        if (!gate.open()) {
            return gate.denied();
        }
        UUID id = config.createAttribute(objectId, body.code(), body.name(), body.description(), body.dataType(),
                Boolean.TRUE.equals(body.required()), body.sampleValue());
        support.audit(gate, "RULES_ATTRIBUTE_CREATED", null, "rule_sys_attribute", id.toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    /**
     * Updates an attribute (retyping or deactivating one that active rules read is a 409).
     *
     * @param id      attribute
     * @param body    the attribute
     * @param request current request
     * @return 204
     */
    @PutMapping("/attributes/{id}")
    public ResponseEntity<?> updateAttribute(@PathVariable UUID id, @RequestBody Attribute body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_LIBRARY, null);
        if (!gate.open()) {
            return gate.denied();
        }
        config.updateAttribute(id, body.name(), body.description(), body.dataType(), Boolean.TRUE.equals(body.required()),
                body.sampleValue(), body.active() == null || body.active());
        support.audit(gate, "RULES_ATTRIBUTE_UPDATED", null, "rule_sys_attribute", id.toString());
        return ResponseEntity.noContent().build();
    }

    /**
     * Impact analysis: the rules that read an attribute.
     *
     * @param id      attribute
     * @param request current request
     * @return 200 with rule codes
     */
    @GetMapping("/attributes/{id}/usage")
    public ResponseEntity<?> usage(@PathVariable UUID id, HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_LIBRARY, null);
        return gate.open() ? ResponseEntity.ok(config.parameterUsage(id)) : gate.denied();
    }

    /**
     * Platform message bundles.
     *
     * @param request current request
     * @return 200 with bundles
     */
    @GetMapping("/bundles")
    public ResponseEntity<?> bundles(HttpServletRequest request) {
        var gate = support.read(request, Permission.RULES_LIBRARY, null);
        return gate.open() ? ResponseEntity.ok(config.platformBundles()) : gate.denied();
    }

    /**
     * Creates a platform bundle.
     *
     * @param body    code and translations
     * @param request current request
     * @return 201 with the bundle
     */
    @PostMapping("/bundles")
    public ResponseEntity<?> createBundle(@RequestBody Bundle body, HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_LIBRARY, null);
        if (!gate.open()) {
            return gate.denied();
        }
        var b = config.createBundle(null, body.code(), body.description(), body.texts());
        support.audit(gate, "RULES_BUNDLE_CREATED", null, "rule_bundle", b.id().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(b);
    }

    /**
     * Sets or replaces one translation of a platform bundle.
     *
     * @param id       bundle
     * @param language BCP 47 tag
     * @param body     the text
     * @param request  current request
     * @return 204
     */
    @PutMapping("/bundles/{id}/texts/{language}")
    public ResponseEntity<?> putText(@PathVariable UUID id, @PathVariable String language, @RequestBody Text body,
                                     HttpServletRequest request) {
        var gate = support.write(request, Permission.RULES_LIBRARY, null);
        if (!gate.open()) {
            return gate.denied();
        }
        config.putText(null, id, language, body.text());
        support.audit(gate, "RULES_BUNDLE_TEXT_SET", null, "rule_bundle", id.toString(), null, Map.of("language", language));
        return ResponseEntity.noContent().build();
    }
}
