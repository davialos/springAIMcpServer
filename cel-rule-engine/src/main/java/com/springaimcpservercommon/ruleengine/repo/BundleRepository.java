package com.springaimcpservercommon.ruleengine.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Message bundles: one bundle, one text per language. */
@Repository
public class BundleRepository {

    /** A bundle. */
    public record Bundle(long id, String code, String description) { }

    /** One language's text of a bundle. */
    public record BundleText(long bundleId, String languageCode, String text) { }

    private final JdbcClient jdbc;

    public BundleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Bundle> bundle(long id) {
        return jdbc.sql("select id, code, description from sys_bundle where id = :i").param("i", id)
                .query(Bundle.class).optional();
    }

    public Optional<Bundle> bundle(String code) {
        return jdbc.sql("select id, code, description from sys_bundle where code = :c").param("c", code)
                .query(Bundle.class).optional();
    }

    public List<Bundle> bundles() {
        return jdbc.sql("select id, code, description from sys_bundle order by code").query(Bundle.class).list();
    }

    public Bundle createBundle(String code, String description) {
        long id = jdbc.sql("insert into sys_bundle (code, description) values (:c, :d) returning id")
                .param("c", code).param("d", description).query(Long.class).single();
        return new Bundle(id, code, description);
    }

    public void setText(long bundleId, String language, String text) {
        jdbc.sql("insert into sys_bundle_text (bundle_id, language_code, text) values (:b, :l, :t) "
                        + "on conflict (bundle_id, language_code) do update set text = :t")
                .param("b", bundleId).param("l", language).param("t", text).update();
    }

    public List<BundleText> texts(long bundleId) {
        return jdbc.sql("select bundle_id, language_code, text from sys_bundle_text where bundle_id = :b "
                + "order by language_code").param("b", bundleId).query(BundleText.class).list();
    }

    /** The texts of several bundles: bundle id → language → text. */
    public Map<Long, Map<String, String>> texts(Collection<Long> bundleIds) {
        Map<Long, Map<String, String>> out = new HashMap<>();
        if (bundleIds.isEmpty()) {
            return out;
        }
        for (BundleText t : jdbc.sql("select bundle_id, language_code, text from sys_bundle_text "
                + "where bundle_id in (:ids)").param("ids", bundleIds).query(BundleText.class).list()) {
            out.computeIfAbsent(t.bundleId(), k -> new HashMap<>()).put(t.languageCode(), t.text());
        }
        return out;
    }
}
