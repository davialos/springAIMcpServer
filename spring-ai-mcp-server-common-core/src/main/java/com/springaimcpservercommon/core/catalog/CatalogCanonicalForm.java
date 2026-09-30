package com.springaimcpservercommon.core.catalog;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical value-tree form of descriptors, rendered with {@code CanonicalJson} for fingerprints and exports.
 * Every semantically relevant field is included; nothing time- or instance-dependent is.
 */
final class CatalogCanonicalForm {

    private CatalogCanonicalForm() {
    }

    static Map<String, @Nullable Object> entity(EntityDescriptor e) {
        Map<String, @Nullable Object> m = new LinkedHashMap<>();
        m.put("kind", "entity");
        m.put("ref", e.ref().toString());
        m.put("javaType", e.javaType());
        m.put("name", e.name());
        m.put("description", e.description());
        m.put("keywords", e.keywords());
        m.put("classification", e.classification());
        m.put("maxLimit", e.maxLimit());
        m.put("mandatoryFilters", e.mandatoryFilters());
        m.put("attributes", e.attributes().stream().map(CatalogCanonicalForm::attribute).toList());
        m.put("relations", e.relations().stream().map(CatalogCanonicalForm::relation).toList());
        m.put("source", e.source());
        return m;
    }

    static Map<String, @Nullable Object> attribute(AttributeDescriptor a) {
        Map<String, @Nullable Object> m = new LinkedHashMap<>();
        m.put("ref", a.ref().toString());
        m.put("name", a.name());
        m.put("javaType", a.javaType());
        m.put("meaning", a.meaning());
        m.put("sensitive", a.sensitive());
        m.put("writable", a.writable());
        m.put("classification", a.classification());
        m.put("identifier", a.identifier());
        return m;
    }

    static Map<String, @Nullable Object> relation(RelationDescriptor r) {
        Map<String, @Nullable Object> m = new LinkedHashMap<>();
        m.put("name", r.name());
        m.put("kind", r.kind());
        m.put("target", r.target().toString());
        m.put("mappedBy", r.mappedBy());
        m.put("optional", r.optional());
        return m;
    }

    static Map<String, @Nullable Object> operation(OperationDescriptor o) {
        Map<String, @Nullable Object> m = new LinkedHashMap<>();
        m.put("kind", "operation");
        m.put("ref", o.ref().toString());
        m.put("beanName", o.beanName());
        m.put("declaringType", o.declaringType());
        m.put("invocationType", o.invocationType());
        m.put("methodName", o.methodName());
        m.put("parameterTypes", o.parameterTypes());
        m.put("toolName", o.toolName());
        m.put("intent", o.intent());
        m.put("keywords", o.keywords());
        m.put("params", o.params().stream().map(CatalogCanonicalForm::param).toList());
        m.put("inputSchema", o.inputSchema().tree());
        m.put("returnSchema", o.returnSchema() == null ? null : o.returnSchema().tree());
        m.put("returnType", o.returnType());
        m.put("readOnly", o.readOnly());
        m.put("idempotent", o.idempotent());
        m.put("classification", o.classification());
        m.put("bounding", o.bounding().kind());
        m.put("limitParameter", o.bounding().limitParameter());
        m.put("context", o.context() == null ? null : o.context().toString());
        m.put("entity", o.entity() == null ? null : o.entity().toString());
        return m;
    }

    static Map<String, @Nullable Object> param(ParamDescriptor p) {
        Map<String, @Nullable Object> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("index", p.index());
        m.put("javaType", p.javaType());
        m.put("description", p.description());
        m.put("required", p.required());
        m.put("sensitive", p.sensitive());
        m.put("kind", p.kind());
        // only when present, so catalogs that do not use them keep their fingerprints
        if (p.details() != null) {
            m.put("details", p.details());
        }
        if (!p.examples().isEmpty()) {
            m.put("examples", p.examples());
        }
        return m;
    }

    static Map<String, @Nullable Object> context(ContextDescriptor c) {
        Map<String, @Nullable Object> m = new LinkedHashMap<>();
        m.put("kind", "context");
        m.put("ref", c.ref().toString());
        m.put("beanName", c.beanName());
        m.put("javaType", c.javaType());
        m.put("methodName", c.methodName());
        m.put("name", c.name());
        m.put("description", c.description());
        m.put("keywords", c.keywords());
        m.put("classification", c.classification());
        m.put("controller", c.controller());
        return m;
    }

    static List<Map<String, @Nullable Object>> all(ScannedCatalog catalog) {
        java.util.ArrayList<Map<String, @Nullable Object>> list = new java.util.ArrayList<>();
        catalog.entities().values().forEach(e -> list.add(entity(e)));
        catalog.operations().values().forEach(o -> list.add(operation(o)));
        catalog.contexts().values().forEach(c -> list.add(context(c)));
        return list;
    }
}
