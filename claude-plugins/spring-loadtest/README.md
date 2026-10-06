# spring-loadtest — Claude Code plugin

Load testing for Spring Boot projects with Grafana k6, driven by an agent.

| Part | What it does |
|---|---|
| MCP server `spring-loadtest` | Tools `loadtest_discover`, `loadtest_generate`, `loadtest_schema`, `loadtest_run`, `loadtest_report`, `loadtest_compare`, `loadtest_modes` over the generator in this repository |
| Skill `loadtest-generate` | Create or refresh a suite for a project and iterate until `smoke` passes |
| Skill `loadtest-analyze` | Explain a run: report, baseline comparison, Grafana/Prometheus app metrics, JFR hot spots → ranked findings |
| Skill `loadtest-capacity` | Stress and breakpoint runs to find the sustainable rate per API and for the mix |
| Agent `load-test-engineer` | Owns the whole workflow, including CI wiring (Maven, Gradle, JUnit 5) |

## Install

Requirements: JDK 25, Maven 3.9 (to build the generator once, offline), [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/)
for runs, Docker for the optional Grafana stack.

```
git clone https://github.com/davialos/springAIMcpServer && export LOADTEST_HOME=$PWD/springAIMcpServer
# in Claude Code:
/plugin marketplace add davialos/springAIMcpServer
/plugin install spring-loadtest@springaimcpserver
```

The MCP server is started with `$LOADTEST_HOME/scripts/loadtest-mcp.sh --root <the directory Claude Code runs in>`;
every path a tool receives must lie inside that root. `LOADTEST_HOME` must be set in the environment Claude Code
starts from. Without the plugin, the server can be added directly:

```
claude mcp add spring-loadtest -- $LOADTEST_HOME/scripts/loadtest-mcp.sh --root "$PWD"
```

## Use

Ask for it in plain words: "load test this Spring project", "why is `GET /orders/{id}` slow under load?", "how much
traffic can the checkout API take?". Runs send real HTTP traffic and create test rows through the application's
own create endpoints; the agent confirms the target before the first run and never runs against production.

Design: `docs/lld/16-load-test-generator.md`; user guide: `docs/integration/load-testing-guide.md`.
