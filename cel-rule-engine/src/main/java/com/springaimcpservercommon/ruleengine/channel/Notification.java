package com.springaimcpservercommon.ruleengine.channel;

import com.springaimcpservercommon.ruleengine.domain.Action;
import com.springaimcpservercommon.ruleengine.eval.Results.FinalMessage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a channel is asked to communicate.
 *
 * @param evaluationId audit id of the evaluation
 * @param tenant       tenant code
 * @param module       module code
 * @param trigger      what triggered the evaluation
 * @param subjectType  {@code RULE} or {@code GROUP}: what the bound outcome belongs to
 * @param subject      the rule or group code
 * @param outcome      the true/false result that fired the binding
 * @param action       the action of the evaluation (allow, warn, block)
 * @param language     language of the messages
 * @param messages     the final messages
 * @param context      the caller's context as {@code object.attribute → text}; used to find recipients, never sent on
 */
public record Notification(UUID evaluationId, String tenant, String module, String trigger, String subjectType,
                           String subject, boolean outcome, Action action, String language,
                           List<FinalMessage> messages, Map<String, String> context) { }
