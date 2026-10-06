package com.springaimcpservercommon.ruleengine.repo;

import com.springaimcpservercommon.ruleengine.domain.Enums.ChannelType;
import com.springaimcpservercommon.ruleengine.domain.Enums.Outcome;
import com.springaimcpservercommon.ruleengine.domain.Model.ActionBinding;
import com.springaimcpservercommon.ruleengine.domain.Model.ApiEndpoint;
import com.springaimcpservercommon.ruleengine.domain.Model.Channel;
import com.springaimcpservercommon.ruleengine.domain.Model.EmailTemplate;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** E-mail templates, API endpoints, channels and the action bindings that use them. */
@Repository
public class ChannelRepository {

    private static final String TEMPLATE = "select id, tenant_id, external_template_id, name, description, active "
            + "from re_email_template";
    private static final String ENDPOINT = "select id, tenant_id, name, url, http_method, headers, environment_class, "
            + "external_confirmed, confirmed_by, confirmed_at, active from re_api_endpoint";
    private static final String CHANNEL = "select id, tenant_id, name, channel_type as type, email_template_id, "
            + "api_endpoint_id, config, active from re_channel";

    private final JdbcClient jdbc;

    public ChannelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── e-mail templates ───────────────────────────────────────────────────────────────────────────

    public Optional<EmailTemplate> template(long id) {
        return jdbc.sql(TEMPLATE + " where id = :i").param("i", id).query(EmailTemplate.class).optional();
    }

    public List<EmailTemplate> templates(long tenantId) {
        return jdbc.sql(TEMPLATE + " where tenant_id = :t order by name").param("t", tenantId)
                .query(EmailTemplate.class).list();
    }

    public EmailTemplate createTemplate(long tenantId, String externalId, String name, @Nullable String description) {
        long id = jdbc.sql("insert into re_email_template (tenant_id, external_template_id, name, description) "
                        + "values (:t, :e, :n, :d) returning id")
                .param("t", tenantId).param("e", externalId).param("n", name).param("d", description)
                .query(Long.class).single();
        return template(id).orElseThrow();
    }

    // ── API endpoints ──────────────────────────────────────────────────────────────────────────────

    public Optional<ApiEndpoint> endpoint(long id) {
        return jdbc.sql(ENDPOINT + " where id = :i").param("i", id).query(ApiEndpoint.class).optional();
    }

    public List<ApiEndpoint> endpoints(long tenantId) {
        return jdbc.sql(ENDPOINT + " where tenant_id = :t order by name").param("t", tenantId)
                .query(ApiEndpoint.class).list();
    }

    public ApiEndpoint createEndpoint(long tenantId, String name, String url, String method, @Nullable String headers,
                                      String environmentClass, boolean confirmed, @Nullable String confirmedBy) {
        long id = jdbc.sql("insert into re_api_endpoint (tenant_id, name, url, http_method, headers, "
                        + "environment_class, external_confirmed, confirmed_by, confirmed_at) values (:t, :n, :u, :m, "
                        + ":h, :c, :x, :by, case when :x then now() end) returning id")
                .param("t", tenantId).param("n", name).param("u", url).param("m", method).param("h", headers)
                .param("c", environmentClass).param("x", confirmed).param("by", confirmedBy).query(Long.class).single();
        return endpoint(id).orElseThrow();
    }

    // ── channels ───────────────────────────────────────────────────────────────────────────────────

    public Optional<Channel> channel(long id) {
        return jdbc.sql(CHANNEL + " where id = :i").param("i", id).query(Channel.class).optional();
    }

    public List<Channel> channels(long tenantId) {
        return jdbc.sql(CHANNEL + " where tenant_id = :t order by name").param("t", tenantId)
                .query(Channel.class).list();
    }

    public Channel createChannel(long tenantId, String name, ChannelType type, @Nullable Long templateId,
                                 @Nullable Long endpointId, @Nullable String config) {
        long id = jdbc.sql("insert into re_channel (tenant_id, name, channel_type, email_template_id, "
                        + "api_endpoint_id, config) values (:t, :n, :c, :e, :a, :cfg) returning id")
                .param("t", tenantId).param("n", name).param("c", type.name()).param("e", templateId)
                .param("a", endpointId).param("cfg", config).query(Long.class).single();
        return channel(id).orElseThrow();
    }

    // ── action bindings ────────────────────────────────────────────────────────────────────────────

    public List<ActionBinding> bindingsForRule(long ruleId, Outcome outcome) {
        return jdbc.sql("select id, tenant_id, rule_id, rule_group_id, on_outcome as outcome, channel_id "
                + "from re_action_binding where rule_id = :r and on_outcome = :o").param("r", ruleId)
                .param("o", outcome.name()).query(ActionBinding.class).list();
    }

    public List<ActionBinding> bindingsForGroup(long groupId, Outcome outcome) {
        return jdbc.sql("select id, tenant_id, rule_id, rule_group_id, on_outcome as outcome, channel_id "
                + "from re_action_binding where rule_group_id = :g and on_outcome = :o").param("g", groupId)
                .param("o", outcome.name()).query(ActionBinding.class).list();
    }

    public ActionBinding bind(long tenantId, @Nullable Long ruleId, @Nullable Long groupId, Outcome outcome,
                              long channelId) {
        long id = jdbc.sql("insert into re_action_binding (tenant_id, rule_id, rule_group_id, on_outcome, channel_id) "
                        + "values (:t, :r, :g, :o, :c) returning id")
                .param("t", tenantId).param("r", ruleId).param("g", groupId).param("o", outcome.name())
                .param("c", channelId).query(Long.class).single();
        return new ActionBinding(id, tenantId, ruleId, groupId, outcome, channelId);
    }

    public void unbind(long bindingId) {
        jdbc.sql("delete from re_action_binding where id = :i").param("i", bindingId).update();
    }
}
