# MCP config templates

Replace `/ABSOLUTE/PATH/TO` with where you cloned the repository - or let `local-dev/bin/devctl setup` write the exact
files for your machine into `~/.localdev/agents/` (and `devctl agent-setup <agent> --install` merge them into the agent's
config). Guide: [../docs/mcp-agents.md](../docs/mcp-agents.md).

| File | Agent | Goes to |
|---|---|---|
| `mcpServers.json` | Cursor, Antigravity, Claude Desktop, Windsurf, Gemini CLI | the agent's MCP JSON (merge the `devctl` entry) |
| `vscode.json` | VS Code agent mode | `.vscode/mcp.json` or the user MCP configuration |
| `codex.toml` | OpenAI Codex CLI | `~/.codex/config.toml` |
| `http.json` | any agent that takes a URL | needs `devctl dashboard start` |
