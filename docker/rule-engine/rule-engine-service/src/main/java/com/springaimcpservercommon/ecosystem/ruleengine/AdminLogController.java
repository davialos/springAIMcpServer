package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.AuditEntry;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.ConversationDetail;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.ConversationLog;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.EvaluationDetail;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.EvaluationLog;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.LogSummary;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Operational logs for administrators. {@code SecurityConfig} restricts {@code /api/v1/admin/**} to the ADMIN role before
 * a request gets here; every query is still scoped to the caller's tenant and organization.
 */
@RestController
@RequestMapping("/api/v1/admin/logs")
class AdminLogController {

    private final LogRepository logs;

    AdminLogController(LogRepository logs) {
        this.logs = logs;
    }

    @GetMapping("/summary")
    LogSummary summary(Caller c, @RequestParam(defaultValue = "24") int hours) {
        return logs.summary(c, Math.max(1, Math.min(hours, 24 * 30)));
    }

    @GetMapping("/evaluations")
    Page<EvaluationLog> evaluations(Caller c, @RequestParam(required = false) String decision,
                                    @RequestParam(required = false) String module,
                                    @RequestParam(required = false) String group,
                                    @RequestParam(defaultValue = "false") boolean errorsOnly,
                                    @RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        return logs.evaluations(c, new LogRepository.EvaluationFilter(upperOrNull(decision), module, group, errorsOnly,
                from, to), page, size);
    }

    @GetMapping("/evaluations/{id}")
    EvaluationDetail evaluation(Caller c, @PathVariable UUID id) {
        return logs.evaluation(c, id);
    }

    @GetMapping("/audit")
    Page<AuditEntry> audit(Caller c, @RequestParam(required = false) String action,
                           @RequestParam(required = false) String entityType,
                           @RequestParam(required = false) String actor, @RequestParam(required = false) Instant from,
                           @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "50") int size) {
        return logs.audit(c, new LogRepository.AuditFilter(upperOrNull(action), upperOrNull(entityType),
                blankToNull(actor), from, to), page, size);
    }

    @GetMapping("/conversations")
    Page<ConversationLog> conversations(Caller c, @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return logs.conversations(c, page, size);
    }

    @GetMapping("/conversations/{id}")
    ConversationDetail conversation(Caller c, @PathVariable UUID id) {
        return logs.conversation(c, id);
    }

    private static String upperOrNull(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
