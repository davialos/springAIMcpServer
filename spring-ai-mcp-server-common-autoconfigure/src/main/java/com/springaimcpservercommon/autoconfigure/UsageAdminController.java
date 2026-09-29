package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.persistence.audit.AuditCategory;
import com.springaimcpservercommon.persistence.audit.AuditPlane;
import com.springaimcpservercommon.persistence.support.TimeRange;
import com.springaimcpservercommon.persistence.usage.BudgetTarget;
import com.springaimcpservercommon.persistence.usage.ModelPriceView;
import com.springaimcpservercommon.persistence.usage.PriceStore;
import com.springaimcpservercommon.persistence.usage.UsageBucket;
import com.springaimcpservercommon.persistence.usage.UsageLedger;
import com.springaimcpservercommon.persistence.usage.UsageTotals;
import com.springaimcpservercommon.persistence.usage.UsageWindow;
import com.springaimcpservercommon.security.permission.Permission;
import com.springaimcpservercommon.webmvc.problem.ProblemDetailFactory.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Usage and cost dashboard API (F-71): summary totals, hourly/daily series and the model price list.
 * Usage endpoints need {@link Permission#BUDGET_MANAGE} or {@link Permission#AUDIT_READ}, in the workspace for
 * {@code /workspaces/{workspaceId}/usage/**} and globally for {@code /usage/**}. Adding a price is a platform
 * decision and needs a global {@link Permission#BUDGET_MANAGE}.
 *
 * <p>Series are read from hourly buckets and limited to 32 days; daily buckets are UTC days. Not a
 * {@code @Component}; registered by {@link DaiAdminAutoConfiguration}.
 */
@NullMarked
@RequestMapping("/dynamic-ai/admin/api/v1")
public final class UsageAdminController {

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@-]{0,127}");
    static final long MAX_PRICE_MICROS = 1_000_000_000_000L;
    static final Duration MAX_SERIES = Duration.ofHours(UsageLedger.MAX_SERIES_HOURS);

    /**
     * Summed usage.
     *
     * @param calls              model calls
     * @param inputTokens        input tokens
     * @param outputTokens       output tokens
     * @param cachedInputTokens  cached input tokens
     * @param totalTokens        all tokens
     * @param costMicrosByCurrency cost per currency, micros
     */
    public record TotalsDto(long calls, long inputTokens, long outputTokens, long cachedInputTokens,
                            long totalTokens, Map<String, Long> costMicrosByCurrency) {
        static TotalsDto of(UsageTotals t) {
            return new TotalsDto(t.calls(), t.inputTokens(), t.outputTokens(), t.cachedInputTokens(),
                    t.totalTokens(), new TreeMap<>(t.costMicrosByCurrency()));
        }
    }

    /**
     * Summary response.
     *
     * @param from   inclusive window start
     * @param to     exclusive window end
     * @param totals summed usage
     */
    public record SummaryDto(Instant from, Instant to, TotalsDto totals) {}

    /**
     * One series point.
     *
     * @param start  bucket start (UTC hour or day)
     * @param totals summed usage
     */
    public record PointDto(Instant start, TotalsDto totals) {}

    /**
     * Series response.
     *
     * @param from   inclusive window start
     * @param to     exclusive window end
     * @param bucket HOUR or DAY
     * @param points points that have usage, oldest first
     */
    public record SeriesDto(Instant from, Instant to, String bucket, List<PointDto> points) {}

    /**
     * Price list entry.
     *
     * @param provider                  provider id
     * @param model                     model id
     * @param validFrom                 start of validity
     * @param currency                  ISO-4217 code
     * @param inputPerMtokMicros        input price per million tokens, micros
     * @param outputPerMtokMicros       output price per million tokens, micros
     * @param cachedInputPerMtokMicros  cached input price per million tokens, micros
     */
    public record PriceDto(String provider, String model, Instant validFrom, String currency,
                           long inputPerMtokMicros, long outputPerMtokMicros, long cachedInputPerMtokMicros) {
        static PriceDto of(ModelPriceView p) {
            return new PriceDto(p.provider(), p.model(), p.validFrom(), p.currency(), p.inputPerMtokMicros(),
                    p.outputPerMtokMicros(), p.cachedInputPerMtokMicros());
        }
    }

    /**
     * Add-price request.
     *
     * @param provider                  provider id
     * @param model                     model id
     * @param validFrom                 ISO-8601 start of validity (default now)
     * @param currency                  ISO-4217 code
     * @param inputPerMtokMicros        input price per million tokens
     * @param outputPerMtokMicros       output price per million tokens
     * @param cachedInputPerMtokMicros  cached input price per million tokens (default 0)
     */
    public record AddPriceRequest(@Nullable String provider, @Nullable String model, @Nullable String validFrom,
                                  @Nullable String currency, @Nullable Long inputPerMtokMicros,
                                  @Nullable Long outputPerMtokMicros, @Nullable Long cachedInputPerMtokMicros) {}

    private final UsageLedger ledger;
    private final PriceStore prices;
    private final AdminAudit audit;
    private final AdminApi api;
    private final Clock clock;

    UsageAdminController(UsageLedger ledger, PriceStore prices, AdminAudit audit, AdminApi api, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Summed usage over a window.
     *
     * @param workspaceId     workspace for the workspace route family; absent for installation-wide usage
     * @param agentResourceId optional agent filter (workspace routes only)
     * @param principalId     optional principal filter
     * @param from            inclusive ISO-8601 start; default 24 h before {@code to}
     * @param to              exclusive ISO-8601 end; default now
     * @param request         current request
     * @return 200 with totals
     */
    @GetMapping({"/usage/summary", "/workspaces/{workspaceId}/usage/summary"})
    public ResponseEntity<?> summary(@PathVariable(required = false) @Nullable UUID workspaceId,
                                     @RequestParam(required = false) @Nullable UUID agentResourceId,
                                     @RequestParam(required = false) @Nullable UUID principalId,
                                     @RequestParam(required = false) @Nullable String from,
                                     @RequestParam(required = false) @Nullable String to,
                                     HttpServletRequest request) {
        var gate = api.gateAny(request, workspaceId, Permission.BUDGET_MANAGE, Permission.AUDIT_READ);
        if (!gate.open()) {
            return gate.denied();
        }
        List<FieldViolation> errors = new ArrayList<>();
        BudgetTarget target = target(workspaceId, agentResourceId, principalId, errors);
        if (target == null) {
            return AdminApi.validation(request, errors);
        }
        TimeRange range = AdminApi.window(from, to, clock.instant());
        UsageTotals totals = ledger.totals(target, new UsageWindow(range.from(), range.to()));
        return ResponseEntity.ok(new SummaryDto(range.from(), range.to(), TotalsDto.of(totals)));
    }

    /**
     * Usage per hour or day over a window of at most 32 days.
     *
     * @param workspaceId     workspace for the workspace route family
     * @param agentResourceId optional agent filter (workspace routes only)
     * @param principalId     optional principal filter
     * @param from            inclusive ISO-8601 start
     * @param to              exclusive ISO-8601 end
     * @param bucket          {@code HOUR} or {@code DAY}; default HOUR for windows up to 72 hours, else DAY
     * @param request         current request
     * @return 200 with the series
     */
    @GetMapping({"/usage/series", "/workspaces/{workspaceId}/usage/series"})
    public ResponseEntity<?> series(@PathVariable(required = false) @Nullable UUID workspaceId,
                                    @RequestParam(required = false) @Nullable UUID agentResourceId,
                                    @RequestParam(required = false) @Nullable UUID principalId,
                                    @RequestParam(required = false) @Nullable String from,
                                    @RequestParam(required = false) @Nullable String to,
                                    @RequestParam(required = false) @Nullable String bucket,
                                    HttpServletRequest request) {
        var gate = api.gateAny(request, workspaceId, Permission.BUDGET_MANAGE, Permission.AUDIT_READ);
        if (!gate.open()) {
            return gate.denied();
        }
        List<FieldViolation> errors = new ArrayList<>();
        BudgetTarget target = target(workspaceId, agentResourceId, principalId, errors);
        TimeRange range = AdminApi.window(from, to, clock.instant());
        if (Duration.between(range.from(), range.to()).compareTo(MAX_SERIES) > 0) {
            errors.add(new FieldViolation("from", "series windows are limited to " + MAX_SERIES.toDays() + " days"));
        }
        boolean daily = Duration.between(range.from(), range.to()).toHours() > 72;
        if (bucket != null && !bucket.isBlank()) {
            String b = bucket.strip().toUpperCase(Locale.ROOT);
            if (b.equals("DAY")) {
                daily = true;
            } else if (b.equals("HOUR")) {
                daily = false;
            } else {
                errors.add(new FieldViolation("bucket", "must be HOUR or DAY"));
            }
        }
        if (target == null || !errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        List<UsageBucket> hours = ledger.hourlySeries(target, new UsageWindow(range.from(), range.to()));
        List<PointDto> points = daily ? daily(hours) : hours.stream()
                .map(h -> new PointDto(h.bucketStart(), TotalsDto.of(h.totals()))).toList();
        return ResponseEntity.ok(new SeriesDto(range.from(), range.to(), daily ? "DAY" : "HOUR", points));
    }

    /**
     * Lists model prices.
     *
     * @param request current request
     * @return 200 with prices
     */
    @GetMapping("/prices")
    public ResponseEntity<?> prices(HttpServletRequest request) {
        var gate = api.gateAny(request, null, Permission.BUDGET_MANAGE, Permission.AUDIT_READ);
        if (!gate.open()) {
            return gate.denied();
        }
        return ResponseEntity.ok(prices.list().stream().map(PriceDto::of).toList());
    }

    /**
     * Adds a model price valid from a point in time. Prices are append-only history.
     *
     * @param body    price data
     * @param request current request
     * @return 201 with the price; 400 with field errors
     */
    @PostMapping("/prices")
    public ResponseEntity<?> addPrice(@RequestBody AddPriceRequest body, HttpServletRequest request) {
        var gate = api.gate(request, Permission.BUDGET_MANAGE, null);
        if (!gate.open()) {
            return gate.denied();
        }
        List<FieldViolation> errors = new ArrayList<>();
        String provider = name(errors, "provider", body.provider());
        String model = name(errors, "model", body.model());
        String currency = body.currency() == null ? null : body.currency().strip().toUpperCase(Locale.ROOT);
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            errors.add(new FieldViolation("currency", "is required and must be an ISO-4217 code like EUR"));
        }
        long input = price(errors, "inputPerMtokMicros", body.inputPerMtokMicros(), true);
        long output = price(errors, "outputPerMtokMicros", body.outputPerMtokMicros(), true);
        long cached = price(errors, "cachedInputPerMtokMicros", body.cachedInputPerMtokMicros(), false);
        Instant validFrom = clock.instant();
        if (body.validFrom() != null && !body.validFrom().isBlank()) {
            try {
                validFrom = AdminApi.instant(body.validFrom(), "validFrom");
            } catch (IllegalArgumentException e) {
                errors.add(new FieldViolation("validFrom", "must be an ISO-8601 instant"));
            }
        }
        if (!errors.isEmpty()) {
            return AdminApi.validation(request, errors);
        }
        DaiPrincipal caller = gate.caller();
        ModelPriceView created = prices.addPrice(Objects.requireNonNull(provider), Objects.requireNonNull(model),
                validFrom, Objects.requireNonNull(currency), input, output, cached, caller.principalId());
        audit.record(caller, AuditCategory.ADMIN, AuditPlane.CONTROL, "MODEL_PRICE_ADDED", null, "model_price",
                created.provider() + "/" + created.model(), null, null, Map.of("currency", created.currency()));
        return ResponseEntity.status(HttpStatus.CREATED).body(PriceDto.of(created));
    }

    private static @Nullable BudgetTarget target(@Nullable UUID workspaceId, @Nullable UUID agentResourceId,
                                                 @Nullable UUID principalId, List<FieldViolation> errors) {
        if (agentResourceId != null && principalId != null) {
            errors.add(new FieldViolation("principalId", "cannot be combined with agentResourceId"));
            return null;
        }
        if (agentResourceId != null) {
            if (workspaceId == null) {
                errors.add(new FieldViolation("agentResourceId", "requires a workspace route"));
                return null;
            }
            return new BudgetTarget.Agent(workspaceId, agentResourceId);
        }
        if (principalId != null) {
            return new BudgetTarget.Principal(workspaceId, principalId);
        }
        return workspaceId == null ? new BudgetTarget.Global() : new BudgetTarget.Workspace(workspaceId);
    }

    static List<PointDto> daily(List<UsageBucket> hours) {
        Map<Instant, long[]> sums = new TreeMap<>();
        Map<Instant, Map<String, Long>> costs = new TreeMap<>();
        for (UsageBucket h : hours) {
            Instant day = h.bucketStart().truncatedTo(ChronoUnit.DAYS);
            long[] acc = sums.computeIfAbsent(day, k -> new long[4]);
            UsageTotals t = h.totals();
            acc[0] += t.calls();
            acc[1] += t.inputTokens();
            acc[2] += t.outputTokens();
            acc[3] += t.cachedInputTokens();
            Map<String, Long> c = costs.computeIfAbsent(day, k -> new TreeMap<>());
            t.costMicrosByCurrency().forEach((cur, micros) -> c.merge(cur, micros, Long::sum));
        }
        List<PointDto> out = new ArrayList<>(sums.size());
        sums.forEach((day, acc) -> out.add(new PointDto(day,
                TotalsDto.of(new UsageTotals(acc[0], acc[1], acc[2], acc[3], costs.get(day))))));
        return out;
    }

    private static @Nullable String name(List<FieldViolation> errors, String field, @Nullable String raw) {
        String v = raw == null ? "" : raw.strip();
        if (!NAME.matcher(v).matches()) {
            errors.add(new FieldViolation(field, "is required and must match " + NAME.pattern()));
            return null;
        }
        return v;
    }

    private static long price(List<FieldViolation> errors, String field, @Nullable Long value, boolean required) {
        if (value == null) {
            if (required) {
                errors.add(new FieldViolation(field, "is required"));
            }
            return 0L;
        }
        if (value < 0 || value > MAX_PRICE_MICROS) {
            errors.add(new FieldViolation(field, "must be between 0 and " + MAX_PRICE_MICROS));
            return 0L;
        }
        return value;
    }
}
