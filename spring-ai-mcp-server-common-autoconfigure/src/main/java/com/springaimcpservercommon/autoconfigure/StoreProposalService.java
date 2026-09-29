package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.ai.tool.ProposalService;
import com.springaimcpservercommon.ai.tool.ToolBinding;
import com.springaimcpservercommon.ai.tool.ToolSource;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveOperation;
import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.core.json.CanonicalJson;
import com.springaimcpservercommon.persistence.proposal.ApprovalRequirement;
import com.springaimcpservercommon.persistence.proposal.ChangeKind;
import com.springaimcpservercommon.persistence.proposal.ChangeProposal;
import com.springaimcpservercommon.persistence.proposal.ChangeProposalStore;
import com.springaimcpservercommon.persistence.proposal.NewChangeProposal;
import com.springaimcpservercommon.persistence.proposal.NewProposalRecord;
import com.springaimcpservercommon.persistence.proposal.ProposalOrigin;
import com.springaimcpservercommon.persistence.proposal.ProposalTargetKind;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Creates the reviewable {@code ChangeProposal} for a tool in PROPOSE mode (F-45, LLD-11 §2–3, ADR-0009).
 *
 * <p>A proposal only <em>records</em> what the tool asked for: the host operation to run (the tool's operation) and
 * the arguments to run it with (after the binding's argument constraints), owned by the caller and tied to the tool
 * call, turn and channel. Nothing is written to the host. The owner reviews it through the review API; a confirm
 * (an HTTP request of the owner's session, never a tool call) moves it on, and applying it through the host's own
 * write path is a separate step (OQ-36).
 *
 * <p>Refusals are {@link ProposalService.ProposalRefusedException}s with fixed messages the model may see:
 * {@code writes_disabled} (the switch {@code dynamic.ai.agent.write.enabled} is off, its default), an operation that
 * is unknown, read-only or not linked to a record type, or arguments that are not a JSON object.
 *
 * <p>Creating is idempotent per turn: the same tool with the same arguments in one turn (or one MCP request) returns
 * the same proposal, so a model that repeats a call does not fill the inbox. The proposal stores the arguments as
 * the record's after-values (they may contain personal data, hence the short retention); the summary, logs and
 * metrics never include them. No before-snapshot or base version is captured yet, so conflicts are only detected by
 * the host's own checks at apply time (OQ-36).
 */
@NullMarked
final class StoreProposalService implements ProposalService {

    private static final Logger LOG = LoggerFactory.getLogger(StoreProposalService.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ChangeProposalStore store;
    private final Supplier<EffectiveCatalog> catalog;
    private final DaiProperties.Write settings;

    StoreProposalService(ChangeProposalStore store, Supplier<EffectiveCatalog> catalog, DaiProperties.Write settings) {
        this.store = Objects.requireNonNull(store, "store");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public UUID createProposal(ProposalRequest request) {
        if (!settings.enabled()) {
            throw new ProposalRefusedException("writes_disabled", "Proposing changes is switched off on this system.");
        }
        NewChangeProposal data = toNewProposal(request, catalog.get(), settings);
        ChangeProposal proposal = store.create(data);
        SafeMetrics.count("dynamic.ai.agent.proposals", "state", proposal.getState().name(), "kind",
                data.changeKind().name(), "origin", data.origin().name());
        LOG.info("Proposal {} created by tool {} for principal {}", proposal.getId(), request.binding().toolName(),
                request.principal().principalId());
        return proposal.getId();
    }

    /** Maps a tool call to the proposal to store; package-private so the mapping is testable without a store. */
    static NewChangeProposal toNewProposal(ProposalRequest request, EffectiveCatalog catalog,
                                           DaiProperties.Write settings) {
        ToolBinding binding = request.binding();
        if (!(binding.source() instanceof ToolSource.OperationSource(CatalogElementRef operationRef))) {
            throw new ProposalRefusedException("not_a_write_tool", "This tool cannot propose changes.");
        }
        EffectiveOperation operation = catalog.operation(operationRef).orElseThrow(() -> new ProposalRefusedException(
                "operation_unavailable", "The operation behind this tool is not available."));
        if (operation.readOnly()) {
            throw new ProposalRefusedException("not_a_write_tool", "This tool does not change data.");
        }
        CatalogElementRef entity = operation.descriptor().entity();
        if (entity == null) {
            throw new ProposalRefusedException("operation_without_entity",
                    "This tool is not linked to a record type, so its change cannot be reviewed.");
        }
        ProposalOrigin origin = switch (request.scope().channel()) {
            case CHAT, PLAYGROUND -> ProposalOrigin.AGENT_TOOL;
            case MCP -> ProposalOrigin.MCP_TOOL;
            case ENDPOINT -> throw new ProposalRefusedException("proposal_unavailable",
                    "Changes cannot be proposed through this channel.");
        };
        Map<String, Object> arguments = arguments(request.toolInput());
        ChangeKind kind = switch (binding.change() == null ? Change.UPDATE : binding.change()) {
            case CREATE -> ChangeKind.CREATE;
            case UPDATE -> ChangeKind.UPDATE;
            case DELETE -> ChangeKind.DELETE;
        };
        boolean approver = kind == ChangeKind.DELETE || settings.requireApprover();
        String argumentsJson = CanonicalJson.write(arguments);
        String contentHash = contentHash(operationRef, kind, arguments);
        UUID anchor = request.scope().turnId() != null ? request.scope().turnId() : request.scope().mcpRequestId();
        // one proposal per (turn or MCP request, tool, arguments); a request without either is keyed by the call
        String key = (anchor != null ? anchor : request.toolInvocationId()) + ":" + binding.id() + ":" + contentHash;
        return new NewChangeProposal(binding.workspaceId(), origin, request.scope().channel(), null,
                request.scope().turnId(), request.toolInvocationId(), null, request.principal().principalId(),
                ProposalTargetKind.HOST_OPERATION, operationRef, argumentsJson, kind,
                approver ? ApprovalRequirement.SELF_CONFIRM_PLUS_APPROVER : ApprovalRequirement.SELF_CONFIRM,
                approver ? 1 : 0, contentHash,
                "Proposed by tool " + binding.toolName() + " to run " + operationRef, null, key,
                settings.proposalTtl(), settings.retention(),
                List.of(new NewProposalRecord(entity, null, null, argumentsJson, null, null)));
    }

    /**
     * The hash a proposal is confirmed against: the target operation, the change kind and the canonical arguments.
     * The applier recomputes it before running anything, so stored content that was altered after review is refused.
     */
    static String contentHash(CatalogElementRef target, ChangeKind kind, Map<String, Object> arguments) {
        return Sha256.of(CanonicalJson.write(Map.of("target", target.toString(), "kind", kind.name(),
                "args", arguments)));
    }

    static Map<String, Object> arguments(String toolInput) {
        Object parsed;
        try {
            parsed = toolInput.isBlank() ? Map.of() : MAPPER.readerFor(Object.class).readValue(toolInput);
        } catch (RuntimeException e) {
            throw new ProposalRefusedException("invalid_arguments", "The tool arguments are not valid JSON.");
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new ProposalRefusedException("invalid_arguments", "The tool arguments must be a JSON object.");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }
}
