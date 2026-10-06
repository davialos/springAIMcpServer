package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.ApiEndpointView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.ChannelView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.EmailTemplateView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.GroupView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.LibraryObject;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.Module;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.RuleView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.SetupSummary;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.TriggerView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only view of the rule engine setup, for every signed-in user (scoped to their tenant and organization). */
@RestController
@RequestMapping("/api/v1")
class SetupController {

    private final CatalogRepository catalog;

    SetupController(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    /** Who the token says the caller is: the UI shows it, and checks it matches the login response. */
    @GetMapping("/me")
    Map<String, Object> me(Caller c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", c.userId());
        m.put("username", c.username());
        m.put("displayName", c.displayName());
        m.put("role", c.role());
        m.put("tenantId", c.tenantId());
        m.put("tenantName", c.tenantName());
        m.put("organizationId", c.organizationId());
        m.put("organizationName", c.organizationName());
        return m;
    }

    @GetMapping("/setup")
    SetupSummary setup(Caller c) {
        return catalog.summary(c);
    }

    @GetMapping("/modules")
    List<Module> modules() {
        return catalog.modules();
    }

    @GetMapping("/library")
    List<LibraryObject> library(Caller c) {
        return catalog.library(c);
    }

    @GetMapping("/rules")
    List<RuleView> rules(Caller c, @RequestParam(required = false) String module,
                         @RequestParam(required = false) String status) {
        return catalog.rules(c, module, status);
    }

    @GetMapping("/rules/{id}")
    RuleView rule(Caller c, @PathVariable UUID id) {
        return catalog.rule(c, id);
    }

    @GetMapping("/rule-groups")
    List<GroupView> groups(Caller c, @RequestParam(required = false) String module,
                           @RequestParam(required = false) String status) {
        return catalog.groups(c, module, status);
    }

    @GetMapping("/rule-groups/{id}")
    GroupView group(Caller c, @PathVariable UUID id) {
        return catalog.group(c, id);
    }

    @GetMapping("/triggers")
    List<TriggerView> triggers(Caller c) {
        return catalog.triggers(c);
    }

    @GetMapping("/channels")
    List<ChannelView> channels(Caller c) {
        return catalog.channels(c);
    }

    @GetMapping("/email-templates")
    List<EmailTemplateView> emailTemplates(Caller c) {
        return catalog.emailTemplates(c);
    }

    @GetMapping("/api-endpoints")
    List<ApiEndpointView> apiEndpoints(Caller c) {
        return catalog.apiEndpoints(c);
    }
}
