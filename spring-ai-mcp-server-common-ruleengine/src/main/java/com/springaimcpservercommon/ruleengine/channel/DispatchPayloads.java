package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.response.EvaluationResponse;
import com.springaimcpservercommon.ruleengine.response.ResponseMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The data each channel sends; shared by direct dispatch and the outbox so both send exactly the same thing. */
final class DispatchPayloads {

    private DispatchPayloads() {
    }

    /** API body: decision and messages, never input values. {@code dispatchId} lets the receiver de-duplicate. */
    static Map<String, Object> api(EvaluationResponse r, UUID dispatchId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dispatchId", dispatchId.toString());
        body.put("module", r.moduleCode());
        body.put("group", r.groupCode());
        body.put("policy", r.policy().name());
        body.put("decision", r.decision().name());
        body.put("matched", r.matched());
        List<Object> messages = new ArrayList<>();
        for (ResponseMessage m : r.messages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", m.source().name());
            item.put("rule", m.ruleCode());
            item.put("outcome", m.outcome().name());
            item.put("action", m.action().name());
            item.put("language", m.language());
            item.put("text", m.text());
            messages.add(item);
        }
        body.put("messages", messages);
        return body;
    }

    static Map<String, Object> email(EmailMessage m) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("templateRef", m.templateRef());
        p.put("templateName", m.templateName());
        p.put("recipient", m.recipient());
        p.put("language", m.language());
        p.put("module", m.moduleCode());
        p.put("group", m.groupCode());
        p.put("decision", m.decision().name());
        return p;
    }

    static Map<String, Object> push(PushMessage m) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("recipient", m.recipient());
        p.put("title", m.title());
        p.put("body", m.body());
        p.put("language", m.language());
        return p;
    }
}
