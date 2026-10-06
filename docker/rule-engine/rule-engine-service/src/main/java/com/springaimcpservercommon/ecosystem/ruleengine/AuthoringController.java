package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.CreateRule;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.GroupRequest;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.GroupView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.RuleView;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.StatusChange;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.Written;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** Create and change rules and rule groups. Any signed-in user may author in their own organization (see AuthoringService). */
@RestController
@RequestMapping("/api/v1")
class AuthoringController {

    private final AuthoringService authoring;

    AuthoringController(AuthoringService authoring) {
        this.authoring = authoring;
    }

    @PostMapping("/rules")
    ResponseEntity<Written<RuleView>> createRule(Caller c, @RequestBody CreateRule request) {
        Written<RuleView> w = authoring.createRule(c, request);
        return ResponseEntity.created(URI.create("/api/v1/rules/" + w.value().id())).body(w);
    }

    @PatchMapping("/rules/{id}/status")
    RuleView ruleStatus(Caller c, @PathVariable UUID id, @RequestBody StatusChange change) {
        return authoring.setRuleStatus(c, id, change.status());
    }

    @PostMapping("/rule-groups")
    ResponseEntity<Written<GroupView>> createGroup(Caller c, @RequestBody GroupRequest request) {
        Written<GroupView> w = authoring.createGroup(c, request);
        return ResponseEntity.created(URI.create("/api/v1/rule-groups/" + w.value().id())).body(w);
    }

    @PutMapping("/rule-groups/{id}")
    Written<GroupView> replaceGroup(Caller c, @PathVariable UUID id, @RequestBody GroupRequest request) {
        return authoring.replaceGroup(c, id, request);
    }

    @PatchMapping("/rule-groups/{id}/status")
    GroupView groupStatus(Caller c, @PathVariable UUID id, @RequestBody StatusChange change) {
        return authoring.setGroupStatus(c, id, change.status());
    }
}
