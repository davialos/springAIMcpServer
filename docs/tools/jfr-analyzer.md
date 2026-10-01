# JFR analyzer (`spring-ai-mcp-server-common-jfr-analyzer`)

A command-line developer tool. It reads a Java Flight Recorder file (`.jfr`) and writes an **HTML report** and a
**JSON report**. Each cost (CPU time, allocated bytes, lock contention, parking, blocking I/O, exceptions) is pinned to
the **method and source line in the packages you name**, so the report leads straight to the code to change.

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
| `--format html,json` | Either or both (default both). |
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
- **Self-contained HTML.** No external script, style or font, so the report works offline and can be attached to a
  ticket. Charts are drawn in the browser from embedded data. Every table is plain HTML. Light and dark themes;
  usable at phone width.
- **Tests** record a real JFR file from a workload with one known hot spot per cost kind
  (`src/test/.../sample/SampleWorkload`). They assert that each section points at exactly that method and line.
