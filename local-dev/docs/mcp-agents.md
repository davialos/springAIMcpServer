# Control your local environment from any coding agent (MCP)

`devctl` is an MCP server. Once connected, Claude Code, Cursor, Antigravity, VS Code, Claude Desktop, Windsurf,
Gemini CLI or Codex can build any repo@branch, deploy it (Spring Boot jars, WARs on JBoss EAP), start and stop
services, read and search logs, run load tests and profile JVMs on **your machine**. Use plain requests like
*"ship feature/login of orders and tell me when it's healthy"*.

The same tools power the **Assistant** tab in the devctl dashboard (Claude inside the dashboard).

```
 coding agent ──stdio──▶ local-dev/bin/devctl-mcp ─┐
 (or URL)     ──HTTP───▶ dashboard :8765/mcp ──────┤   36 tools · 5 resources · 4 prompts
 dashboard Assistant tab ──────────────────────────┘
                                                   ▼
             git worktrees · builds · deploys · process-compose · JBoss · logs · k6 · JFR
```

## 1. Quick start (macOS)

```bash
git clone <this repo> && cd springAIMcpServer        # or: git pull
brew bundle --file local-dev/Brewfile                 # git, maven, JDKs, process-compose, k6, python, OrbStack
local-dev/bin/devctl setup --with-ai --install claude-code,cursor,antigravity
```

`setup` does everything, idempotently (run it again any time):

1. **Configuration.** Creates `~/.localdev/config.json`. The workspace defaults to the folder you cloned this repo
   into (`--workspace ~/work` to change it), and every git repo in that folder becomes available.
2. **Environment for agents.** Saves your shell's `PATH`/`JAVA_HOME` to `~/.localdev/env.sh`. Agents started from the
   Dock get a bare `PATH`, and the launcher sources this file so `mvn`, `java`, `k6`, `docker` and
   `process-compose` are found.
3. **Prerequisites.** Checks the tools devctl needs and prints the fix for anything missing.
4. **AI assistant** (`--with-ai`). Creates `local-dev/.venv` with the official `anthropic` SDK (needs Python ≥ 3.10).
5. **MCP self-test.** Starts the MCP server the way an agent would, and lists its tools and the repos it can see.
6. **Agent configs.** Writes ready-to-copy configs for every agent to `~/.localdev/agents/`. `--install` merges them
   into the agents you name, keeping your other servers and writing a `.bak` first.
7. **Dashboard.** Starts it in the background at <http://127.0.0.1:8765>, which also serves MCP over HTTP at `/mcp`.

Then restart or reload your agent and ask: **"use devctl - what services do I have and are they healthy?"**

> No setup at all? If you open this repository in **Claude Code**, **Cursor** or **VS Code**, the project files
> `.mcp.json`, `.cursor/mcp.json` and `.vscode/mcp.json` already point at `local-dev/bin/devctl-mcp`. Approve the
> server when the agent asks.

## 2. Connect your agent

The **stdio** server is `<repo>/local-dev/bin/devctl-mcp`. It needs no daemon, port or token, and every agent
supports it. `devctl agent-setup` prints the exact snippets for your machine, the dashboard's **Agents** tab has
copy buttons, and `devctl agent-setup <agent> --install` writes the snippet into the agent's config for you.

### Claude Code
```bash
claude mcp add --scope user devctl -- /ABS/PATH/springAIMcpServer/local-dev/bin/devctl-mcp
claude mcp list                     # devctl: ... ✓ Connected
```
In this repository, the committed `.mcp.json` already works: run `claude` in the repo root and approve `devctl`.
HTTP alternative: `claude mcp add --scope user --transport http devctl http://127.0.0.1:8765/mcp`.

### Cursor
`~/.cursor/mcp.json` (all projects) or `.cursor/mcp.json` (this project, already committed):
```json
{ "mcpServers": { "devctl": { "command": "/ABS/PATH/springAIMcpServer/local-dev/bin/devctl-mcp", "args": [] } } }
```
Settings → MCP shows `devctl` with its tools. Use it in Agent mode.

### Google Antigravity
Agent panel → **⋯** → **MCP Servers** → **Manage MCP Servers** → **View raw config** opens `mcp_config.json`
(typically `~/.gemini/antigravity/mcp_config.json`). Add:
```json
{ "mcpServers": { "devctl": { "command": "/ABS/PATH/springAIMcpServer/local-dev/bin/devctl-mcp", "args": [] } } }
```
Save, then **Refresh** in the MCP Servers panel. Antigravity's HTTP form uses `serverUrl`, not `url`:
`{"mcpServers": {"devctl": {"serverUrl": "http://127.0.0.1:8765/mcp"}}}`.
`devctl agent-setup antigravity --install` writes the default path. If your Antigravity version shows a different
file under "View raw config", paste the snippet there instead.

### VS Code (Copilot agent mode)
`.vscode/mcp.json` (committed for this repo), or **MCP: Open User Configuration** for all workspaces. Note that the
top-level key is `servers`:
```json
{ "servers": { "devctl": { "type": "stdio", "command": "/ABS/PATH/springAIMcpServer/local-dev/bin/devctl-mcp", "args": [] } } }
```

### Claude Desktop · Windsurf · Gemini CLI
Same `mcpServers` JSON as Cursor, in:
- **Claude Desktop:** `~/Library/Application Support/Claude/claude_desktop_config.json`. Restart the app afterwards.
- **Windsurf:** `~/.codeium/windsurf/mcp_config.json`.
- **Gemini CLI:** `~/.gemini/settings.json`.

### OpenAI Codex CLI
`~/.codex/config.toml`:
```toml
[mcp_servers.devctl]
command = "/ABS/PATH/springAIMcpServer/local-dev/bin/devctl-mcp"
args = []
```

### Any other agent
- If it can start a command, use the stdio path above.
- If it takes a URL, run `devctl dashboard start` and use `http://127.0.0.1:8765/mcp`. This is MCP Streamable HTTP,
  stateless, with JSON responses.
- `devctl autostart install` keeps the dashboard running from login, via a macOS launchd agent.

Templates with placeholder paths are in [`local-dev/agents/`](../agents/).

## 3. What the agent can do

### Tools
| Area | Tools (effect: **R** read, **W** changes local state, **D** removes something) |
|---|---|
| Orientation | `status` R · `diagnose` R (process, health, deploy, live metrics, error lines, last build/load run/JFR verdict, hints) · `config_get` R |
| Repos & builds | `list_repos` R · `list_branches` R · `build` W · `wait_build` R · `build_status` R · `list_builds` R |
| Run | `ship` W (build → deploy → wait healthy, one call) · `deploy` W · `undeploy` D · `wait_healthy` R · `service_control` W · `stack_control` W · `infra_control` W · `sync_project` W |
| Logs | `logs` R · `search_logs` R (regex with context) |
| Performance | `perf_snapshot` R · `perf_readiness` R |
| Load tests | `loadtest_discover` R · `loadtest_create` W · `loadtest_run` W · `loadtest_result` R · `loadtest_runs` R · `loadtest_stop` W |
| Profiling | `jfr_list_jvms` R · `jfr_snapshot` W · `jfr_record` W · `jfr_list` R · `jfr_analyze` W · `jfr_summary` R |
| Configuration | `config_set` W (services / repos / stacks, validated) · `config_remove` D · `config_set_workspace` W |

Effects are published as MCP tool annotations (`readOnlyHint`, `destructiveHint`). Agents that honour them ask before
non-read calls, and your agent's own permission settings still apply. Everything happens locally; nothing reaches
shared environments.

### Resources
`devctl://status`, `devctl://config`, `devctl://builds/recent`, `devctl://loadruns/recent`, `devctl://guide`, and
the template `devctl://logs/{name}`.

### Prompts (slash commands in most agents)
| Prompt | Does |
|---|---|
| `ship-branch` (repo, branch, service) | build, deploy, verify health; on failure, the exact error line and a fix |
| `investigate-service` (service) | diagnose → logs → metrics → JFR → root cause with evidence |
| `performance-check` (service, vus, duration) | readiness → discover → load test → p95/errors/hot spots with `file:line` |
| `onboard-project` (repo) | reads the build files, registers the repo and service, ships it, confirms health |

The server also sends **instructions** at `initialize` (how to orient, prefer `ship`/`diagnose`, quote evidence), so
agents use the tools well without extra prompting.

### Example requests
- "What's running locally and is anything unhealthy?"
- "Onboard the `billing` repo from my workspace. It's a WAR on JBoss, deploy it with the other billing WARs."
- "Ship `feature/discounts` of orders as orders-api. If the build fails, tell me why."
- "orders-api returns 500s on `/api/orders/{id}`. Find the exception in the logs and explain it."
- "Load test orders-api at 50 VUs for 2 minutes and tell me the slowest line of code."
- "Start grafana and loki, then give me the Explore query for orders-api errors."

## 4. The dashboard Assistant (Claude inside devctl)

The **Assistant** tab is a chat with Claude that uses the same 36 tools.
- **Read tools run immediately.**
- **Write and destroy calls pause on an *Approve / Decline* card**, so nothing changes on your machine without a click.
  `ai.confirm_writes: false` turns this off.
- **Setup:** `devctl setup --with-ai` (Python ≥ 3.10), then credentials and a dashboard restart:
  ```bash
  export ANTHROPIC_API_KEY=sk-ant-...      # or: ant auth login (profile, also works under autostart)
  local-dev/bin/devctl dashboard stop && local-dev/bin/devctl dashboard start
  ```
- **Model settings:** under `ai` in `~/.localdev/config.json`. The model defaults to `claude-opus-5-5`. Effort defaults
  to `medium`; use `high` for harder investigations. `max_steps` defaults to 15. With `fallbacks`, the server retries a
  declined request on Anthropic's recommended fallback model.
- **Conversations** stay in the dashboard process memory and are never written to disk.

The assistant is optional. The MCP server needs neither the SDK nor an API key, because your coding agent brings its
own model.

## 5. Security model
- **Local only.**
  - The stdio server is a child process of your agent, running as you.
  - The HTTP endpoint binds `127.0.0.1`.
  - Both the endpoint and the dashboard check `Host` to block DNS rebinding.
  - `/mcp` rejects foreign `Origin`s, so a web page can't drive it.
  - For an extra shared secret, set `"mcp": {"http_token": "<random>"}`; requests then need
    `Authorization: Bearer <random>`. Some agents still drop custom headers, which is why stdio stays the default.
- **What an agent can do is what devctl can do.**
  - It can run build commands from your config, start and stop local processes, and edit `config.json` (validated keys
    only).
  - Treat `config.json` like a shell profile.
  - Keep your agent's tool-approval mode on for `W`/`D` tools if you want a human in the loop.
- **No secrets in generated files.** No API key is written to agent configs, `env.sh` or the launchd plist.

## 6. Troubleshooting
| Symptom | Fix |
|---|---|
| Agent shows devctl with 0 tools, or "failed to start" | Run `local-dev/bin/devctl mcp-test`. Use an **absolute** path in global configs, and check `chmod +x local-dev/bin/*`. |
| Tools fail with "mvn/java/k6/process-compose not found" | Rerun `devctl setup` from a terminal where those commands work; it refreshes `~/.localdev/env.sh`. |
| `jcmd`/`jps` not found (Profiling) | Install a JDK (`brew install openjdk@21`) or set `java_home` in the config. |
| Repo list empty | Point the workspace at the folder holding your repos: `devctl setup --workspace ~/work`, or ask the agent to call `config_set_workspace`. |
| HTTP `/mcp` 403 | The request carried a foreign `Origin`; use stdio or a client that doesn't send one. 401 means `mcp.http_token` is set and the header is missing. |
| Antigravity doesn't list the server | Edit the file shown under *View raw config*, use `serverUrl` for HTTP, then click Refresh. |
| Assistant tab says "not ready" | The hint names it: missing SDK (`setup --with-ai`) or credentials (`ANTHROPIC_API_KEY` / `ant auth login`, then restart the dashboard). |
| Long `ship`/`wait_build` calls time out in the agent | Raise the agent's MCP tool timeout (Claude Code: `MCP_TOOL_TIMEOUT`), or pass `timeout_seconds` and call `wait_build` again. |

Logs: the dashboard writes to `~/.localdev/dashboard.log`. The stdio server writes diagnostics to stderr, which the
agent's MCP log shows.

## 7. Uninstall
```bash
local-dev/bin/devctl autostart uninstall; local-dev/bin/devctl dashboard stop
claude mcp remove --scope user devctl            # and delete the "devctl" entry from other agents' configs (.bak files kept)
rm -rf ~/.localdev local-dev/.venv               # config, state, builds stay in builds_dir (default ~/localdev)
```

## Verified
- **Claude Code 2.1:** connected over stdio (`--mcp-config`) and over HTTP `/mcp`; it called `list_repos`,
  `config_get` and `status` and answered correctly.
- **Assistant loop:** tested with the real `anthropic` SDK against a fake Messages API, covering the request shape,
  the tool round trip and approvals.
- **Other agents:** configs follow each agent's documented format but haven't been run against the live apps
  (docs/open-questions.md OQ-LD-2).
