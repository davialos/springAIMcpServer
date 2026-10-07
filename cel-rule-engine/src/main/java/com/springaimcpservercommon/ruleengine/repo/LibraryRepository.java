package com.springaimcpservercommon.ruleengine.repo;

import com.springaimcpservercommon.ruleengine.domain.DataType;
import com.springaimcpservercommon.ruleengine.domain.Model.SysAttribute;
import com.springaimcpservercommon.ruleengine.domain.Model.SysObject;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The parameter library: sys objects and their attributes. */
@Repository
public class LibraryRepository {

    private record ObjectRow(long id, String code, String name, String description, Long moduleId, boolean active) {
    }

    private final JdbcClient jdbc;

    public LibraryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every active object with its attributes. */
    public List<SysObject> loadAll() {
        Map<Long, List<SysAttribute>> attributes = new LinkedHashMap<>();
        for (SysAttribute a : jdbc.sql("select id, sys_object_id as object_id, code, name, data_type, required, "
                + "description from sys_object_attribute order by sys_object_id, id").query(SysAttribute.class).list()) {
            attributes.computeIfAbsent(a.objectId(), k -> new ArrayList<>()).add(a);
        }
        return jdbc.sql("select id, code, name, description, module_id, active from sys_object where active order by code")
                .query(ObjectRow.class).list().stream()
                .map(o -> new SysObject(o.id(), o.code(), o.name(), o.description(), o.moduleId(), o.active(),
                        attributes.getOrDefault(o.id(), List.of())))
                .toList();
    }

    public Optional<SysObject> object(String code) {
        return loadAll().stream().filter(o -> o.code().equals(code)).findFirst();
    }

    public SysObject createObject(String code, String name, @Nullable String description, @Nullable Long moduleId) {
        long id = jdbc.sql("insert into sys_object (code, name, description, module_id) values (:c, :n, :d, :m) "
                        + "returning id")
                .param("c", code).param("n", name).param("d", description).param("m", moduleId)
                .query(Long.class).single();
        return new SysObject(id, code, name, description, moduleId, true, List.of());
    }

    public void updateObject(long id, String name, @Nullable String description, boolean active) {
        jdbc.sql("update sys_object set name = :n, description = :d, active = :a, updated_at = now() where id = :i")
                .param("n", name).param("d", description).param("a", active).param("i", id).update();
    }

    public SysAttribute upsertAttribute(long objectId, String code, String name, DataType type, boolean required,
                                        @Nullable String description) {
        long id = jdbc.sql("insert into sys_object_attribute (sys_object_id, code, name, data_type, required, "
                        + "description) values (:o, :c, :n, :t, :r, :d) on conflict (sys_object_id, code) do update "
                        + "set name = :n, data_type = :t, required = :r, description = :d returning id")
                .param("o", objectId).param("c", code).param("n", name).param("t", type.name())
                .param("r", required).param("d", description).query(Long.class).single();
        jdbc.sql("update sys_object set updated_at = now() where id = :o").param("o", objectId).update();
        return new SysAttribute(id, objectId, code, name, type, required, description);
    }

    public void deleteAttribute(long objectId, String code) {
        jdbc.sql("delete from sys_object_attribute where sys_object_id = :o and code = :c")
                .param("o", objectId).param("c", code).update();
        jdbc.sql("update sys_object set updated_at = now() where id = :o").param("o", objectId).update();
    }

    /** A cheap change detector for the cache: changes with every object or attribute edit. */
    public String fingerprint() {
        return jdbc.sql("select coalesce(max(updated_at)::text, '') || ':' || (select count(*) from sys_object_attribute) "
                + "|| ':' || count(*) from sys_object").query(String.class).single();
    }
}
