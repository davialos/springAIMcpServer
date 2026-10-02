# JFR analyzer (`spring-ai-mcp-server-common-jfr-analyzer`)

A command-line developer tool. It reads a Java Flight Recorder file (`.jfr`) and pins each cost (CPU time,
allocated bytes, lock contention, parking, blocking I/O, exceptions) to the **method and source line in the packages
you name**, so the report leads straight to the code to change. One run writes four files:

| File | For | Content |
|---|---|---|
| `<name>.html` | engineers | Interactive report: hot spots with lines, call paths and charts. |
| `<name>.json` | tooling | The complete analysis, machine-readable (`meta.schemaVersion` = `jfr-analyzer/report/2`). |
| `<name>.xlsx` | teams, leadership | Excel dashboard (health banner, KPI tiles, top issues, recommendations, native charts) plus filterable detail sheets. |
| `<name>-summary.json` | sharing | Status, score, key metrics, findings and top hot spots, without stacks, thread names, paths, endpoints or command lines (`jfr-analyzer/summary/1`). |

It is not part of the starter, is not in the BOM, and no host ever depends on it. It lives in the reactor so the
offline build compiles and tests it.

## Run it

```
# 1. Record (any JDK 11+ application; settings=profile samples more often than the default)
java -XX:StartFlightRecording:settings=profile,filename=app.jfr \
     -XX:FlightRecorderOptions:stackdepth=256 -jar app.jar
#    or attach to a running JVM:
jcmd <pid> JFR.start settings=profile duration=5m filename=app.jfr

# 2. Analyze (the script builds the analyzer once, offline, then runs it on JDK 25)
scripts/jfr-analyze.sh app.jfr -p com.acme.orders -p com.acme.billing -o reports/
```

| Option | Meaning |
|---|---|
| `-p, --package <prefix>` | Package (or class) to attribute costs to. Repeatable or comma-separated. `com.acme` matches `com.acme.Foo` and `com.acme.x.Bar`, not `com.acmex.Baz`; `com.acme.*` and `com/acme` work too. Without `-p`, every frame counts as application code. |
| `-x, --exclude <prefix>` | Never attribute to this package, even inside `-p` (e.g. generated proxies). |
| `-o, --output <dir>` | Output directory (default: next to the recording). |
| `-n, --name <base>` | File name without extension (default `<recording>-report`). |
| `--format html,json,xlsx,summary` | Any subset (default: all four). |
| `--top <n>` / `--stacks <n>` / `--depth <n>` | Rows per list (25), call paths per hot spot (5), frames per path (24). |
| `--compact-json` | Do not indent the JSON. |

The console prints the hottest line, the top allocation site, the most-blocked site and every finding. Exit code is
0 on success, 1 if the file cannot be analyzed, and 2 on a usage error.

## How attribution works

For each event that has a stack trace, the analyzer walks from the top frame (where the thread was) down. The first
frame whose class is in a requested package is the **hot spot**: the line of your code that ran, or that called the
JDK or library code that ran. So a sample inside `HashMap.resize` called from `OrderService.merge` is charged to
`OrderService.merge(OrderService.java:118)`, and the report shows `HashMap.resize` under **"Where the cost was
paid"**. Events with no frame in your packages are listed separately ("not reached through your packages"), so you
can see what fraction of the cost you can act on.

Locations are printed in stack-trace form (`com.acme.Foo.bar(Foo.java:42)`). IDE consoles make that form clickable,
and IntelliJ's *Analyze Stack Trace* accepts it.

Each hot spot carries:
- its hottest **lines**;
- the **top frames** where the cost was paid (`(self)` when the method itself was running);
- a per-section **breakdown**: allocated type, monitor class, blocker class, path or host:port, or thrown class;
- **threads**;
- the most common **call paths**, with your frames highlighted.

Separately, each section ranks your methods **inclusively** (anywhere on the stack), which finds expensive entry
points.

Exception events are recorded inside the throwable's constructor, so leading constructors of `*Exception`, `*Error`
and `*Throwable` classes are skipped: the hot spot is the method that created the exception.

## What the report contains

| Section | JFR events | Content |
|---|---|---|
| CPU | `jdk.ExecutionSample` (`jdk.CPUTimeSample` if only that was recorded), `jdk.NativeMethodSample`, `jdk.CPULoad` | Hot methods and lines, inclusive ranking, thread states, JVM and machine CPU over time, samples per second (all threads vs your packages). |
| Memory | `jdk.GCHeapConfiguration`, `jdk.GCHeapSummary`, `jdk.GCHeapMemoryUsage`, `jdk.MetaspaceSummary`, `jdk.ObjectAllocationSample` (or the TLAB events), `jdk.ThreadAllocationStatistics`, `jdk.AllocationRequiringGC`, `jdk.OldObjectSample` | Max, initial, peak used and committed heap. Live set after GC (min/avg/max/first/last) and its least-squares growth per minute. Metaspace. Total allocated and allocation rate. Heap-over-time and allocation-rate charts. Allocation hot spots by type. Allocations that triggered a GC. Long-lived objects (leak candidates). |
| GC | `jdk.GarbageCollection`, `jdk.GCHeapSummary`, `jdk.GCConfiguration` | Collectors, count and frequency, total, max, avg and p50/p95/p99 pause, overhead (% of time paused), per collector, per cause, the longest pauses with heap before/after, a pause timeline. |
| Threads | `jdk.JavaMonitorEnter`, `jdk.JavaMonitorWait`, `jdk.ThreadPark`, `jdk.ThreadSleep`, `jdk.VirtualThreadPinned`, `jdk.JavaThreadStatistics` | Lock contention hot spots with monitor class and the threads holding the lock. Parks by blocker class. `wait`, `sleep`, virtual-thread pinning. Blocked time per thread. Thread counts. |
| I/O | `jdk.FileRead/Write`, `jdk.SocketRead/Write` | Slow I/O hot spots by path or endpoint, plus bytes. |
| Exceptions | `jdk.JavaExceptionThrow`, `jdk.JavaErrorThrow`, `jdk.ExceptionStatistics` | Throwables created per second, and throw sites by thrown class. |
| Findings | all of the above | Rule-of-thumb conclusions (CRITICAL / WARNING / INFO). Each states the measured value and points at a location when one is responsible. Thresholds are in `FindingsEngine`. |

Two kinds of event are off or thresholded in the stock settings:
- `jdk.JavaExceptionThrow` is off; add `jdk.JavaExceptionThrow#enabled=true` to `-XX:StartFlightRecording` to see
  throw sites.
- Blocking events are recorded only above a threshold: 20 ms in `default` and 10 ms in `profile`.

## Executive summary (status, score, key metrics)

Every output starts from the same executive summary (`model.ExecutiveSummary`), so the Excel dashboard, the HTML
banner and both JSON files always agree:

- **Health score** = 100 − 25 per CRITICAL finding − 8 per WARNING finding (floor 0).
- **Status**: RED with any critical finding or a score under 50, AMBER with any warning or a score under 80, else
  GREEN.
- **Key metrics** carry a stable `id` (for tracking across runs), the value and unit, a display string, a
  RED/AMBER/GREEN/UNKNOWN status and one sentence on why it matters. They are judged against the same thresholds as
  the findings (`FindingsEngine`), so a metric shown AMBER or RED always has a matching finding:

| Metric id | AMBER at | RED at |
|---|---|---|
| `cpu.jvmAvgPercent` | machine CPU peak ≥ 90 % | — |
| `cpu.hottestMethodPercent` | 15 % of samples | 35 % |
| `gc.overheadPercent` | 5 % of time paused | 15 % |
| `gc.maxPauseMs`, `gc.p99PauseMs` | 200 ms | 1 000 ms |
| `heap.liveSetPercentOfMax` | 70 % of max heap after GC | 85 % |
| `allocation.rateBytesPerSec` | 1 GiB/s | — |
| `threads.lockBlockedMs` | blocked time ≥ 5 % of the recording | — |
| `exceptions.perSecond` | 1 000/s | — |
| `threads.virtualPinnedEvents` | any | — |

- **Top issues** restate CRITICAL and WARNING findings as impact plus next action. **Recommendations** are ordered
  next steps, each naming the line to start at.

## Excel workbook

| Sheet | Content |
|---|---|
| Dashboard | Health banner and headline, 8 KPI tiles (status-colored, with label and icon so color is never the only cue), top issues with the line to look at, recommendations, 6 native charts (heap over time with after-GC points and max heap, CPU load, GC pauses, allocation rate, top CPU hot spots, top allocation sites), top hot spots per area with data bars. Links jump to the detail sheets. |
| Findings | Every finding with severity, area, impact, what to do and the full location; filterable. |
| Key metrics | The executive-summary metrics with status, value, raw value, unit and meaning. |
| Hot spots / Hot lines | Every hot spot (and hottest line) of every area in one filterable table: cost in samples, MiB, ms or events, share, self %, top frame, top detail, top thread. |
| GC / Memory / Threads | The section numbers, plus per-collector, per-cause, longest-pause, allocated-type, lock-owner and blocked-thread tables. |
| Recording | File, time range, JVM, OS, CPU and JVM arguments (secrets masked), and event counts. |
| Chart data | The numbers the dashboard charts draw. |

Charts are native Excel charts with cached values, so they render in Excel, LibreOffice, Google Sheets and Numbers
and stay editable. Stack traces are not in the workbook; they stay in the HTML and JSON.

## Sharing and privacy

- **Secrets:** JVM and application arguments are masked before they reach any output (`collect.Redactor`). This
  covers values of `*password*`, `*secret*`, `*token*`, `*apikey*`/`*api.key*`, `*credential*`, `*private.key*` and
  `*access.key*` arguments, and passwords in URLs (`user:****@host`).
- **What the summary JSON leaves out:**
  - stack traces and thread names;
  - the recording's directory (it keeps the file name);
  - file paths and socket endpoints;
  - command lines.
- **What it keeps:** code locations in the requested packages, which is what a reader needs to act.
- **What the Excel workbook leaves out:** stack traces and the recording's directory.

## JSON

The JSON is the same model as the HTML (`model.AnalysisReport`). Units are fixed:
- weights carry their section's `unit` (`samples`, `bytes`, `nanos` or `events`);
- times are milliseconds unless the field name says otherwise;
- percentages run from 0 to 100.

It is rendered through `core.json.CanonicalJson`, the codebase's single JSON writer (ADR-0020). Keys are sorted and
the output is deterministic, so two reports diff cleanly. By default it is indented; `--compact-json` writes it as
one line.

## Design notes

- **One pass, streaming.** `RecordingFile.readEvent()`, never `readAllEvents`. Memory grows with the number of
  distinct stacks, not with file size. Stacks and methods are cached by identity, since the parser shares
  constant-pool objects within a chunk. Call paths are capped at 4,096 distinct paths per method.
- **JDK-only at run time.** The analyzer jar plus the core jar, whose `CanonicalJson` needs only the JDK. Fields are
  read defensively (`Fields`), because event fields differ between JDK versions.
- **Excel without Apache POI.** `report.xlsx` is a small OOXML writer covering what the dashboard needs: inline
  strings, shared styles, merges, frozen panes, autofilters, data bars, internal links, and scatter and bar charts.
  POI would bring roughly 20 MB of transitive jars into `offline-repo` and the run-time class path. Parts follow
  the schema's element order; entries carry a fixed timestamp, so equal content gives equal bytes.
  `XlsxWorkbookTest` checks package integrity (content types, relationships, well-formed XML, element order). The
  generated workbook is also verified to open in LibreOffice Calc and openpyxl.
- **Self-contained HTML.** No external script, style or font, so the report works offline and can be attached to a
  ticket. Charts are drawn in the browser from embedded data. Every table is plain HTML. Light and dark themes;
  usable at phone width.
- **Tests** record a real JFR file from a workload with one known hot spot per cost kind
  (`src/test/.../sample/SampleWorkload`). They assert that each section points at exactly that method and line.
