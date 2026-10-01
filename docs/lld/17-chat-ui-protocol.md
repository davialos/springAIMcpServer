# LLD-17: Chat Interaction & UI Tree Protocol

| Field | Value |
|-------|-------|
| Status | Draft v1 |
| Owner agent | agent-runtime-designer (with control-plane-designer for the client side) |
| Module(s) | `core` (UI model, validator, ports), `persistence` (surfaces, interrupts, interactions), `ai` (interrupts, control tools), `webmvc` (endpoints), `autoconfigure` (wiring, properties), `review-ui` (renderer; module not created yet, OQ-08/OQ-15) |
| Related features | F-43, F-45, F-51, F-52, F-53, F-57, F-58, F-59 |
| Related ADRs | ADR-0008, ADR-0009, ADR-0015, ADR-0021, ADR-0023 |
| Replaces in part | LLD-13 §3 (event table, `dai-stream/1`) and LLD-11 §7 (payload shapes) |
| Machine-readable contract | [`docs/schemas/`](../schemas/): `dai-ui-1`, `dai-stream-2`, `dai-tools-1` (JSON Schema 2020-12), `negative-corpus.json`, `validate.mjs` |
| Input | Product-owner request (2026-09-30): a best-practice API for chat interactions where the UI renders a JSON tree and sends user input back (confirmations and actions, input to an AI query, UI instructions) through one consistent API |

> **Status of the code (2026-09-30).** Of the `dai-stream/1` events only `turn.start`, `text.delta`, `usage`, `turn.end`
> and `error` are emitted (`DefaultAgentInvoker`). `tool.call`, `tool.result`, `ui.component` and `proposal.*` exist in
> `StreamEvent` but nothing creates them, and `ui.component.payload` is a JSON string inside JSON. A proposal created by a
> tool is reviewed through `/dynamic-ai/api/proposals` with no link from the stream. The only input is
> `POST …/chat[/stream]` with free text. This document is the target design; §20 lists the phased work.
>
> **Verification.** Every JSON example in this document is validated against the schemas by `docs/schemas/validate.mjs`
> (`json`, `sse` and `http` blocks); the reducer in §14 and the Spring AI sketch in §13 were run and compiled against the
> vendored jars (Spring AI 2.0.1, Reactor 3.8.7, Jackson 3.1.5). Behaviour that could not be verified without running a model
> is marked **verify** and has a test in §19.

## 0. At a glance

1. **One way in.** `POST /dynamic-ai/api/agents/{slug}/interactions` takes one JSON envelope with four types: `message`, `action`, `respond`, `cancel` (§6).
2. **One way out.** A typed event stream (`dai-stream/2`) as SSE, or the same events as one JSON batch (§8).
3. **What the user sees is a surface:** a JSON tree of catalog nodes, a data model and server-declared actions (`dai-ui/1`, §5). No HTML, no script, no URL invented by a model.
4. **The client never invents behaviour.** It renders nodes and, on a click, sends `(surface, revision, actionId, values)`. What an action does, and whether it is allowed, comes from the server's own record (§5.7, §11).
5. **When the AI needs the user it suspends:** it raises an *interrupt*, ends the turn and holds no thread, connection or model state. The answer arrives as a new interaction, on any node (§7).
6. **The AI can ask but never authorize.** Only a server-built proposal review can lead to a write (§7.6, ADR-0009).
7. **UI instructions** are `action`s handled by the server without the model (paging, sorting, refresh), plus a closed, host-registered set of commands the AI may ask the UI to run (§5.7, §5.8).
8. **Reload equals live.** `GET …/state` returns one `state.snapshot` event that the same reducer folds as it folds a live stream (§8.4).

```
 ┌──────────────┐  POST /interactions  (message | action | respond | cancel)       ┌───────────────────────────┐
 │  Client      │ ───────────────────────────────────────────────────────────────► │ webmvc: interaction gate  │
 │  (Web Comp., │                                                                   │ authn · authz · limits ·  │
 │   React, …)  │ ◄──────────────  SSE or JSON batch: dai-stream/2 events  ──────── │ validate · idempotency    │
 │              │    turn.* text.* tool.* ui.* interrupt.* proposal.* error         └─────────────┬─────────────┘
 │  reducer +   │                                                                                 │ dispatch
 │  renderer    │  GET /conversations/{id}/state   (snapshot = fold(events))         ┌────────────┴────────────────┐
 └──────────────┘  GET /surfaces/{id}              (ETag = revision, poll hint)      │ message → agent run         │
                                                                                      │ respond → interrupt resolver│
                                                                                      │ action  → server-held handler│
                                                                                      │ cancel  → running turn      │
                                                                                      └────────────┬────────────────┘
                                                                   PostgreSQL: surfaces · interrupts · interactions · (events, P2)
```

| Method and path | Purpose | Phase |
|---|---|---|
| `POST /dynamic-ai/api/agents/{slug}/interactions` | every client → server input; `Accept` selects SSE or JSON | P1 |
| `GET /dynamic-ai/api/agents/{slug}/turns/{turnId}/events` | replay a turn (`Last-Event-ID`); exists, becomes cross-node in P2 | exists |
| `GET /dynamic-ai/api/conversations/{id}/state` | snapshot for reload and resume | P1 |
| `GET /dynamic-ai/api/surfaces/{id}` | one surface, `ETag` = revision, drives the `poll` hint | P1 |
| `POST /dynamic-ai/api/agents/{slug}/chat`, `…/chat/stream` | legacy aliases of a `message` interaction | exists, kept |
| `/dynamic-ai/api/proposals/**` | proposal API; unchanged, also used by the review inbox | exists |

**Reading guide.** Server developers: §5–§7, §10–§13. Client developers: §5, §8, §14. Security review: §11. Product owner: §0, §20, §22.

## 1. Purpose & responsibilities

**Owns:** the wire contract (requests, events, errors); the UI tree schema and its validator; the lifecycles and persistence of surfaces, interrupts and interactions; the control tools `ask_user` and `render_ui`; the rules a renderer must follow.

**Does not own:**
- writes: the proposal state machine, apply and versioning stay in LLD-11. This document only *presents* a proposal and *routes the user's decision* to it;
- the model loop, memory and guardrails (LLD-06) and tool authorization and result envelopes (LLD-07);
- visual design and the component implementation (LLD-08, `review-ui`);
- login, CSRF and transport configuration of the host (integration guide).

## 2. Context and current state

| Area | Today (code) | Gap | Target |
|---|---|---|---|
| Input | `ChatRequest{conversationId, message, clientRequestId}` | only free text; no answers, no UI events; de-duplication is node-local (OQ-42) | one envelope, four types, PostgreSQL idempotency |
| Output | `dai-stream/1`, five event types emitted | `tool.*`, `ui.component`, `proposal.*` never emitted | `dai-stream/2`, all emitted, surfaces as objects |
| UI payloads | LLD-11 §7: one flat component per event | no tree, no actions, no state, no updates | `dai-ui/1` surfaces |
| Questions | none; a turn can only end with text | the AI cannot ask structured questions | `ask_user` and interrupts |
| Confirmations | `POST /api/proposals/{id}:confirm`; the stream carries only `reviewUrl` | the UI must discover proposals itself | `proposal_review` surface in the stream, confirm via `action` |
| Reload | `GET /conversations/{id}/messages` returns text | surfaces and pending questions are lost | `state` snapshot |
| Replay | in-memory ring per node (256 events, 5 min) | breaks ADR-0021 behind a round-robin balancer | P2: PostgreSQL |
| Memory | USER, ASSISTANT, SYSTEM text only (V9, OQ-45) | no tool messages to carry a tool answer | answer framing as user text (§7.5) |

## 3. Design principles

| # | Principle | Rule | Source |
|---|---|---|---|
| 1 | One envelope in, one stream out | every input is an `Interaction`; every output an event | consistency |
| 2 | Declarative data, never code | closed node catalog; no HTML, script or style from any model | OWASP LLM05, default deny |
| 3 | The server owns every action | opaque action ids; handler, arguments and authority are server-held | threat model E5, T4 |
| 4 | Suspend, don't block | an interrupt ends the run; a human answers in minutes, so no thread or stream waits | ADR-0021 |
| 5 | Snapshot = fold(events) | one reducer serves live, replay and reload | AG-UI state snapshots |
| 6 | The model describes, the server renders | a question is a schema, a display is a model-safe tree; interactive nodes are server-only | security, reliability |
| 7 | Bind, don't copy | a model-authored table references a tool result by handle; the server copies the rows | threat I6, tokens |
| 8 | Tolerant reader, strict writer | clients ignore unknown events, fields and nodes (using `fallback`); the server emits only validated trees | evolvability |
| 9 | Fail the feature, not the host | any UI-layer failure degrades to plain text; chat keeps working | LLD-12 |
| 10 | Stateless by default | any replica serves any interaction; state lives in PostgreSQL | ADR-0021 |
| 11 | Accessible by construction | `alt`, `caption` and `label` are required; focus and live-region rules are part of the renderer contract | F-53, WCAG 2.2 AA |

## 4. Concepts and identifiers

| Concept | Definition | Id | Persisted in |
|---|---|---|---|
| Conversation | the user's thread with one agent | UUIDv7 `conversationId` | `dai_conversation` (optional), memory key hash |
| Turn | one agent run for one input; ends with `turn.end` | UUIDv7 `turnId` | `dai_agent_turn` |
| Interaction | one client → server input (`message`, `action`, `respond`, `cancel`) | UUIDv7 `interactionId`, client-generated | `dai_chat_interaction` |
| Surface | a rendered region: tree, data model, actions; has a `revision` | UUIDv7 `surfaceId` | `dai_chat_surface` |
| Node | one element of a tree | local id `[A-Za-z0-9_-]{1,64}` | inside the surface |
| Action | something a node can trigger; declared by the server | local id, unique per surface | declaration in the surface, handler server-side only |
| Interrupt | something the run needs from the user or the UI | UUIDv7 `interruptId` | `dai_chat_interrupt` |
| Command | an instruction from the server to the UI (`ui.command`) | UUIDv7 `commandId` | not persisted |
| Result handle | per-turn name of a tool result (`r1`, `r2`) | `r[0-9]{1,3}` | in memory for the turn |

Rules: persisted ids are UUIDv7 (`Ids.newId()`); every persisted object is scoped to its owner and workspace; a foreign or unknown id answers `404`, never `403`, so ids reveal nothing.


## 5. The UI tree (`dai-ui/1`)

### 5.1 Surface

A surface is one rendered region of the chat: a tree of nodes, a data model the tree reads and writes, and the actions the user may trigger. It is created by the server, owned by one user, and has a monotonically increasing `revision`.

<!-- validate: ui name=surface-question -->
```json
{
  "surfaceId": "0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "turnId": "0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70",
  "interruptId": "0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10",
  "revision": 1,
  "kind": "question",
  "origin": "model",
  "status": "active",
  "catalog": "dai-ui/1",
  "title": "Which customer?",
  "tree": {
    "id": "root",
    "type": "form",
    "props": {
      "submit": "a_submit",
      "cancel": "a_dismiss"
    },
    "children": [
      {
        "id": "q",
        "type": "text",
        "props": {
          "value": "There are two customers called ACME. Which one do you mean?",
          "variant": "title"
        }
      },
      {
        "id": "f_customer",
        "type": "select",
        "props": {
          "name": "customer",
          "label": "Customer",
          "bind": "/form/customer",
          "required": true,
          "options": [
            {
              "value": "4711",
              "label": "ACME GmbH (Berlin)"
            },
            {
              "value": "4712",
              "label": "ACME Ltd (Leeds)"
            }
          ]
        }
      },
      {
        "id": "buttons",
        "type": "stack",
        "props": {
          "direction": "horizontal",
          "gap": "sm"
        },
        "children": [
          {
            "id": "b_submit",
            "type": "button",
            "props": {
              "label": "Send",
              "action": "a_submit",
              "variant": "primary"
            }
          },
          {
            "id": "b_decline",
            "type": "button",
            "props": {
              "label": "Skip",
              "action": "a_decline",
              "variant": "link"
            }
          }
        ]
      }
    ]
  },
  "data": {
    "form": {}
  },
  "actions": {
    "a_submit": {
      "kind": "respond",
      "outcome": "accept",
      "submit": "/form"
    },
    "a_decline": {
      "kind": "respond",
      "outcome": "decline",
      "label": "Skip"
    },
    "a_dismiss": {
      "kind": "respond",
      "outcome": "cancel"
    }
  },
  "expiresAt": "2026-10-01T10:45:00Z"
}
```

| Member | Rule |
|---|---|
| `surfaceId`, `conversationId`, `turnId`, `interruptId` | UUIDs; `interruptId` is present when the surface answers an interrupt |
| `revision` | integer ≥ 1; +1 for every change (`ui.surface` replace, `ui.patch`, `ui.status`); the client ignores events with a lower or equal revision |
| `kind` | `display` (read-only content), `question`, `confirmation`, `proposal_review`, `status` |
| `origin` | provenance label, not an authoring right. `model`: the surface contains words written by the model; `server`: all text is server-authored; `host`: contributed by host code. Renderers must style `model` surfaces as the assistant's own content and never with system or review chrome (anti-spoofing) |
| `status` | `active`, then one of `completed`, `superseded`, `expired`, `closed`. Non-active surfaces are rendered read-only, actions disabled |
| `anchor` | UTF-16 code-unit offset into the assistant message text where the surface belongs; absent means after the text. Java `String.length()` and JS `string.length` agree, so both sides compute it the same way |
| `tree`, `data`, `actions` | §5.2, §5.3, §5.7 |
| `poll` | hint `{"after": "PT10S"}`: while `status` is `active`, re-fetch `GET /surfaces/{id}` with `If-None-Match` after this delay (state that changes without a turn, such as proposal approval) |
| `expiresAt` | after this instant actions are refused with `410` |
| `catalog` | always `dai-ui/1` for this version |

### 5.2 Node

```jsonc
{ "id": "n7", "type": "table", "props": { /* per type, closed */ }, "children": [ /* containers only */ ],
  "visibleWhen": { "path": "/page/hasMore", "truthy": true }, "fallback": "Orders: 101, 102" }
```

- `id` is unique within the surface and **stable across patches** (patches address nodes by id, never by array index).
- `props` are validated per type and **closed**: an unknown prop is rejected by the server. Clients ignore unknown props (tolerant reader).
- `children` exist only on container nodes (`x-container` in the schema).
- `fallback` (≤ 500 characters, plain text) is what a client that does not know the node type shows instead.
- Every node type has an **authoring class**: `model_safe` nodes may appear in a tree authored by a model (`render_ui`, §9.2); `server_only` nodes (every input, button, form and the proposal review) are created by trusted server code only.

### 5.3 Data binding and predicates

The data model is a JSON object. Nodes reference it with RFC 6901 JSON Pointers; there is no expression language.

| Construct | Meaning |
|---|---|
| `{"$data": "/rows"}` in a bindable prop | read: replaced by the value at the pointer when rendering. The pointer must resolve |
| `bind: "/form/customer"` on an input | two-way: the client keeps the edited value at the pointer in its **local** copy of the data model. The parent (`/form`) must exist |
| `submit: "/form"` on an action | when the action fires, the client sends the object at that pointer as `values` |
| `visibleWhen` | pure predicate: `{path, eq}`, `{path, ne}`, `{path, in}`, `{path, truthy}`, combined with `allOf`, `anyOf`, `not`, nested at most three levels |

Local edits are never sent except as `values` of an action. The server validates `values` itself (§6.6); the constraints on input nodes (`required`, `maxLength`, …) are generated from the same schema for convenience only.

### 5.4 Node catalog v1

| Type | Class | Children | Purpose and notes |
|---|---|---|---|
| `stack` | model_safe | yes | vertical or horizontal layout, gap, alignment |
| `card` | model_safe | yes | titled container with a tone |
| `section` | model_safe | yes | heading, optionally collapsible |
| `divider` | model_safe | no | separator |
| `text` | model_safe | no | plain text (default) or restricted Markdown (§5.5); variants body, caption, title, heading, mono |
| `badge` | model_safe | no | short label with a tone |
| `callout` | model_safe | yes | info, success, warning or danger message |
| `code` | model_safe | no | preformatted text with an optional language label; never executed |
| `link` | model_safe | no | `https:` or `mailto:` only; the client shows the full destination |
| `image` | model_safe | no | `https:` host in the allow-list or `attachment:`; `alt` required; never auto-loaded (§5.5) |
| `kv` | model_safe | no | label/value pairs |
| `table` | model_safe | no | caption required; rows bound to the data model; `onSort` and `onPage` are server-only props |
| `list` | model_safe | no | ordered or unordered items |
| `timeline` | model_safe | no | dated events |
| `entity_link` | model_safe | no | a record reference (`entity:` ref and id); the host's resolver turns it into a route, the server never sends a URL |
| `progress` | model_safe | no | value 0..1 or indeterminate; label required |
| `form` | server_only | yes | groups inputs; `submit` and `cancel` name actions (Enter, Esc) |
| `text_input` | server_only | no | string answer; length, pattern (server-authored), `email` or `uri` format; never a password field |
| `number_input` | server_only | no | number or integer with bounds |
| `select` | server_only | no | single choice, dropdown or radio |
| `multi_select` | server_only | no | several choices with min and max |
| `checkbox` | server_only | no | boolean, checkbox or switch |
| `date_input` | server_only | no | date or date-time |
| `button` | server_only | no | triggers an action; variants primary, secondary, danger, link |
| `suggestion_chips` | server_only | no | quick replies; each item triggers a `message` action the server created |
| `change_review` | server_only | no | the review of a change proposal: diff, form, delete or bulk presentation (§7.6) |

Reserved for later versions (do not reuse for other meanings): `tabs`, `tab`, `chart`, `icon`, `entity_picker`, `tree`, `grid`. The prop tables are in Appendix A and in `dai-ui-1.schema.json`.

`change_review` replaces the four LLD-11 §7.2 components: `record-diff` is `presentation: diff`, `record-form` is `form`, `delete-confirm` is `delete`, `bulk-change-table` is `bulk`. LLD-11 §7.1 display components map to `kv` (record-card, key-value), `table` (record-table), `timeline`, `entity_link`; `chart` is reserved.

### 5.5 Validation, sanitization and limits

Validation runs in three layers; **the server is authoritative, the client mirrors it for UX**.

1. **Limits first, on the parsed tree, iteratively.** Before any schema validation: size, node count, depth, string length. Schema validators recurse; an adversarial depth must not reach them (a 1000-deep tree overflows a generated validator, which was observed). Parse with a private `JsonMapper` built like `PolicyDocumentParser` (strict duplicate-key detection, fail on trailing tokens) and `StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(...)`.
2. **Structure** with `dai-ui-1.schema.json`. Node dispatch uses `if/then` on `type` so each node is validated exactly once; **do not change it to `oneOf` over the node definitions**, which is exponential in tree depth with recursive children.
3. **Semantics** (`UiTreeValidator`, the rules below; `validate.mjs` is the executable reference).

| # | Rule |
|---|---|
| S1 | node ids are unique within the surface |
| S2 | every action id a node references (`button.action`, `form.submit`, `form.cancel`, `table.onSort`, `table.onPage`, chip actions) is declared in `actions` |
| S3 | every `$data` pointer resolves; every `bind` and `submit` parent exists; `visibleWhen` paths resolve |
| S4 | limits: ≤ 400 nodes, depth ≤ 10, ≤ 256 KiB per surface and for `data`, ≤ 200 inline table rows, ≤ 40 actions, ≤ 8 surfaces per turn (all configurable, §13.5) |
| S5 | `respond` actions exist only on surfaces bound to an interrupt; a proposal review's `accept` action carries the proposal's `contentHash` as `digest` |
| S6 | `change_review`: `changed` equals `before !== after` for every field |
| S7 | strings contain no C0 or C1 control characters except tab and newline, and no bidirectional override or isolate characters (U+202A–202E, U+2066–2069), which spoof displayed values (Trojan-Source style). Zero-width characters are allowed; review diffs render them visibly |
| S8 | model-authored trees (`render_ui`) contain only `model_safe` nodes and none of the server-only props (`table.onSort`, `table.onPage`) |
| S9 | URLs: `link.href` scheme in `dynamic.ai.agent.chat.ui.link-schemes` (default `https`, `mailto`); `image.src` host in `image-hosts` (default empty, so only `attachment:`); `javascript:`, `data:`, `file:` never |

Text rendering: `text.value` is **plain text** unless `format: markdown`. Markdown is a restricted subset (paragraphs, emphasis, lists, inline code, code blocks, links under S9); raw HTML is never rendered; images in Markdown are not loaded. This is the LLD-13 §6 rule, now stated for every text node.

### 5.6 Patches

A surface changes by replacing it (`ui.surface`, higher revision) or by a patch. Patches are an optimization for large or frequently changing surfaces (a table gaining rows, a progress bar) and are phase P2; the model is defined now so ids and revisions are right from P1.

`ui.patch` carries `baseRevision`, the new `revision`, and any of three sections applied **atomically** in this order: `tree` (id-addressed operations), `data` (RFC 6902 JSON Patch over the data model), `actions` (declaration upserts, `null` removes).

| Tree op | Fields | Effect |
|---|---|---|
| `replace` | `id`, `node` | replaces the node with that id |
| `update` | `id`, `props` | shallow-merges props; a `null` value deletes the prop |
| `insert` | `parentId`, `index?`, `node` | adds a child (append when `index` is absent) |
| `remove` | `id` | removes the node and its subtree |
| `move` | `id`, `parentId`, `index?` | moves a node |

Rules: if the client's current revision is not `baseRevision` it **must not apply the patch** and re-fetches the surface (`GET /surfaces/{id}`); the validator runs on the resulting surface, so a patch can never produce a tree a full replace could not; data operations use pointers, tree operations use ids because arrays reorder.

<!-- validate: event name=ui-patch-rows -->
```json
{
  "type": "ui.patch",
  "surfaceId": "0198f1c1-2e40-7a15-9b26-3c4d5e6f7a81",
  "baseRevision": 1,
  "revision": 2,
  "data": [
    {
      "op": "add",
      "path": "/rows/-",
      "value": {
        "id": 103,
        "status": "OPEN",
        "total": "42.00"
      }
    },
    {
      "op": "replace",
      "path": "/page/hasMore",
      "value": false
    }
  ]
}
```

<!-- validate: event name=ui-patch-tree -->
```json
{
  "type": "ui.patch",
  "surfaceId": "0198f1c2-5d22-7c81-a192-b3c4d5e6f708",
  "baseRevision": 1,
  "revision": 2,
  "tree": [
    {
      "op": "update",
      "id": "review",
      "props": {
        "state": "APPLIED",
        "hostRevision": "JPA_VERSION:8"
      }
    },
    {
      "op": "remove",
      "id": "buttons"
    }
  ],
  "actions": {
    "a_confirm": null,
    "a_decline": null
  }
}
```

### 5.7 Actions

An action is declared in `surface.actions` and referenced by id from a node. **What the client sees** is a declaration for presentation and validation; **what the server holds** (the handler, its arguments and the authority) is stored separately and is never sent.

<!-- validate: ui name=surface-table -->
```json
{
  "surfaceId": "0198f1c1-2e40-7a15-9b26-3c4d5e6f7a81",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "turnId": "0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f",
  "revision": 1,
  "kind": "display",
  "origin": "model",
  "status": "active",
  "catalog": "dai-ui/1",
  "title": "Open orders for ACME GmbH",
  "anchor": 64,
  "tree": {
    "id": "root",
    "type": "card",
    "props": {
      "title": "Open orders for ACME GmbH"
    },
    "children": [
      {
        "id": "t1",
        "type": "table",
        "props": {
          "caption": "Open orders for ACME GmbH",
          "columns": [
            {
              "key": "id",
              "label": "Order",
              "align": "end"
            },
            {
              "key": "status",
              "label": "Status"
            },
            {
              "key": "total",
              "label": "Total",
              "format": "currency",
              "align": "end"
            }
          ],
          "rows": {
            "$data": "/rows"
          },
          "rowKey": "id",
          "hasMore": {
            "$data": "/page/hasMore"
          },
          "onPage": "a_more",
          "emptyText": "No open orders."
        }
      },
      {
        "id": "more",
        "type": "button",
        "visibleWhen": {
          "path": "/page/hasMore",
          "truthy": true
        },
        "props": {
          "label": "Show more",
          "action": "a_more",
          "variant": "secondary"
        }
      }
    ]
  },
  "data": {
    "rows": [
      {
        "id": 101,
        "status": "PAID",
        "total": "120.50"
      },
      {
        "id": 102,
        "status": "OPEN",
        "total": "80.00"
      }
    ],
    "page": {
      "hasMore": true
    }
  },
  "actions": {
    "a_more": {
      "kind": "event",
      "label": "Show more"
    }
  },
  "expiresAt": "2026-10-01T22:00:00Z"
}
```

| Declaration member | Meaning |
|---|---|
| `kind` | `respond`: resolves an interrupt. `event`: a deterministic UI event handled by the server **without the model**. `message`: sends a user message the server wrote |
| `outcome` | `respond` only: `accept`, `decline` or `cancel` (the MCP elicitation vocabulary) |
| `submit` | pointer of the data object the client sends as `values` |
| `digest` | opaque `sha256:`; the client echoes it as `expect.digest`; a mismatch means the user is looking at stale content (`409 stale-surface`) |
| `confirm` | optional friction dialog (`title`, `body`, `confirmLabel`, optional `typed` phrase). **UX only, not enforced by the server** |
| `label`, `style`, `expiresAt` | presentation and lifetime |

Server-held record for the `a_more` action above (design sketch, stored in `dai_chat_surface.handlers`, never serialized to a client):

```jsonc
{ "a_more": { "kind": "event", "handler": "tool.page",
              "args": { "binding": "find_orders", "arguments": { "customerId": 4711, "status": "OPEN" }, "cursor": "<signed cursor>" },
              "repeatable": true } }
```

How the three kinds behave:

| Kind | Server handler | Repeatable | Example |
|---|---|---|---|
| `respond` | resolves the interrupt (compare-and-set `open → terminal`), then resumes the run or routes to the proposal decision | no, single use | answer a question, confirm a proposal |
| `event` | a registered `UiEventHandler` by name. It **re-enters the normal tool pipeline as the caller** (authorization, limits, masking, audit), so a UI event can never do more than a tool call could | yes | `tool.page` (next page), `table.sort`, `surface.refresh`, `proposal.apply` |
| `message` | creates a user message from a fixed `text`, or from a server-held `template` with `{name}` placeholders filled from validated `values`, then runs the `message` path | yes | quick-reply chips, a search form that feeds the AI |

An `event` handler answers with `ui.patch` or `ui.surface` events and ends with `interaction.end`; no model is called and no budget is spent.

### 5.8 UI commands (phase P2)

The server can instruct the UI with `ui.command`: `{commandId, name, args, requiresGesture}`. This is how an agent says "open order 101" or "focus the table" without any code crossing the wire.

<!-- validate: event name=ui-command -->
```json
{
  "type": "ui.command",
  "commandId": "0198f1c4-0001-7a00-8b00-00000000c0de",
  "name": "open_entity",
  "args": {
    "entity": "entity:com.acme.order.Order",
    "id": "101"
  },
  "requiresGesture": true
}
```

Rules:
- command names come from a **host-registered registry** (`UiCommandRegistry`: name, JSON Schema for `args`, risk, `requiresGesture`). Built-ins: `focus_surface`, `scroll_to_surface`, `open_entity` (an entity ref, resolved by the host's client-side resolver). Anything else is refused. Default: no commands allowed (`chat.ui.commands.allowed=[]`);
- a command carries no code and no URL; `args` are validated against the registered schema;
- the client lists what it implements in `context.client.commands`; the server only sends those;
- a command that navigates away or opens something sets `requiresGesture: true`; the client shows a button and executes it on click, never automatically;
- the client may ignore any command; a command is a request, not an order.

A command that must *return* something to the AI (for example "what rows are selected?") is a `client_command` interrupt answered with `respond`; its answer is untrusted user-side data like any other input (§7.7, phase P3).


## 6. Interactions (client → server)

### 6.1 Endpoint

```
POST /dynamic-ai/api/agents/{slug}/interactions
Content-Type: application/json
Accept: text/event-stream        (events as they happen)   or   application/json   (one batch)
Authorization: Bearer …  |  session cookie + CSRF header
Accept-Language: de-DE           (server-authored labels)
```

- POST only, never GET: input must not appear in URLs or logs (LLD-13 §2). The browser `EventSource` cannot POST or send headers; clients use `fetch()` with a stream reader.
- The body is read as a size-capped string (`chat.interactions.max-request-bytes`, default 64 KiB, else `413 request-too-large`) and parsed by the library's own strict `JsonMapper`, not by the host's, so a host-wide naming strategy or lenient setting cannot change this contract.
- The same envelope is used by the playground (`channel=PLAYGROUND`, draft revisions) and by any other first-party client. MCP clients do not use it (§21).
- The legacy `POST …/chat` and `…/chat/stream` remain as thin aliases: they build a `message` interaction (`clientRequestId` becomes the `interactionId` when it is a UUID) and call the same code.

### 6.2 Envelope

Common members: `protocol` (`dai-stream/2`), `interactionId` (client-generated UUIDv7, the idempotency key), `conversationId` (required except for the first `message`), `type`, and an optional `context`.

| `type` | Members | Meaning |
|---|---|---|
| `message` | `text` (1..32000), `replyTo?` (interrupt id) | free text to the AI: a new question, an instruction, or the typed answer to a single-text question |
| `action` | `surface{id, revision}`, `actionId`, `values?`, `expect{digest}?` | the user activated something a surface offered |
| `respond` | `interruptId`, `outcome` (`accept`, `decline`, `cancel`), `values?`, `expect?` | answers an interrupt by id, for clients and flows without a surface |
| `cancel` | `turnId` | stops a running turn the caller owns |

<!-- validate: interaction name=request-message -->
```json
{
  "protocol": "dai-stream/2",
  "interactionId": "0198f1c0-3a9d-7d02-a3b4-1e2f3a4b5c6d",
  "type": "message",
  "text": "Show me ACME's open orders",
  "context": {
    "locale": "en-GB",
    "timeZone": "Europe/Berlin",
    "client": {
      "name": "saimcp-chat",
      "version": "1.0.0",
      "catalog": "dai-ui/1",
      "features": [
        "patch",
        "markdown"
      ]
    },
    "page": {
      "route": "/customers/4711",
      "title": "ACME GmbH"
    },
    "selection": [
      {
        "entity": "entity:com.acme.customer.Customer",
        "id": 4711
      }
    ]
  }
}
```

<!-- validate: interaction name=request-respond-answer -->
```json
{
  "protocol": "dai-stream/2",
  "interactionId": "0198f1c1-09f0-7b21-8d32-3e4f5a6b7c8d",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "type": "action",
  "surface": {
    "id": "0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50",
    "revision": 1
  },
  "actionId": "a_submit",
  "values": {
    "customer": "4711"
  }
}
```

<!-- validate: interaction name=request-respond-by-interrupt -->
```json
{
  "protocol": "dai-stream/2",
  "interactionId": "0198f1c1-09f0-7b21-8d32-3e4f5a6b7c8d",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "type": "respond",
  "interruptId": "0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10",
  "outcome": "accept",
  "values": {
    "customer": "4711"
  }
}
```

<!-- validate: interaction name=request-action-confirm -->
```json
{
  "protocol": "dai-stream/2",
  "interactionId": "0198f1c3-7a10-7e20-9f31-a0b1c2d3e4f5",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "type": "action",
  "surface": {
    "id": "0198f1c2-5d22-7c81-a192-b3c4d5e6f708",
    "revision": 1
  },
  "actionId": "a_confirm",
  "expect": {
    "digest": "sha256:1370b55b38277acb9bfb8d757a41d1dd6b11025dc764020058366ea41ca1bd9c"
  }
}
```

<!-- validate: interaction name=request-cancel -->
```json
{
  "protocol": "dai-stream/2",
  "interactionId": "0198f1c2-5900-7b31-8c42-5d6e7f8091a2",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "type": "cancel",
  "turnId": "0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70"
}
```

`context` is **untrusted, bounded (≤ 8 KiB) and never an authority**:

| Member | Use |
|---|---|
| `locale`, `timeZone` | server-authored labels, date formats, answer rendering |
| `client{name, version, catalog, features, commands}` | capability negotiation: the server never sends node types or commands the client did not declare; absent means the full v1 catalog |
| `page{route, title}` | where the user is; offered to the model as context |
| `selection[]` | entity refs the user selected in the host UI; refs not in the effective catalog are dropped. The model sees them as a delimited *user-context* block; tools still run as the caller, so a forged ref cannot reach data the caller could not read |

### 6.3 Which type do I send?

| The user… | Send |
|---|---|
| typed in the composer | `message` |
| pressed anything a surface drew (button, chip, form submit, sort, "show more") | `action` with the surface, its revision and the action id |
| answers an interrupt that has no surface, or a headless client answers by id | `respond` |
| pressed stop | `cancel` |

Which of the user's three kinds of input this is: *confirmations and actions* are `action` (and `respond`); *input to an AI query* is `message` (and a `respond`/`action` that answers a question); *UI instructions* are `action`s of kind `event`, handled without the model (§5.7).

### 6.4 Dispatch

| Interaction | Handler | Runs the model | Response ends with |
|---|---|---|---|
| `message` | agent run (existing guardrails, budget, memory); open `question` and `confirmation` interrupts are superseded unless this message answers one (`replyTo`) | yes | `turn.end` |
| `action`, kind `respond` | resolve the interrupt, then per interrupt kind: resume the run (question, confirmation) or decide the proposal (proposal review) | question: yes; proposal: no | `turn.end` or `interaction.end` |
| `action`, kind `event` | server-held `UiEventHandler` | no | `interaction.end` |
| `action`, kind `message` | builds the message, then as `message` | yes | `turn.end` |
| `respond` | as `action` kind `respond` | as above | as above |
| `cancel` | cancels the running turn (idempotent) | no | `interaction.end` |

### 6.5 Idempotency

`interactionId` is stored with the owner in `dai_chat_interaction` (unique `(owner_id, interaction_id)`) together with a hash of the canonical request body. Window: `chat.interactions.dedupe-window` (default 24 h).

- same id, same body, run still active: attach to its stream (phase P1: `409` with the turn to resume, as today; P2: attach);
- same id, same body, finished: return the recorded outcome, never execute again. A retried confirm after a network error cannot apply a change twice;
- same id, different body: `409 idempotency-conflict`.

This replaces the node-local `ClientRequestRegistry` (OQ-42).

### 6.6 Validation order

1. transport: size, content type, strict JSON → `413`, `415`, `400`;
2. envelope structure (`dai-stream-2` `interaction`) → `400 invalid-argument` with `errors[]`;
3. authentication `401`; `agent:invoke` on the agent `403`; kill switch `503` (§6.7); rate limit `429`; budget `429` (only for interactions that run the model);
4. references: conversation, surface, interrupt belong to the caller (`404` otherwise);
5. state: interrupt open (`409 interrupt-resolved`, `410 interrupt-expired`), surface active, `expect.digest` equals the current digest (`409 stale-surface`, body carries `current`), no other turn running in the conversation for runs (`409 turn-active`);
6. `values` against the **server-held** schema of the action or interrupt (additional properties refused, every value bounded) → `400 invalid-argument` with JSON Pointer fields such as `/values/customer`;
7. execute.

### 6.7 Concurrency and exemptions

- One running turn per conversation. A second run-starting interaction answers `409 turn-active`. Cluster-wide enforcement needs the run lease of phase P2; in P1 it is enforced per node and by the client disabling input while a turn runs.
- Answering, declining, cancelling and deciding a proposal must stay possible when the user is rate limited: **`respond`, `cancel` and `action` on an open interrupt skip the chat rate limiter** after authorization (their own protections are single use, expiry and the per-principal cap on open interrupts).
- The agent kill switch blocks work that calls the model (`message`, resume). It does **not** block `decline` and `cancel`, so a user can always say no. Applying a confirmed proposal is governed by the write capability (LLD-11), not by the agent switch.
- Open interrupts per conversation are capped (`chat.interrupts.max-open`, default 5); a new blocking interrupt supersedes the previous blocking one.

## 7. Interrupts: questions, confirmations, proposal reviews

An interrupt is **something the run needs from the user (or the UI) before it can continue**. It is the one concept behind "the AI asked a question".

### 7.1 Kinds

| Kind | Raised by | Blocking | Answer | Authority |
|---|---|---|---|---|
| `question` | the model, via `ask_user` | yes | `accept` with `values` validated against a flat response schema; `decline`; `cancel` | none: the answer is data for the model |
| `confirmation` | the model, via `ask_user` with `confirm` | yes | `accept` (yes), `decline` (no) | none: advisory UX. **A model-raised confirmation never authorizes anything** |
| `proposal_review` | the server, when a mutating tool created a change proposal | no | `accept` (confirm, with digest), `decline` | **the only kind that can lead to a write**, through the proposal API rules |
| `client_command` | the server (phase P3) | yes | `accept` with the command's result | none: untrusted client data |
| `step_up` | reserved (OQ-18) | yes | re-authentication, then `accept` | n/a |

*Blocking* means the conversation is waiting; a new `message` supersedes it. Proposal reviews do not block: the user may keep chatting and decide later, until the proposal expires.

### 7.2 Lifecycle

```
          raise                       accept ─► answered
  (none) ───────► open ──┬── respond decline ─► declined
                         ├── respond cancel  ─► cancelled
                         ├── expiresAt passes ─► expired        terminal states never change
                         └── new message / newer blocking one ─► superseded
```

Resolution is a compare-and-set on the stored row (`UPDATE … WHERE status = 'OPEN'`), so two tabs or a double click resolve it once; the loser gets `409 interrupt-resolved`. Expiry is applied lazily on access and swept by the maintenance runner. Defaults: `chat.interrupts.ttl` 30 min for questions and confirmations, `write.proposal-ttl` (15 min) for proposal reviews.

### 7.3 `ask_user`

`ask_user` is a **control tool**: it is offered to the model like a tool but is a protocol signal, not a host operation, so it needs no tool grant, touches no host data and is not executed by the tool loop (§7.4). It is available when the agent enables it (`interaction.askUser`) and the agent output mode is text.

Arguments (`dai-tools-1` `askUserInput`): a `question` (≤ 500), and either `fields` (≤ 8) or `confirm`; neither means one free-text answer.

<!-- validate: askUser name=ask-user-choice -->
```json
{
  "question": "There are two customers called ACME. Which one do you mean?",
  "fields": [
    {
      "name": "customer",
      "label": "Customer",
      "type": "choice",
      "required": true,
      "options": [
        {
          "value": "4711",
          "label": "ACME GmbH (Berlin)"
        },
        {
          "value": "4712",
          "label": "ACME Ltd (Leeds)"
        }
      ]
    }
  ]
}
```

<!-- validate: askUser name=ask-user-confirm -->
```json
{
  "question": "Export all 1,240 matching orders as CSV?",
  "confirm": {
    "accept": "Yes, export",
    "decline": "No"
  }
}
```

<!-- validate: askUser name=ask-user-form -->
```json
{
  "question": "I need a few details to find the right shipment.",
  "fields": [
    {
      "name": "carrier",
      "label": "Carrier",
      "type": "choice",
      "options": [
        {
          "value": "dhl",
          "label": "DHL"
        },
        {
          "value": "ups",
          "label": "UPS"
        }
      ]
    },
    {
      "name": "sentAfter",
      "label": "Sent after",
      "type": "date"
    },
    {
      "name": "minWeightKg",
      "label": "Minimum weight (kg)",
      "type": "number",
      "minimum": 0,
      "maximum": 1000
    },
    {
      "name": "fragile",
      "label": "Fragile only",
      "type": "boolean"
    }
  ]
}
```

The server turns the arguments into three things, none of which the model controls:

1. **A response schema**, kept with the interrupt and used to validate the answer. It is the flat-object subset that MCP elicitation allows (string with length, pattern and `email`/`uri`/`date`/`date-time`; number and integer with bounds; boolean; single choice as `oneOf` of `const`/`title`; multi choice as an array of such choices) plus `additionalProperties: false`.

<!-- validate: responseSchema name=response-schema-choice -->
```json
{
  "type": "object",
  "title": "Which customer do you mean?",
  "properties": {
    "customer": {
      "type": "string",
      "title": "Customer",
      "oneOf": [
        {
          "const": "4711",
          "title": "ACME GmbH (Berlin)"
        },
        {
          "const": "4712",
          "title": "ACME Ltd (Leeds)"
        }
      ]
    }
  },
  "required": [
    "customer"
  ],
  "additionalProperties": false
}
```

2. **A form tree** built by `FormTreeBuilder` (trusted code):

| Field `type` | Node |
|---|---|
| `text` | `text_input` (`multiline` for long answers) |
| `number`, `integer` | `number_input` |
| `boolean` | `checkbox` |
| `choice` | `select` (radio when ≤ 4 options) |
| `multi_choice` | `multi_select` |
| `date`, `datetime` | `date_input` |
| `confirm` | two `button`s, no inputs |

3. **A surface** (kind `question` or `confirmation`, `origin: model`) with a `respond` action per outcome, exactly as in the §5.1 example.

Guards on the model's arguments: lengths and counts from the schema; plain text only; §5.5 S7 character rules; option values unique; **no sensitive requests**: labels and questions matching `chat.ask-user.blocked-terms` (passwords, tokens, API keys, card numbers by default) are refused, mirroring the MCP rule that form mode must not collect secrets. Invalid arguments degrade to a plain-text question (principle 9): the turn ends normally with the sanitized `question` as text and the user answers with a `message`.

### 7.4 Suspension mechanics (Spring AI 2.0.1)

Contract, whatever the mechanism: after the model emits `ask_user`, **no other tool of that model response runs**; the runtime persists the interrupt and surface in one transaction, emits `ui.surface`, `interrupt.raised` and `turn.end` with `finishReason: "interrupt"`, and stores the question as the assistant message (memory and transcript).

Verified facts that shape the mechanism:

- `ToolMetadata.returnDirect()` exists, but `DefaultToolCallingManager` combines the flags of the tools in a round with a **logical AND** (bytecode-verified). A round that mixes `ask_user` with any other tool does not return directly, so `returnDirect` alone cannot suspend the turn.
- `ToolCallingAdvisor.builder().toolExecutionEligibilityChecker(...)` and `ToolExecutionEligibilityChecker` (a `Function<ChatResponse, Boolean>`) exist; `AdvisorParams.toolCallingAdvisorAutoRegister(boolean)` exists.
- `ChatResponse.hasToolCalls()` and `AssistantMessage.ToolCall` (`id`, `type`, `name`, `arguments`) exist.

**Chosen mechanism:** register the `ToolCallingAdvisor` with an eligibility checker that returns `false` for any response containing `ask_user` (`AskUserGate`, §13.3). The loop then returns that response untouched and the invoker converts the tool call into the interrupt. This needs no thread-safe side channel for `ask_user` and drops the other calls of the round cleanly. The alternative (a custom `ToolCallingManager`, ADR-0017, that forces `returnDirect` for a round containing `ask_user`) keeps `ask_user` in the tool telemetry but depends on a component that does not exist yet.

**Verify (spike S-1, test T-2):** that exactly one `ToolCallingAdvisor` is in the chain; that a streamed response with the tool call arriving in fragments is aggregated before the check; and that chat memory ends as `USER(original)`, `ASSISTANT(question)`, never an empty assistant message. The turn is recorded with `finish_reason = INTERRUPT` (needs the `ck_agent_turn_finish` extension, §12).

### 7.5 Answer framing and resume

Memory has no tool messages (V9), so the answer reaches the model as a **server-generated user message**, in English, from the server-held schema and the validated values:

```text
The user answered your question "Which customer do you mean?":
- Customer: ACME GmbH (Berlin) [4711]
```

| Outcome | Effect |
|---|---|
| `accept` | frame as above (free text: `The user answered your question "…": <text>`); a new turn starts with `turn.start.resumes = {interruptId}` |
| `decline` | `The user declined to answer your question "…". Continue without it or explain what you cannot do.`; a turn starts |
| `cancel` | no turn; the interrupt is `cancelled` and the user may type anything |

The framed message passes the same input guardrails as any user message. The user's transcript shows a readable rendering ("Customer: ACME GmbH (Berlin)") in their locale, not the framed English. Values are bounded (≤ 2,000 characters each) and are user data like any other input.

### 7.6 Proposal review

When a mutating tool in PROPOSE mode returns `status: proposed`, the server builds the review surface **from the stored proposal** (one owner of the fact): `proposal.created` (now with `surfaceId` and `interruptId`), `ui.surface`, `interrupt.raised` (kind `proposal_review`, non-blocking, `digest` = the proposal `contentHash`). The model's turn continues and typically says that a change awaits review.

<!-- validate: ui name=surface-review -->
```json
{
  "surfaceId": "0198f1c2-5d22-7c81-a192-b3c4d5e6f708",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "turnId": "0198f1c2-5a00-7e10-8f20-9a0b1c2d3e4f",
  "interruptId": "0198f1c2-5d21-7d92-b2a3-c4d5e6f70819",
  "revision": 1,
  "kind": "proposal_review",
  "origin": "server",
  "status": "active",
  "catalog": "dai-ui/1",
  "title": "Proposed change: mark order 101 as shipped",
  "poll": {
    "after": "PT10S"
  },
  "tree": {
    "id": "root",
    "type": "card",
    "props": {
      "title": "Review before anything is written",
      "tone": "warning"
    },
    "children": [
      {
        "id": "review",
        "type": "change_review",
        "props": {
          "proposalId": "0198f1c2-5d10-7a60-9b71-8c9d0e1f2a3b",
          "presentation": "diff",
          "changeKind": "UPDATE",
          "summary": "Mark order 101 as shipped and record tracking number 1Z999",
          "state": "PROPOSED",
          "records": [
            {
              "entity": "Order",
              "entityId": "101",
              "fields": [
                {
                  "attr": "status",
                  "label": "Status",
                  "before": "PAID",
                  "after": "SHIPPED",
                  "changed": true
                },
                {
                  "attr": "trackingNo",
                  "label": "Tracking no.",
                  "before": null,
                  "after": "1Z999",
                  "changed": true
                },
                {
                  "attr": "total",
                  "label": "Total",
                  "before": "120.50",
                  "after": "120.50",
                  "changed": false
                }
              ]
            }
          ],
          "validation": {
            "errors": [],
            "warnings": [
              "Customer is on credit hold"
            ]
          },
          "approval": {
            "kind": "SELF_CONFIRM",
            "required": 0,
            "received": 0
          },
          "expiresAt": "2026-10-01T11:00:00Z",
          "contentHash": "sha256:1370b55b38277acb9bfb8d757a41d1dd6b11025dc764020058366ea41ca1bd9c"
        }
      },
      {
        "id": "buttons",
        "type": "stack",
        "props": {
          "direction": "horizontal",
          "gap": "sm"
        },
        "children": [
          {
            "id": "b_confirm",
            "type": "button",
            "props": {
              "label": "Apply change",
              "action": "a_confirm",
              "variant": "primary"
            }
          },
          {
            "id": "b_decline",
            "type": "button",
            "props": {
              "label": "Decline",
              "action": "a_decline",
              "variant": "secondary"
            }
          }
        ]
      }
    ]
  },
  "data": {},
  "actions": {
    "a_confirm": {
      "kind": "respond",
      "outcome": "accept",
      "label": "Apply change",
      "style": "primary",
      "digest": "sha256:1370b55b38277acb9bfb8d757a41d1dd6b11025dc764020058366ea41ca1bd9c",
      "confirm": {
        "title": "Apply this change?",
        "body": "It is written to order 101 as you, through the application.",
        "confirmLabel": "Apply"
      }
    },
    "a_decline": {
      "kind": "respond",
      "outcome": "decline",
      "label": "Decline",
      "style": "secondary"
    }
  },
  "expiresAt": "2026-10-01T11:00:00Z"
}
```

`accept` calls the **same decision service** as `POST /proposals/{id}:confirm` (the controller is refactored to share it, one owner of the rule): owner check, `data:write-confirm`, content hash equal to `expect.digest`, expiry, state, then apply when no approver is needed. The surface is a *projection* of the proposal: `dai_chat_surface.source_version` holds the proposal `row_version` it was built from, and `GET /surfaces/{id}` re-projects (revision + 1) when the proposal changed, for example after an approver acted.

| Proposal state | Surface shows | Actions |
|---|---|---|
| `PROPOSED`, `EDITED` | diff, validation, expiry | `a_confirm`, `a_decline`; `a_edit` (kind `event`, phase P2, when OQ-36 `PATCH` exists) |
| `AWAITING_APPROVAL` | "waiting for approval", approvals received | none; `poll` |
| `CONFIRMED` (approved, not yet applied) | ready to apply | `a_apply` (kind `event`, handler `proposal.apply`) |
| `APPLYING` | in progress | none; `poll` |
| `APPLIED` | result and host revision | none; surface `completed` |
| `REJECTED`, `EXPIRED`, `FAILED`, `CONFLICT` | outcome and sanitized reason | none; surface `completed`; `CONFLICT` offers "ask the assistant to propose again" as a `message` action |

The interrupt resolves (`answered`) when the owner decides; later approval and apply are proposal states shown by the surface. A decision does **not** start a model turn by default (cost, surprise). The outcome is recorded in memory as a server note (`SYSTEM`) so the next turn knows (`chat.interactions.proposal-decision-turn=false`, OQ-58).

### 7.7 Client commands and step-up (reserved)

`client_command` (the AI needs a value only the UI has) and `step_up` (OQ-18) use the same `interrupt.raised` and `respond` machinery. Their answers are untrusted, validated against a registered schema, and carry no authority. Specified when P3 is scheduled.

## 8. Events (server → client)

### 8.1 Vocabulary (`dai-stream/2`)

Every SSE frame has `id: {turnId}:{seq}` (or `{interactionId}:{seq}` for interactions without a turn), `event: <type>` and `data: <JSON with "type">`. In JSON batch mode each event carries `seq` inline.

| Event | Members | Phase |
|---|---|---|
| `turn.start` | `turnId`, `conversationId`, `agent`, `revision`, `protocol`, `interactionId?`, `resumes?{interruptId}` | P1 |
| `text.delta` | `text` | exists |
| `text.snapshot` | `text`, `upToSeq`: replaces the text streamed so far (resume after a gap) | P2 |
| `tool.call` / `tool.result` | as in LLD-13; `tool.result` adds `handle` (`r1`) when the result is available to `render_ui` | P1 (now emitted) |
| `ui.surface` | `surface`: creates or replaces a surface | P1 |
| `ui.patch` | `surfaceId`, `baseRevision`, `revision`, `tree?`, `data?`, `actions?` | P2 |
| `ui.status` | `surfaceId`, `revision`, `status` | P1 |
| `ui.command` | `commandId`, `name`, `args?`, `requiresGesture` | P2 |
| `interrupt.raised` | `interruptId`, `kind`, `blocking`, `surfaceId?`, `digest?`, `expiresAt` | P1 |
| `interrupt.resolved` | `interruptId`, `status`, `by` (`user`, `system`) | P1 |
| `proposal.created` / `.updated` / `.applied` | LLD-13/LLD-11 members; `created` and `updated` add `surfaceId`, `created` adds `interruptId` | P1 |
| `usage` | unchanged | exists |
| `turn.end` | `finishReason` (adds `interrupt`), `messageId?`, `interrupts?[]` | exists, extended |
| `interaction.end` | `interactionId`, `outcome` (`ok`, `rejected`, `noop`) | P1 |
| `error` | RFC 9457 subset as in LLD-13; `turnId` or `interactionId` | exists |
| `state.snapshot` | `conversationId`, `cursor`, `messages[]`, `surfaces[]`, `interrupts[]` | P1 |

Heartbeats stay SSE comments (`: keep-alive`), never events.

### 8.2 Ordering and termination

- `turn.start` is the first event of a turn. For a resumed turn, `interrupt.resolved` and `ui.status` for the answered surface follow it.
- A stream **always ends** with exactly one of `turn.end`, `interaction.end` or `error`.
- **Persist, then emit** for structural events (`ui.*`, `interrupt.*`, `proposal.*`): the row is committed before the event is sent, so a dropped connection never loses a pending question; the client recovers through `state`. Token deltas and `tool.*` are best effort.
- `turn.end` with `finishReason: "interrupt"` means the conversation is waiting; `interrupts[]` lists what for.

### 8.3 JSON batch

With `Accept: application/json` the response is `{"protocol","conversationId?","cursor?","events":[…]}`: the **same events, in order, with `seq`**. Use it for deterministic actions, tests and integrations that cannot stream. A client handles it with the reducer it already has.

<!-- validate: batch name=batch-response -->
```json
{
  "protocol": "dai-stream/2",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "cursor": "0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70:6",
  "events": [
    {
      "type": "turn.start",
      "seq": 0,
      "turnId": "0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70",
      "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
      "agent": "orders-assistant",
      "revision": 7,
      "protocol": "dai-stream/2"
    },
    {
      "type": "ui.surface",
      "seq": 1,
      "surface": {
        "surfaceId": "0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50",
        "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
        "turnId": "0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70",
        "interruptId": "0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10",
        "revision": 1,
        "kind": "question",
        "origin": "model",
        "status": "active",
        "catalog": "dai-ui/1",
        "title": "Which customer?",
        "tree": {
          "id": "root",
          "type": "form",
          "props": {
            "submit": "a_submit",
            "cancel": "a_dismiss"
          },
          "children": [
            {
              "id": "q",
              "type": "text",
              "props": {
                "value": "There are two customers called ACME. Which one do you mean?",
                "variant": "title"
              }
            },
            {
              "id": "f_customer",
              "type": "select",
              "props": {
                "name": "customer",
                "label": "Customer",
                "bind": "/form/customer",
                "required": true,
                "options": [
                  {
                    "value": "4711",
                    "label": "ACME GmbH (Berlin)"
                  },
                  {
                    "value": "4712",
                    "label": "ACME Ltd (Leeds)"
                  }
                ]
              }
            },
            {
              "id": "buttons",
              "type": "stack",
              "props": {
                "direction": "horizontal",
                "gap": "sm"
              },
              "children": [
                {
                  "id": "b_submit",
                  "type": "button",
                  "props": {
                    "label": "Send",
                    "action": "a_submit",
                    "variant": "primary"
                  }
                },
                {
                  "id": "b_decline",
                  "type": "button",
                  "props": {
                    "label": "Skip",
                    "action": "a_decline",
                    "variant": "link"
                  }
                }
              ]
            }
          ]
        },
        "data": {
          "form": {}
        },
        "actions": {
          "a_submit": {
            "kind": "respond",
            "outcome": "accept",
            "submit": "/form"
          },
          "a_decline": {
            "kind": "respond",
            "outcome": "decline",
            "label": "Skip"
          },
          "a_dismiss": {
            "kind": "respond",
            "outcome": "cancel"
          }
        },
        "expiresAt": "2026-10-01T10:45:00Z"
      }
    },
    {
      "type": "interrupt.raised",
      "seq": 2,
      "interruptId": "0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10",
      "kind": "question",
      "blocking": true,
      "surfaceId": "0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50",
      "expiresAt": "2026-10-01T10:45:00Z"
    },
    {
      "type": "turn.end",
      "seq": 3,
      "finishReason": "interrupt",
      "messageId": "0198f1c1-0b77-7c88-9d99-aabbccddeeff",
      "interrupts": [
        "0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10"
      ]
    }
  ]
}
```

### 8.4 State and reload

`GET /dynamic-ai/api/conversations/{id}/state` (owner only) returns one `state.snapshot` event: the messages (text plus `surfaces[]` references with their anchors), every surface that is active or belongs to a stored message, and the interrupts. Without transcript recording (`conversations.enabled=false`) `messages` is empty and the response still carries the **operational** state: open interrupts and their surfaces, active proposal reviews. `cursor` is the `Last-Event-ID` to resume from.

<!-- validate: event name=state-snapshot -->
```json
{
  "type": "state.snapshot",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "cursor": "0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f:31",
  "messages": [
    {
      "seq": 0,
      "role": "user",
      "text": "Show me ACME's open orders"
    },
    {
      "seq": 1,
      "role": "assistant",
      "turnId": "0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70",
      "text": "There are two customers called ACME.",
      "surfaces": [
        {
          "surfaceId": "0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50",
          "anchor": 36
        }
      ]
    }
  ],
  "surfaces": [
    {
      "surfaceId": "0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50",
      "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
      "turnId": "0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70",
      "interruptId": "0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10",
      "revision": 2,
      "kind": "question",
      "origin": "model",
      "status": "completed",
      "catalog": "dai-ui/1",
      "title": "Which customer?",
      "tree": {
        "id": "root",
        "type": "form",
        "props": {
          "submit": "a_submit",
          "cancel": "a_dismiss"
        },
        "children": [
          {
            "id": "q",
            "type": "text",
            "props": {
              "value": "There are two customers called ACME. Which one do you mean?",
              "variant": "title"
            }
          },
          {
            "id": "f_customer",
            "type": "select",
            "props": {
              "name": "customer",
              "label": "Customer",
              "bind": "/form/customer",
              "required": true,
              "options": [
                {
                  "value": "4711",
                  "label": "ACME GmbH (Berlin)"
                },
                {
                  "value": "4712",
                  "label": "ACME Ltd (Leeds)"
                }
              ]
            }
          },
          {
            "id": "buttons",
            "type": "stack",
            "props": {
              "direction": "horizontal",
              "gap": "sm"
            },
            "children": [
              {
                "id": "b_submit",
                "type": "button",
                "props": {
                  "label": "Send",
                  "action": "a_submit",
                  "variant": "primary"
                }
              },
              {
                "id": "b_decline",
                "type": "button",
                "props": {
                  "label": "Skip",
                  "action": "a_decline",
                  "variant": "link"
                }
              }
            ]
          }
        ]
      },
      "data": {
        "form": {}
      },
      "actions": {
        "a_submit": {
          "kind": "respond",
          "outcome": "accept",
          "submit": "/form"
        },
        "a_decline": {
          "kind": "respond",
          "outcome": "decline",
          "label": "Skip"
        },
        "a_dismiss": {
          "kind": "respond",
          "outcome": "cancel"
        }
      },
      "expiresAt": "2026-10-01T10:45:00Z"
    }
  ],
  "interrupts": [
    {
      "interruptId": "0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10",
      "kind": "question",
      "status": "answered",
      "blocking": true,
      "surfaceId": "0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50",
      "expiresAt": "2026-10-01T10:45:00Z"
    }
  ]
}
```

The reducer treats `state.snapshot` as authoritative and replaces its state.

### 8.5 Reconnect and resume

| Situation | Client does | Server |
|---|---|---|
| connection drops during a turn | reconnect `GET …/turns/{turnId}/events` with `Last-Event-ID` | P1: replays from the node-local ring if the node is the same, else `404`; P2: replays from PostgreSQL, text as `text.snapshot` plus tail |
| `404` on replay, or a patch does not apply | `GET …/state` and replace | snapshot |
| page reload | `GET …/state` | snapshot, including open interrupts |
| tab regains focus with an `active` surface that has `poll` | re-fetch with `If-None-Match` | `304` or the new surface |

Phase P2 persists structural events in `dai_chat_event` and checkpoints the in-progress text, so any node can serve a resume (OQ-42).

### 8.6 Migration from `dai-stream/1`

| Change | Detail |
|---|---|
| protocol | `turn.start.protocol` is `dai-stream/2`; clients that see another major version stop and show an upgrade message |
| `ui.component` | removed; the payload (a string) is replaced by `ui.surface` (an object). No client consumed it |
| `text.delta.seq` | removed; the event position is the SSE id |
| `proposal.created` | keeps `reviewUrl`; adds `surfaceId` and `interruptId` |
| `turn.end` | adds `finishReason: "interrupt"` and `interrupts` |
| unknown events | new rule for all clients: ignore unknown event types and fields |
| `clientRequestId` | `interactionId` (UUID) on the new endpoint; the legacy endpoints keep their rules |


## 9. Authoring surfaces

Three authors, three trust levels. Nothing a model writes is ever interpreted as structure it was not allowed to create.

### 9.1 Server-authored (trusted)

Questions and confirmations (`FormTreeBuilder` from `ask_user` arguments), proposal reviews (`ProposalSurfaceFactory` from the stored proposal), status and error surfaces, and optional **tool-result presenters** (a tool binding may declare `present: "table"`: the runtime renders the result as a `table` from the catalog's attribute labels and formats, with no model involved; default off). Labels and fixed texts come from `MessageSource` keys `dynamic.ai.agent.ui.*` for the caller's locale, as LLD-06 §6.1 does for problem titles.

### 9.2 Model-authored: `render_ui` (phase P2)

For richer answers an agent that enables `interaction.renderUi` can show a display surface. `render_ui` is a normal tool executed in the loop; its arguments are `dai-tools-1` `renderUiInput`: a `title`, a **`model_safe` tree**, a `data` object, and up to three `suggestions` (quick replies).

<!-- validate: renderUi name=render-ui-table -->
```json
{
  "title": "Open orders for ACME GmbH",
  "tree": {
    "id": "root",
    "type": "card",
    "props": {
      "title": "Open orders for ACME GmbH"
    },
    "children": [
      {
        "id": "t1",
        "type": "table",
        "props": {
          "caption": "Open orders for ACME GmbH",
          "columns": [
            {
              "key": "id",
              "label": "Order",
              "align": "end"
            },
            {
              "key": "status",
              "label": "Status"
            },
            {
              "key": "total",
              "label": "Total",
              "format": "currency",
              "align": "end"
            }
          ],
          "rows": {
            "$data": "/rows"
          },
          "rowKey": "id"
        }
      }
    ]
  },
  "data": {
    "rows": {
      "$result": "r1",
      "path": "/data"
    }
  },
  "suggestions": [
    {
      "label": "Only unpaid",
      "text": "Show only the unpaid ones"
    }
  ]
}
```

- **Bind, don't copy.** `data` values are small literals or references `{"$result": "r1", "path": "/data"}` to a tool result *of this turn*. Every tool result envelope carries a `handle` (`r1`, `r2`, …; an additive field of LLD-07 §3a, not a provider call id). The server resolves the reference from its per-turn result cache (bounded: ≤ 8 results, ≤ 1 MiB each), **re-masks by the caller's clearance**, and copies the rows into the surface data model. The model never re-emits rows, so it cannot fabricate "database" data (threat I6) and the turn costs fewer tokens. This replaces the hash-match provenance check of LLD-11 §7.1 with something stricter and cheaper.
- The tree is validated with rule S8 plus all of §5.5. `suggestions` become a server-created `suggestion_chips` node whose items are `message` actions with the given text; clicking one is the same as the user typing that text.
- Result: `ui.surface` (`kind: display`, `origin: model`, `anchor` = text length when the tool ran) and a one-line tool result to the model ("Displayed a table of 2 rows to the user").
- Pagination, sorting and refresh of such a table are `event` actions the server attaches (`tool.page` re-runs the original tool binding with a signed cursor, as the caller, through `SecuredToolCallback`).

### 9.3 Host-authored (phase P3)

A host can register extra node types and commands (`UiNodeTypeRegistry`, `UiCommandRegistry`) with a JSON Schema each, declared at startup, default deny, `fallback` required. Hosts may also render surfaces with their own components: the schemas are the contract (LLD-11 §7.3 stays true).

## 10. HTTP API reference

### 10.1 Endpoints

| Endpoint | Success | Notes |
|---|---|---|
| `POST /dynamic-ai/api/agents/{slug}/interactions` | `200 text/event-stream` or `200 application/json` batch | §6; `Cache-Control: no-cache, no-transform`, `X-Accel-Buffering: no` for SSE |
| `GET /dynamic-ai/api/agents/{slug}/turns/{turnId}/events` | `200 text/event-stream` | `Last-Event-ID: {turnId}:{seq}`; owner only |
| `GET /dynamic-ai/api/conversations/{id}/state` | `200` one `state.snapshot` | owner only; `ETag` = cursor |
| `GET /dynamic-ai/api/surfaces/{id}` | `200` surface, or `304` | owner only; `ETag: "<revision>"`; re-projects proposal reviews |

### 10.2 Problem codes

Bodies are RFC 9457 `application/problem+json` as today (`type` `https://dynamic-ai/problems/<code>`, `title`, `status`, `detail?`, `instance`, `errors[]?`, `retryAfter?`), plus optional members `code` (the last segment of `type`), `retryable` and `current`. Existing codes are reused; five are new.

| Code | Status | When | New |
|---|---|---|---|
| `invalid-argument` | 400 | envelope or `values` invalid; `errors[{field, code?, message}]` with JSON Pointers | |
| `unauthenticated` | 401 | no caller | |
| `access-denied` | 403 | lacks `agent:invoke` or `data:write-confirm` | |
| `not-found` | 404 | unknown or foreign conversation, surface, interrupt, turn, or action | |
| `interrupt-resolved` | 409 | already answered, declined, cancelled or superseded | ✔ |
| `stale-surface` | 409 | `expect.digest` or a required revision no longer current; `current{surfaceId, revision, digest?}` | ✔ |
| `turn-active` | 409 | another turn is running in the conversation | ✔ |
| `idempotency-conflict` | 409 | same `interactionId`, different body | ✔ |
| `conflict` | 409 | other state conflicts (existing) | |
| `interrupt-expired` | 410 | the interrupt or surface expired (`proposal-expired` stays for proposals) | ✔ |
| `request-too-large` | 413 | body or message too large | |
| `rate-limited`, `budget-exhausted` | 429 | as today | |
| `endpoint-disabled` | 503 | agent kill switch | |

Errors **before** the stream opens are ordinary HTTP problems; after `200` they are `error` events with the same `code`.

<!-- validate: problem name=problem-stale -->
```json
{
  "type": "https://dynamic-ai/problems/stale-surface",
  "title": "The proposal changed",
  "status": 409,
  "detail": "The content you reviewed is no longer current. Review the updated proposal.",
  "instance": "/dynamic-ai/api/agents/orders-assistant/interactions",
  "code": "stale-surface",
  "retryable": false,
  "current": {
    "surfaceId": "0198f1c2-5d22-7c81-a192-b3c4d5e6f708",
    "revision": 3,
    "digest": "sha256:1370b55b38277acb9bfb8d757a41d1dd6b11025dc764020058366ea41ca1bd9c"
  }
}
```

<!-- validate: problem name=problem-invalid -->
```json
{
  "type": "https://dynamic-ai/problems/invalid-argument",
  "title": "Validation failed",
  "status": 400,
  "detail": "1 parameter(s) failed validation",
  "instance": "/dynamic-ai/api/agents/orders-assistant/interactions",
  "errors": [
    {
      "field": "/values/customer",
      "code": "enum",
      "message": "must be one of the offered options"
    }
  ]
}
```

## 11. Security

Controls are enforced on the server; the client is not trusted. New threat rows (to be added to SEC-02 when this LLD is approved):

| # | Threat | Control |
|---|---|---|
| U1 | A model emits markup, script or styling | closed catalog, plain text by default, restricted Markdown, props closed (§5.5) |
| U2 | A model creates buttons or forms that act | interactive nodes are `server_only`; model trees are validated with S8; actions are declared only by server code |
| U3 | A client forges or replays an action | the client sends only `(surface, revision, actionId, values)`; the handler comes from the server-held record; ids are owner-scoped; `respond` is single use (compare-and-set); `interactionId` is idempotent; unknown ids answer `404` |
| U4 | Confirming stale content (TOCTOU) | `expect.digest` must equal the proposal `contentHash`; the surface is re-projected from the proposal; mismatch is `409 stale-surface` (threat T4) |
| U5 | Prompt injection leads to a write | the model can raise a `confirmation`, which authorizes nothing; only a server-built `proposal_review` leads to a write, through an authenticated user request (threat E5, ADR-0009) |
| U6 | A model asks for secrets or impersonates the system | `blocked-terms` on questions; `origin: model` surfaces are styled as assistant content; the question form shows who is asking |
| U7 | Display spoofing with Unicode | S7 strips bidi overrides and controls; review diffs render zero-width characters visibly |
| U8 | Data exfiltration through links or images | https/mailto only; image host allow-list, never auto-loaded; the client shows the destination before navigation |
| U9 | Fabricated data shown as database facts | tables bind to result handles; the server copies and re-masks (threat I6) |
| U10 | UI event escalates beyond tools | `event` handlers re-enter the tool pipeline as the caller; no handler writes |
| U11 | Algorithmic-complexity or size DoS (deep, wide, huge trees) | limits checked iteratively before schema validation; linear schema dispatch; caps on surfaces per turn, open interrupts, request size |
| U12 | Untrusted `context` or `selection` steers data access | bounded, validated against the catalog, framed as user data; tools authorize as the caller |
| U13 | Cross-user access to surfaces and interrupts | owner-scoped lookups; `404` not `403` (no existence oracle) |
| U14 | CSRF on interactions with cookie sessions | the host's CSRF protection must cover `/dynamic-ai/api/**`; verify (gap analysis "to verify", OQ-61) |
| U15 | Hidden fields smuggled in a request | strict parsing with duplicate-key detection; `additionalProperties: false`; `values` validated against the server-held schema |

Other rules: no prompt content, answers, `values` or row data in logs; audit stores hashes and ids only (`CHAT_ACTION`, `CHAT_INTERRUPT_RAISED`, `CHAT_INTERRUPT_RESOLVED`, `UI_COMMAND_ISSUED`; proposal decisions keep their `DATA_WRITE` events); an interrupt row stores `answer_hash`, not the answer; surfaces may contain row data, so they follow the conversation's retention and erase mode (§12).

## 12. Persistence (design sketch for migration V11)

Conventions as LLD-15 §4: UUIDv7 keys set by the application, `timestamptz` UTC, closed sets as `text` plus `CHECK`, `row_version` for mutable rows, `dai_` prefix. These tables merge into LLD-15 when V11 lands.

```sql
-- design sketch: V11__chat_interaction.sql
CREATE TABLE dai_chat_interrupt (
    id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES dai_workspace (id),
    agent_resource_id uuid REFERENCES dai_resource (id), conversation_id uuid NOT NULL,   -- logical: transcript may be off
    turn_id uuid, owner_id uuid NOT NULL REFERENCES dai_principal (id), surface_id uuid,
    kind text NOT NULL CHECK (kind IN ('QUESTION','CONFIRMATION','PROPOSAL_REVIEW','CLIENT_COMMAND')),
    blocking boolean NOT NULL,
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ANSWERED','DECLINED','CANCELLED','EXPIRED','SUPERSEDED')),
    response_schema jsonb,                                  -- restricted schema; NULL for proposal reviews
    digest text CHECK (digest ~ '^sha256:[0-9a-f]{64}$'),
    proposal_id uuid REFERENCES dai_change_proposal (id) ON DELETE CASCADE,
    answer_hash text, resolved_at timestamptz, expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), row_version bigint NOT NULL DEFAULT 0,
    CHECK ((status = 'OPEN') = (resolved_at IS NULL)),
    CHECK ((kind = 'PROPOSAL_REVIEW') = (proposal_id IS NOT NULL)) );
CREATE INDEX ix_chat_interrupt_open   ON dai_chat_interrupt (owner_id, conversation_id) WHERE status = 'OPEN';
CREATE INDEX ix_chat_interrupt_expiry ON dai_chat_interrupt (expires_at) WHERE status = 'OPEN';

CREATE TABLE dai_chat_surface (
    id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES dai_workspace (id), conversation_id uuid NOT NULL,
    turn_id uuid, owner_id uuid NOT NULL REFERENCES dai_principal (id), interrupt_id uuid,
    kind text NOT NULL CHECK (kind IN ('DISPLAY','QUESTION','CONFIRMATION','PROPOSAL_REVIEW','STATUS')),
    origin text NOT NULL CHECK (origin IN ('SERVER','MODEL','HOST')),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','COMPLETED','SUPERSEDED','EXPIRED','CLOSED')),
    revision integer NOT NULL CHECK (revision >= 1), anchor integer, title text,
    tree jsonb NOT NULL, data jsonb NOT NULL DEFAULT '{}', actions jsonb NOT NULL DEFAULT '{}',
    handlers jsonb NOT NULL DEFAULT '{}',                   -- server-held action records, never sent to clients
    source_version bigint,                                  -- proposal row_version a review was projected from
    expires_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    retention_until timestamptz NOT NULL, row_version bigint NOT NULL DEFAULT 0 );
CREATE INDEX ix_chat_surface_conversation ON dai_chat_surface (owner_id, conversation_id, created_at);
CREATE INDEX ix_chat_surface_retention    ON dai_chat_surface (retention_until);

CREATE TABLE dai_chat_interaction (
    owner_id uuid NOT NULL REFERENCES dai_principal (id), interaction_id uuid NOT NULL, conversation_id uuid,
    type text NOT NULL CHECK (type IN ('MESSAGE','ACTION','RESPOND','CANCEL')), body_hash text NOT NULL,
    status text NOT NULL DEFAULT 'ACCEPTED' CHECK (status IN ('ACCEPTED','COMPLETED','FAILED')),
    turn_id uuid, outcome text, created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
    PRIMARY KEY (owner_id, interaction_id) );
CREATE INDEX ix_chat_interaction_expiry ON dai_chat_interaction (expires_at);

-- also in V11: allow the new finish reason of a turn that ended awaiting input
ALTER TABLE dai_agent_turn DROP CONSTRAINT ck_agent_turn_finish;
ALTER TABLE dai_agent_turn ADD CONSTRAINT ck_agent_turn_finish
    CHECK (finish_reason IN ('STOP','LENGTH','TOOL_LIMIT','BUDGET','CANCELLED','ERROR','INTERRUPT'));   -- and TurnRecorder.Finish.INTERRUPT
```

Phase P2 adds `dai_chat_run` (`conversation_id` primary key, `turn_id`, `node_id`, `lease_until`, `status`, `text_checkpoint`, `last_seq`: the cluster-wide single-turn lease and the in-progress text) and `dai_chat_event` (`turn_id`, `seq`, `type`, `payload jsonb`, `created_at`; primary key `(turn_id, seq)`; structural events only, purged after 24 h).

Retention and erase: surfaces follow the conversation (`retention_until` from `store.retention.conversation-days`; when transcripts are off, `chat.surfaces.retention`, default 24 h after the last update). Interrupts and interactions are purged 7 days after `expires_at`. Erasing a conversation deletes its surfaces and interrupts under the same `erase-mode` rules (OQ-52). `ConversationRetentionJob` gains these purges.


## 13. Server implementation reference

### 13.1 Module placement

| Module | Adds |
|---|---|
| `core` (`core.ui`, `core.interaction`) | `UiSurface`, `UiNode`, action and interrupt records, the `Interaction` sealed hierarchy, `UiCatalog`, `UiTreeValidator`, `UiPatchApplier`, `FormTreeBuilder`, ports `SurfaceStore`, `InterruptStore`, `InteractionLog`, `UiEventHandler`, `UiCommandRegistry`. No Spring Web, JPA or AI imports (CLAUDE.md) |
| `persistence` (`persistence.chat`) | migration V11, JPA entities and the stores implementing the ports; retention and erase hooks |
| `ai` (`ai.interaction`) | `AskUserGate` and the interrupt conversion in `DefaultAgentInvoker`, `AnswerFramer`, `RenderUiToolCallback` (P2), `TurnEventSink`, result-handle cache; `StreamEvent` gains the new events (`tool.*` finally emitted) |
| `webmvc` (`webmvc.chat`) | `AgentInteractionController`, `ConversationStateController`, `SurfaceController`, request parsing, SSE and batch assembly, five new `ProblemCode`s |
| `autoconfigure` | `DaiChatInteractionAutoConfiguration`, `DaiProperties.Chat` extensions, store-backed adapters (`ProposalDecisionService` extracted from `ProposalReviewController`, `ProposalSurfaceFactory`). Library classes are not `@Component`; every default bean is `@ConditionalOnMissingBean`; controllers are mapped by `DaiControllerRegistrar` |
| `review-ui` (new) | Web Components and the JS client (§14) |

### 13.2 Core contracts

The whole input side is a closed sealed hierarchy and a closed switch: adding a fifth interaction type is a compile error until every handler deals with it. The records below compile against the vendored jars.

```java
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import java.time.Instant;
import java.util.*;

/** design sketch: core.interaction / core.ui (no Spring Web, JPA or AI imports). */
public final class Core {
    private Core() {}

    /** One client → server input; parsed by a private strict JsonMapper, never by the host's. */
    public sealed interface Interaction permits Interaction.Message, Interaction.Action, Interaction.Respond, Interaction.Cancel {
        UUID interactionId();
        @Nullable UUID conversationId();

        enum Outcome { ACCEPT, DECLINE, CANCEL }
        record SurfaceRef(UUID id, int revision) {}

        record Message(UUID interactionId, @Nullable UUID conversationId, String text, @Nullable UUID replyTo) implements Interaction {}
        record Action(UUID interactionId, UUID conversationId, SurfaceRef surface, String actionId,
                      Map<String, Object> values, @Nullable String expectDigest) implements Interaction {}
        record Respond(UUID interactionId, UUID conversationId, UUID interruptId, Outcome outcome,
                       Map<String, Object> values, @Nullable String expectDigest) implements Interaction {}
        record Cancel(UUID interactionId, UUID conversationId, UUID turnId) implements Interaction {}
    }

    public record UiNode(String id, String type, Map<String, Object> props, List<UiNode> children,
                         @Nullable Map<String, Object> visibleWhen, @Nullable String fallback) {}

    public enum SurfaceStatus { ACTIVE, COMPLETED, SUPERSEDED, EXPIRED, CLOSED }

    public record UiSurface(UUID surfaceId, UUID conversationId, @Nullable UUID turnId, @Nullable UUID interruptId,
                            int revision, String kind, String origin, SurfaceStatus status, @Nullable Integer anchor,
                            UiNode tree, Map<String, Object> data, Map<String, Map<String, Object>> actions,
                            @Nullable Instant expiresAt) {}

    /** Server-held record of what an action does; never serialized to a client. */
    public record ActionHandler(String kind, @Nullable String name, Map<String, Object> args, boolean repeatable) {}

    public interface UiTreeValidator {
        enum Authoring { MODEL, SERVER, HOST }
        record Violation(String pointer, String code, String message) {}
        /** Limits first (iterative), then structure, then the S1..S9 rules of LLD-17 §5.5. */
        List<Violation> validate(UiSurface surface, Authoring authoring);
    }

    /** Ports implemented by persistence; every lookup is owner-scoped and returns empty on mismatch. */
    public interface SurfaceStore {
        void insert(UiSurface surface, Map<String, ActionHandler> handlers);
        Optional<UiSurface> find(UUID surfaceId, UUID ownerId);
        Optional<ActionHandler> handler(UUID surfaceId, UUID ownerId, String actionId);
        /** Compare-and-set on revision: true when {@code next.revision() == expectedRevision + 1} was stored. */
        boolean replace(UUID surfaceId, UUID ownerId, int expectedRevision, UiSurface next);
    }

    public interface InterruptStore {
        enum Status { OPEN, ANSWERED, DECLINED, CANCELLED, EXPIRED, SUPERSEDED }
        record Interrupt(UUID id, UUID conversationId, String kind, boolean blocking, Status status,
                         @Nullable String digest, Instant expiresAt) {}
        void insert(UUID ownerId, Interrupt interrupt);
        Optional<Interrupt> find(UUID id, UUID ownerId);
        /** {@code UPDATE … WHERE status = 'OPEN'}: true for exactly one caller. */
        boolean resolve(UUID id, UUID ownerId, Status to, @Nullable String answerHash);
    }

    public interface UiEventHandler {
        String name();
        /** Runs as the caller; answers with ui.patch / ui.surface events. */
        Flux<Object> handle(UUID ownerId, UiSurface surface, ActionHandler record, Map<String, Object> values);
    }

    /** Dispatch is a closed switch: adding an interaction type is a compile error until it is handled. */
    public static <R> R dispatch(Interaction in, java.util.function.Function<Interaction.Message, R> message,
                                 java.util.function.Function<Interaction.Action, R> action,
                                 java.util.function.Function<Interaction.Respond, R> respond,
                                 java.util.function.Function<Interaction.Cancel, R> cancel) {
        return switch (in) {
            case Interaction.Message m -> message.apply(m);
            case Interaction.Action a -> action.apply(a);
            case Interaction.Respond r -> respond.apply(r);
            case Interaction.Cancel c -> cancel.apply(c);
        };
    }
}
```

Parsing uses a private `JsonMapper` (never a bean, ADR-0019) configured like `PolicyDocumentParser` plus `StreamReadConstraints`; Jackson 3 annotations stay in `com.fasterxml.jackson.annotation` (`@JsonTypeInfo`, `@JsonSubTypes`, jackson-annotations 2.21).

### 13.3 The `ask_user` gate (compiled and verified against Spring AI 2.0.1)

```java
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** design sketch: the loop never executes a response that asks the user; the runtime turns it into an interrupt. */
public final class AskUserGate implements ToolExecutionEligibilityChecker {

    /** Protocol-level control tool; not a host tool, so it is not authorized or recorded like one. */
    public static final String TOOL = "ask_user";

    @Override
    public Boolean apply(ChatResponse response) {
        return response.hasToolCalls() && findAskUser(response).isEmpty();
    }

    /** The first ask_user call of a model response, if any; other calls of that response are dropped. */
    public static Optional<AssistantMessage.ToolCall> findAskUser(ChatResponse response) {
        return response.getResults().stream()
                .flatMap(g -> g.getOutput().getToolCalls().stream())
                .filter(c -> TOOL.equals(c.name()))
                .findFirst();
    }

    /** Registers the advisor with the gate; Spring AI must not also auto-register its own. */
    public static ToolCallingAdvisor advisor(org.springframework.ai.model.tool.ToolCallingManager manager) {
        return ToolCallingAdvisor.builder()
                .toolCallingManager(manager)
                .toolExecutionEligibilityChecker(new AskUserGate())
                .build();
    }

    /** The definition the model sees; call() is a safety net because the loop never executes it. */
    public static final class Definition implements ToolCallback {
        private final ToolDefinition definition;

        public Definition(String inputSchemaJson) {
            this.definition = ToolDefinition.builder()
                    .name(TOOL)
                    .description("Ask the user for information you need to continue. Never ask for passwords, keys or tokens.")
                    .inputSchema(inputSchemaJson)
                    .build();
        }

        @Override public ToolDefinition getToolDefinition() { return definition; }

        @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().returnDirect(true).build(); }

        @Override public String call(String toolInput) { throw new IllegalStateException("ask_user is handled by the runtime"); }

        @Override public String call(String toolInput, ToolContext toolContext) { return call(toolInput); }
    }

    /** design sketch: thread-safe per-turn side channel for events raised on tool threads. */
    public static final class TurnSink<E> {
        private final Sinks.Many<E> sink = Sinks.many().unicast().onBackpressureBuffer();

        public void emit(E event) { sink.emitNext(event, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100))); }

        public void complete() { sink.tryEmitComplete(); }

        public reactor.core.publisher.Flux<E> events() { return sink.asFlux(); }
    }

    static List<String> names(ChatResponse r) {
        return r.getResults().stream().flatMap(g -> g.getOutput().getToolCalls().stream()).map(AssistantMessage.ToolCall::name).toList();
    }
}
```

`DefaultAgentInvoker.buildChatClient` registers `AskUserGate.advisor(...)` instead of relying on auto-registration, and `findAskUser` on the aggregated response creates the interrupt. `TurnSink` is the thread-safe side channel for events raised on tool threads (`render_ui`, proposal surfaces); structural events are persisted before they are emitted, so a full sink drops only `tool.*`.

### 13.4 Controller skeleton

```java
// design sketch (webmvc.chat)
@RequestMapping("/dynamic-ai/api/agents/{slug}")
public final class AgentInteractionController {

    @PostMapping(value = "/interactions", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = { MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE })
    public Object interact(@PathVariable String slug, @RequestBody String body, HttpServletRequest http) {
        var gate = gateway.admit(slug, body, http);            // §6.6 steps 1-5: size, parse, authn/z, kill switch, limits, references
        if (gate.problem() != null) return problemResponse(gate);          // ordinary HTTP problem before any stream
        Flux<StreamEvent> events = dispatcher.dispatch(gate.interaction(), gate.caller());   // sealed switch, §6.4
        return wantsSse(http)
                ? sse(events, gate)                                         // heartbeat bounded by the content, as LLD-13 §4
                : events.collectList().map(list -> batch(list, gate));      // Mono<ResponseEntity<String>>: no thread blocked
    }
}
```

`sse(...)` is the existing assembly of `AgentChatController.chatStream` (idle timeout, bounded backpressure buffer, `takeUntilOther` heartbeat, terminal `error` events with a code only), extracted and shared.

### 13.5 Configuration (`dynamic.ai.agent.chat.*`)

Existing: `stream-idle-timeout` (20 s), `max-message-chars` (32000).

| Property | Default | Description |
|---|---|---|
| `interactions.enabled` | `true` | exposes `/interactions`, `/state`, `/surfaces/{id}`; `false` keeps the legacy endpoints only |
| `interactions.max-request-bytes` | `65536` | request size cap |
| `interactions.dedupe-window` | `24h` | idempotency window |
| `interactions.proposal-decision-turn` | `false` | start a model turn after a proposal decision |
| `interrupts.ttl` | `30m` | question and confirmation lifetime |
| `interrupts.max-open` | `5` | open interrupts per conversation |
| `ask-user.enabled` | `true` | offer `ask_user` to agents that enable it |
| `ask-user.blocked-terms` | password, passcode, secret, token, api key, credit card, cvv | refused in questions and labels (case-insensitive) |
| `ui.max-nodes` / `max-depth` / `max-surface-bytes` | `400` / `10` / `262144` | S4 limits |
| `ui.max-inline-rows` / `max-surfaces-per-turn` | `200` / `8` | S4 limits |
| `ui.link-schemes` | `https, mailto` | S9 |
| `ui.image-hosts` | `[]` | empty: only `attachment:` images |
| `ui.render-tool.enabled` | `false` | `render_ui` (P2) |
| `ui.commands.allowed` | `[]` | command names the server may send (P2) |
| `surfaces.retention` | `24h` | surfaces when transcripts are off |
| `stream.replay.backend` | `memory` | `memory` or `postgres` (P2) |
| `stream.text-checkpoint-interval` | `1s` | in-progress text checkpoint (P2) |

Per agent (`AgentDefinition.interaction`, new optional member; absent means off, so existing revisions behave as before): `askUser`, `renderUi`, `commands[]`. Agents with `output.mode = JSON_SCHEMA` cannot use `ask_user` or surfaces. Validation of the spec follows OQ-41.

### 13.6 Other channels

MCP clients do not use this protocol. Over MCP, `ask_user` is not offered; an agent exposed as `ask_<agent>` that would ask returns a tool result with `status: needs_input` and the question as text, and the human continues in the chat UI. A client that declares the MCP `elicitation` capability on a stateful transport could be mapped to a `question` interrupt (§21); the stateless default cannot hold the round trip (ADR-0021).

## 14. Client implementation reference

### 14.1 Architecture

Three parts, deliberately small:

1. **Transport**: `fetch()` with a stream reader for SSE, the `Last-Event-ID` rules of §8.5, `AbortController` for stop. Retries reuse the same `interactionId` (§14.3).
2. **Reducer**: a pure function `(state, event) → state`. The same function folds live events, replayed events, JSON batches and `state.snapshot`.
3. **Renderer**: maps node types to components and turns user activations into `action` interactions. It knows nothing about what an action does.

### 14.2 Reference reducer (TypeScript, run against the validated examples)

The reducer ignores unknown events (tolerant reader), ignores surfaces with a lower or equal revision, refuses a patch whose base revision does not match (flagging the surface for re-fetch), and treats `state.snapshot` as authoritative. It is tested against every event example in this document.

```ts
// Reference stream reducer for dai-stream/2 (LLD-17 §14). Pure function: (state, event) -> state.
// The same reducer folds live SSE events, JSON batch responses and state.snapshot, so reload == live.

export type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
export interface Node { id: string; type: string; props?: Record<string, Json>; children?: Node[]; visibleWhen?: Json; fallback?: string }
export interface Surface {
  surfaceId: string; revision: number; kind: string; origin: string; status: string; catalog: string;
  turnId?: string; interruptId?: string; anchor?: number; title?: string;
  tree: Node; data: Record<string, Json>; actions: Record<string, Json>;
}
export interface SurfaceRef { surfaceId: string; anchor: number }
export interface Message {
  key: string; role: 'user' | 'assistant'; turnId?: string; text: string; surfaces: SurfaceRef[];
  status: 'streaming' | 'done' | 'interrupted' | 'cancelled' | 'error';
}
export interface InterruptView { interruptId: string; kind: string; status: string; blocking: boolean; surfaceId?: string }
export interface ChatState {
  conversationId?: string;
  cursor?: string;                       // "<turnId>:<seq>" of the last applied event, sent back as Last-Event-ID
  messages: Message[];
  surfaces: Record<string, Surface>;
  interrupts: Record<string, InterruptView>;
  resync: string[];                      // surfaces whose patch did not apply: re-fetch them
  commands: Json[];                      // ui.command events for the host app to handle
  error?: { code: string; retryable: boolean };
}
export type DaiEvent = { type: string; seq?: number; [k: string]: any };

export const initialState = (): ChatState => ({ messages: [], surfaces: {}, interrupts: {}, resync: [], commands: [] });

export function reduce(s: ChatState, ev: DaiEvent, sseId?: string): ChatState {
  const next = step(s, ev);
  return sseId ? { ...next, cursor: sseId } : next;
}

function step(s: ChatState, ev: DaiEvent): ChatState {
  const cur = s.messages[s.messages.length - 1];
  const streaming = cur && cur.role === 'assistant' && cur.status === 'streaming';
  switch (ev.type) {
    case 'turn.start':
      return { ...s, conversationId: ev.conversationId, error: undefined,
        messages: [...s.messages, { key: ev.turnId, role: 'assistant', turnId: ev.turnId, text: '', surfaces: [], status: 'streaming' }] };
    case 'text.delta':
      return streaming ? withLast(s, { ...cur, text: cur.text + ev.text }) : s;
    case 'text.snapshot':                // resume after a gap: the snapshot replaces what was streamed so far
      return streaming ? withLast(s, { ...cur, text: ev.text }) : s;
    case 'ui.surface': {
      const incoming: Surface = ev.surface;
      const known = s.surfaces[incoming.surfaceId];
      if (known && known.revision >= incoming.revision) return s;            // stale or duplicate
      let messages = s.messages;
      if (!known && streaming && incoming.turnId === cur.turnId) {           // first sight: anchor it in the message
        const anchor = incoming.anchor ?? cur.text.length;
        messages = [...s.messages.slice(0, -1), { ...cur, surfaces: [...cur.surfaces, { surfaceId: incoming.surfaceId, anchor }] }];
      }
      return { ...s, messages, surfaces: { ...s.surfaces, [incoming.surfaceId]: incoming } };
    }
    case 'ui.patch': {
      const sf = s.surfaces[ev.surfaceId];
      if (!sf || sf.revision !== ev.baseRevision) return { ...s, resync: [...new Set([...s.resync, ev.surfaceId])] };
      let tree = sf.tree, data = sf.data, actions = { ...sf.actions };
      for (const op of ev.tree ?? []) tree = applyTreeOp(tree, op);
      if (ev.data?.length) data = applyJsonPatch(data, ev.data);
      for (const [k, v] of Object.entries(ev.actions ?? {})) { if (v === null) delete actions[k]; else actions[k] = v as Json; }
      return { ...s, surfaces: { ...s.surfaces, [ev.surfaceId]: { ...sf, tree, data, actions, revision: ev.revision } } };
    }
    case 'ui.status': {
      const sf = s.surfaces[ev.surfaceId];
      return sf && ev.revision >= sf.revision ? { ...s, surfaces: { ...s.surfaces, [ev.surfaceId]: { ...sf, status: ev.status, revision: ev.revision } } } : s;
    }
    case 'ui.command':
      return { ...s, commands: [...s.commands, ev as Json] };
    case 'interrupt.raised':
      return { ...s, interrupts: { ...s.interrupts, [ev.interruptId]: { interruptId: ev.interruptId, kind: ev.kind, status: 'open', blocking: ev.blocking, surfaceId: ev.surfaceId } } };
    case 'interrupt.resolved': {
      const it = s.interrupts[ev.interruptId];
      return it ? { ...s, interrupts: { ...s.interrupts, [ev.interruptId]: { ...it, status: ev.status } } } : s;
    }
    case 'turn.end':
      return streaming ? withLast(s, { ...cur, status: ev.finishReason === 'interrupt' ? 'interrupted' : ev.finishReason === 'cancelled' ? 'cancelled' : 'done' }) : s;
    case 'error':
      return { ...(streaming ? withLast(s, { ...cur, status: 'error' }) : s), error: { code: ev.code, retryable: ev.retryable } };
    case 'state.snapshot':               // authoritative: replaces everything
      return {
        conversationId: ev.conversationId, cursor: ev.cursor, resync: [], commands: [],
        messages: ev.messages.map((m: any, i: number) => ({ key: m.turnId ?? `m${i}`, role: m.role, turnId: m.turnId, text: m.text, surfaces: m.surfaces ?? [], status: 'done' as const })),
        surfaces: Object.fromEntries(ev.surfaces.map((x: Surface) => [x.surfaceId, x])),
        interrupts: Object.fromEntries(ev.interrupts.map((x: InterruptView) => [x.interruptId, x])),
      };
    default:                             // tolerant reader: unknown events (and tool.*, usage, proposal.*) never break the UI
      return s;
  }
}

const withLast = (s: ChatState, m: Message): ChatState => ({ ...s, messages: [...s.messages.slice(0, -1), m] });

// ---- tree operations (id-addressed) ----------------------------------------------------------------------------
function mapTree(n: Node, f: (n: Node) => Node | null): Node | null {
  const r = f(n);
  if (r === null) return null;
  if (!r.children) return r;
  return { ...r, children: r.children.map((c) => mapTree(c, f)).filter((c): c is Node => c !== null) };
}
export function applyTreeOp(root: Node, op: any): Node {
  switch (op.op) {
    case 'replace': return mapTree(root, (n) => (n.id === op.id ? op.node : n)) ?? root;
    case 'update': return mapTree(root, (n) => {
      if (n.id !== op.id) return n;
      const props = { ...(n.props ?? {}) };
      for (const [k, v] of Object.entries(op.props)) { if (v === null) delete props[k]; else props[k] = v as Json; }
      return { ...n, props };
    }) ?? root;
    case 'remove': return mapTree(root, (n) => (n.id === op.id ? null : n)) ?? root;
    case 'insert': return mapTree(root, (n) => {
      if (n.id !== op.parentId) return n;
      const kids = [...(n.children ?? [])]; kids.splice(op.index ?? kids.length, 0, op.node); return { ...n, children: kids };
    }) ?? root;
    case 'move': {
      let moved: Node | undefined;
      const without = mapTree(root, (n) => { if (n.id === op.id) { moved = n; return null; } return n; }) ?? root;
      if (!moved) return root;
      return mapTree(without, (n) => {
        if (n.id !== op.parentId) return n;
        const kids = [...(n.children ?? [])]; kids.splice(op.index ?? kids.length, 0, moved!); return { ...n, children: kids };
      }) ?? root;
    }
    default: throw new Error(`unknown tree op ${op.op}`);
  }
}

// ---- RFC 6902 over the data model (RFC 6901 pointers) ------------------------------------------------------------
const unescape = (t: string) => t.replace(/~1/g, '/').replace(/~0/g, '~');
const tokens = (p: string) => (p === '' ? [] : p.split('/').slice(1).map(unescape));
function getAt(doc: any, p: string): any { return tokens(p).reduce((a, t) => a?.[t], doc); }
function setAt(doc: any, p: string, value: any, mode: 'add' | 'replace'): any {
  const t = tokens(p);
  if (t.length === 0) return value;
  const go = (node: any, i: number): any => {
    const k = t[i], last = i === t.length - 1;
    if (Array.isArray(node)) {
      const copy = node.slice(); const idx = k === '-' ? copy.length : Number(k);
      if (last) { if (mode === 'add') copy.splice(idx, 0, value); else copy[idx] = value; } else copy[idx] = go(copy[idx], i + 1);
      return copy;
    }
    const copy = { ...node };
    if (last) copy[k] = value; else copy[k] = go(copy[k], i + 1);
    return copy;
  };
  return go(doc, 0);
}
function removeAt(doc: any, p: string): any {
  const t = tokens(p);
  const go = (node: any, i: number): any => {
    const k = t[i], last = i === t.length - 1;
    if (Array.isArray(node)) { const c = node.slice(); if (last) c.splice(Number(k), 1); else c[Number(k)] = go(c[Number(k)], i + 1); return c; }
    const c = { ...node }; if (last) delete c[k]; else c[k] = go(c[k], i + 1); return c;
  };
  return go(doc, 0);
}
export function applyJsonPatch(doc: any, ops: any[]): any {
  let d = doc;
  for (const op of ops) {
    switch (op.op) {
      case 'add': d = setAt(d, op.path, op.value, 'add'); break;
      case 'replace': d = setAt(d, op.path, op.value, 'replace'); break;
      case 'remove': d = removeAt(d, op.path); break;
      case 'move': { const v = getAt(d, op.from); d = setAt(removeAt(d, op.from), op.path, v, 'add'); break; }
      case 'copy': d = setAt(d, op.path, structuredClone(getAt(d, op.from)), 'add'); break;
      case 'test': if (JSON.stringify(getAt(d, op.path)) !== JSON.stringify(op.value)) throw new Error('test failed'); break;
      default: throw new Error(`unknown data op ${op.op}`);
    }
  }
  return d;
}
```

### 14.3 Sending interactions

1. Create `interactionId` (UUIDv7) when the user acts, not when the request is sent; keep it for retries.
2. Disable the activated control and set `aria-busy` until the response ends. One in-flight interaction per surface.
3. Send `{type: "action", surface: {id, revision}, actionId, values, expect: {digest}}`; `values` is the object at the action's `submit` pointer, `expect.digest` is the declaration's `digest`.
4. On a network error or `5xx`, retry with the **same** body and `interactionId` (safe: §6.5). On `409 stale-surface` fetch `current.surfaceId` and re-render; on `409 interrupt-resolved` show "already answered" and fetch state; on `410` show "expired" and offer to ask again; on `401` re-authenticate, then retry.
5. Never apply an optimistic result for a confirmation: show what the server returns.

### 14.4 Renderer rules (normative)

| # | Rule |
|---|---|
| R1 | Render only catalog types the client implements; for others show `fallback`, or a neutral "unsupported content" placeholder |
| R2 | Text is plain unless `format: markdown`; Markdown renders the §5.5 subset with raw HTML disabled; links get `rel="noopener noreferrer"` and display their destination |
| R3 | Images load only after a click, from allow-listed hosts, `alt` always present |
| R4 | `origin: model` surfaces live inside the assistant message and use assistant styling; review and system chrome is for `origin: server` only |
| R5 | A non-`active` or expired surface is read-only and shows its state |
| R6 | A `change_review` shows **what will be written exactly as sent**: before, after, validation, expiry, approval. Never compute or edit the diff locally |
| R7 | A `confirm` friction dialog (if present) precedes sending; it is UX only |
| R8 | `ui.command`: execute only commands the host registered; `requiresGesture` commands behind a button; never navigate automatically |
| R9 | Unknown props, events and fields are ignored, never an error |
| R10 | The client sends no data the user did not enter or select: `values` come only from the surface's own data model |

### 14.5 Accessibility and internationalization

- Streaming text sits in an `aria-live="polite"` region; progress and status use `role="status"`.
- On `interrupt.raised` with `blocking: true` move focus to the first input (or the primary action) of the surface and give the form the surface `title` as its accessible name. Enter submits (`form.submit`), Esc runs `form.cancel`.
- Tables: required `caption`, header cells with `scope`, sortable headers as buttons with `aria-sort`.
- Diffs: label values "Was" and "Now" in text; do not rely on colour or strike-through alone.
- Timed interrupts: show the remaining time and offer "ask again" after expiry (WCAG 2.2.1).
- Respect `prefers-reduced-motion`; surfaces carry the conversation language in `lang`; support right-to-left layouts.
- Server-authored labels arrive localized (`Accept-Language`, `context.locale`); dates and numbers are formatted by the client from ISO values and `valueFormat`.

### 14.6 Web Components and host UIs

`<saimcp-chat endpoint agent conversation-id>` owns transport, reducer and composer; `<saimcp-surface surface-id>` renders one surface from a reducer store. A host sets `fetch` (to add its own auth), `entityResolver(entityRef, id) → route` for `entity_link` and `open_entity`, `renderers` (override a node type), and themes with `--saimcp-*` CSS custom properties. The component dispatches `saimcp-action` (cancelable) before sending, so a host can observe or veto. React hosts can use the same reducer and a mapping from node `type` to React components: the node shape `{id, type, props, children}` is deliberately React-element-like.

### 14.7 Conformance

A renderer is conformant when it passes (a) the validated examples of this document rendered to the expected structure (fixtures will be extracted from this file), (b) every entry of `negative-corpus.json` refused or neutralised, and (c) axe checks on the question, table and proposal-review surfaces.


## 15. Worked flows

In `sse` blocks `"surface": "@name"` abbreviates the full surface object shown earlier in this document (the real event carries the object); `validate.mjs` substitutes it before validating.

### 15.1 The AI asks, the user answers (P1)

The user types a message. Two customers match, so the model calls `ask_user`.

```http
POST /dynamic-ai/api/agents/orders-assistant/interactions HTTP/1.1
Content-Type: application/json
Accept: text/event-stream

{
  "protocol": "dai-stream/2",
  "interactionId": "0198f1c0-3a9d-7d02-a3b4-1e2f3a4b5c6d",
  "type": "message",
  "text": "Show me ACME's open orders",
  "context": {
    "locale": "en-GB",
    "timeZone": "Europe/Berlin",
    "client": {
      "name": "saimcp-chat",
      "version": "1.0.0",
      "catalog": "dai-ui/1",
      "features": [
        "patch",
        "markdown"
      ]
    },
    "page": {
      "route": "/customers/4711",
      "title": "ACME GmbH"
    },
    "selection": [
      {
        "entity": "entity:com.acme.customer.Customer",
        "id": 4711
      }
    ]
  }
}
```

Response `200 text/event-stream`:

```sse
id: 0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70:0
event: turn.start
data: {"type":"turn.start","turnId":"0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70","conversationId":"0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01","agent":"orders-assistant","revision":7,"protocol":"dai-stream/2","interactionId":"0198f1c0-3a9d-7d02-a3b4-1e2f3a4b5c6d"}

id: 0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70:1
event: tool.call
data: {"type":"tool.call","callId":"call_1","tool":"find_customers","argsPreview":"name=\"ACME\""}

id: 0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70:2
event: tool.result
data: {"type":"tool.result","callId":"call_1","status":"ok","summary":"2 rows","handle":"r1"}

id: 0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70:3
event: text.delta
data: {"type":"text.delta","text":"Two customers match"}

id: 0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70:4
event: ui.surface
data: {"type":"ui.surface","surface":"@surface-question"}

id: 0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70:5
event: interrupt.raised
data: {"type":"interrupt.raised","interruptId":"0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10","kind":"question","blocking":true,"surfaceId":"0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50","expiresAt":"2026-10-01T10:45:00Z"}

id: 0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70:6
event: usage
data: {"type":"usage","inputTokens":1420,"outputTokens":87,"costMicros":0,"model":"gpt-4.1"}

id: 0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70:7
event: turn.end
data: {"type":"turn.end","finishReason":"interrupt","messageId":"0198f1c1-0b77-7c88-9d99-aabbccddeeff","interrupts":["0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10"]}
```

The user picks a customer and presses Send. The client sends an `action` (the surface's data `/form` becomes `values`):

```http
POST /dynamic-ai/api/agents/orders-assistant/interactions HTTP/1.1
Content-Type: application/json
Accept: text/event-stream

{
  "protocol": "dai-stream/2",
  "interactionId": "0198f1c1-09f0-7b21-8d32-3e4f5a6b7c8d",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "type": "action",
  "surface": {
    "id": "0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50",
    "revision": 1
  },
  "actionId": "a_submit",
  "values": {
    "customer": "4711"
  }
}
```

The server validates `values` against the interrupt's response schema, resolves the interrupt, frames the answer (§7.5) and starts a resumed turn:

```sse
id: 0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f:0
event: turn.start
data: {"type":"turn.start","turnId":"0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f","conversationId":"0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01","agent":"orders-assistant","revision":7,"protocol":"dai-stream/2","interactionId":"0198f1c1-09f0-7b21-8d32-3e4f5a6b7c8d","resumes":{"interruptId":"0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10"}}

id: 0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f:1
event: interrupt.resolved
data: {"type":"interrupt.resolved","interruptId":"0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10","status":"answered","by":"user"}

id: 0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f:2
event: ui.status
data: {"type":"ui.status","surfaceId":"0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50","revision":2,"status":"completed"}

id: 0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f:3
event: tool.call
data: {"type":"tool.call","callId":"call_3","tool":"find_orders","argsPreview":"customerId=4711, status=\"OPEN\""}

id: 0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f:4
event: tool.result
data: {"type":"tool.result","callId":"call_3","status":"ok","summary":"2 rows","handle":"r1"}

id: 0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f:5
event: text.delta
data: {"type":"text.delta","text":"ACME GmbH has two open orders:"}

id: 0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f:6
event: ui.surface
data: {"type":"ui.surface","surface":"@surface-table"}

id: 0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f:7
event: usage
data: {"type":"usage","inputTokens":1420,"outputTokens":87,"costMicros":0,"model":"gpt-4.1"}

id: 0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f:8
event: turn.end
data: {"type":"turn.end","finishReason":"stop","messageId":"0198f1c1-0b77-7c88-9d99-aabbccddeeff"}
```

The table surface (§5.7) was built by `render_ui` from handle `r1` (P2). In P1 the same answer is plain text.

### 15.2 A change proposal, reviewed and confirmed (P1)

"Mark order 101 as shipped, tracking 1Z999." The mutating tool is in PROPOSE mode, so nothing is written; the server builds the review from the stored proposal.

```sse
id: 0198f1c2-5a00-7e10-8f20-9a0b1c2d3e4f:0
event: turn.start
data: {"type":"turn.start","turnId":"0198f1c2-5a00-7e10-8f20-9a0b1c2d3e4f","conversationId":"0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01","agent":"orders-assistant","revision":7,"protocol":"dai-stream/2","interactionId":"0198f1c2-5900-7b31-8c42-5d6e7f8091a2"}

id: 0198f1c2-5a00-7e10-8f20-9a0b1c2d3e4f:1
event: tool.call
data: {"type":"tool.call","callId":"call_2","tool":"mark_order_shipped","argsPreview":"orderId=101, trackingNo=\"1Z999\""}

id: 0198f1c2-5a00-7e10-8f20-9a0b1c2d3e4f:2
event: tool.result
data: {"type":"tool.result","callId":"call_2","status":"proposed","summary":"change proposal created"}

id: 0198f1c2-5a00-7e10-8f20-9a0b1c2d3e4f:3
event: proposal.created
data: {"type":"proposal.created","proposalId":"0198f1c2-5d10-7a60-9b71-8c9d0e1f2a3b","summary":"Mark order 101 as shipped and record tracking number 1Z999","reviewUrl":"/dynamic-ai/api/proposals/0198f1c2-5d10-7a60-9b71-8c9d0e1f2a3b","surfaceId":"0198f1c2-5d22-7c81-a192-b3c4d5e6f708","interruptId":"0198f1c2-5d21-7d92-b2a3-c4d5e6f70819"}

id: 0198f1c2-5a00-7e10-8f20-9a0b1c2d3e4f:4
event: ui.surface
data: {"type":"ui.surface","surface":"@surface-review"}

id: 0198f1c2-5a00-7e10-8f20-9a0b1c2d3e4f:5
event: interrupt.raised
data: {"type":"interrupt.raised","interruptId":"0198f1c2-5d21-7d92-b2a3-c4d5e6f70819","kind":"proposal_review","blocking":false,"surfaceId":"0198f1c2-5d22-7c81-a192-b3c4d5e6f708","digest":"sha256:1370b55b38277acb9bfb8d757a41d1dd6b11025dc764020058366ea41ca1bd9c","expiresAt":"2026-10-01T11:00:00Z"}

id: 0198f1c2-5a00-7e10-8f20-9a0b1c2d3e4f:6
event: text.delta
data: {"type":"text.delta","text":"I've prepared the change. Please review it below before anything is written."}

id: 0198f1c2-5a00-7e10-8f20-9a0b1c2d3e4f:7
event: usage
data: {"type":"usage","inputTokens":1420,"outputTokens":87,"costMicros":0,"model":"gpt-4.1"}

id: 0198f1c2-5a00-7e10-8f20-9a0b1c2d3e4f:8
event: turn.end
data: {"type":"turn.end","finishReason":"stop","messageId":"0198f1c1-0b77-7c88-9d99-aabbccddeeff"}
```

The user reads the diff and presses *Apply change*; the friction dialog (`confirm`) is shown first. The request has **no model in it**: it is an authenticated user request carrying the digest of what was shown.

```http
POST /dynamic-ai/api/agents/orders-assistant/interactions HTTP/1.1
Content-Type: application/json
Accept: text/event-stream

{
  "protocol": "dai-stream/2",
  "interactionId": "0198f1c3-7a10-7e20-9f31-a0b1c2d3e4f5",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "type": "action",
  "surface": {
    "id": "0198f1c2-5d22-7c81-a192-b3c4d5e6f708",
    "revision": 1
  },
  "actionId": "a_confirm",
  "expect": {
    "digest": "sha256:1370b55b38277acb9bfb8d757a41d1dd6b11025dc764020058366ea41ca1bd9c"
  }
}
```

The server runs the proposal decision (owner, `data:write-confirm`, hash equals digest, not expired), applies through the host's own write path as the user (ADR-0008), and answers:

```sse
id: 0198f1c3-7a10-7e20-9f31-a0b1c2d3e4f5:0
event: interrupt.resolved
data: {"type":"interrupt.resolved","interruptId":"0198f1c2-5d21-7d92-b2a3-c4d5e6f70819","status":"answered","by":"user"}

id: 0198f1c3-7a10-7e20-9f31-a0b1c2d3e4f5:1
event: ui.patch
data: {"type":"ui.patch","surfaceId":"0198f1c2-5d22-7c81-a192-b3c4d5e6f708","baseRevision":1,"revision":2,"tree":[{"op":"update","id":"review","props":{"state":"APPLIED","hostRevision":"JPA_VERSION:8"}},{"op":"remove","id":"buttons"}],"actions":{"a_confirm":null,"a_decline":null}}

id: 0198f1c3-7a10-7e20-9f31-a0b1c2d3e4f5:2
event: proposal.updated
data: {"type":"proposal.updated","proposalId":"0198f1c2-5d10-7a60-9b71-8c9d0e1f2a3b","state":"APPLIED","surfaceId":"0198f1c2-5d22-7c81-a192-b3c4d5e6f708"}

id: 0198f1c3-7a10-7e20-9f31-a0b1c2d3e4f5:3
event: proposal.applied
data: {"type":"proposal.applied","proposalId":"0198f1c2-5d10-7a60-9b71-8c9d0e1f2a3b"}

id: 0198f1c3-7a10-7e20-9f31-a0b1c2d3e4f5:4
event: ui.status
data: {"type":"ui.status","surfaceId":"0198f1c2-5d22-7c81-a192-b3c4d5e6f708","revision":3,"status":"completed"}

id: 0198f1c3-7a10-7e20-9f31-a0b1c2d3e4f5:5
event: interaction.end
data: {"type":"interaction.end","interactionId":"0198f1c3-7a10-7e20-9f31-a0b1c2d3e4f5","outcome":"ok"}
```

### 15.3 A UI instruction without the model (P2)

*Show more* is an `action` of kind `event`. The client asks for JSON and gets the same events as a batch:

```http
POST /dynamic-ai/api/agents/orders-assistant/interactions HTTP/1.1
Content-Type: application/json
Accept: application/json

{
  "protocol": "dai-stream/2",
  "interactionId": "0198f1c2-0001-7c00-8a00-000000000003",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "type": "action",
  "surface": {
    "id": "0198f1c1-2e40-7a15-9b26-3c4d5e6f7a81",
    "revision": 1
  },
  "actionId": "a_more"
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "protocol": "dai-stream/2",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "events": [
    {
      "type": "ui.patch",
      "seq": 0,
      "surfaceId": "0198f1c1-2e40-7a15-9b26-3c4d5e6f7a81",
      "baseRevision": 1,
      "revision": 2,
      "data": [
        {
          "op": "add",
          "path": "/rows/-",
          "value": {
            "id": 103,
            "status": "OPEN",
            "total": "42.00"
          }
        },
        {
          "op": "replace",
          "path": "/page/hasMore",
          "value": false
        }
      ]
    },
    {
      "type": "interaction.end",
      "seq": 1,
      "interactionId": "0198f1c2-0001-7c00-8a00-000000000003",
      "outcome": "ok"
    }
  ]
}
```

### 15.4 Errors

Each is an ordinary HTTP problem because it happens before a stream opens.

Another window changed the proposal after the user opened it; the digest no longer matches:

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{
  "type": "https://dynamic-ai/problems/stale-surface",
  "title": "The proposal changed",
  "status": 409,
  "detail": "The content you reviewed is no longer current. Review the updated proposal.",
  "instance": "/dynamic-ai/api/agents/orders-assistant/interactions",
  "code": "stale-surface",
  "retryable": false,
  "current": {
    "surfaceId": "0198f1c2-5d22-7c81-a192-b3c4d5e6f708",
    "revision": 3,
    "digest": "sha256:1370b55b38277acb9bfb8d757a41d1dd6b11025dc764020058366ea41ca1bd9c"
  }
}
```

The question was already answered in another tab:

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{
  "type": "https://dynamic-ai/problems/interrupt-resolved",
  "title": "Already answered",
  "status": 409,
  "detail": "This question was already answered, here or in another window.",
  "instance": "/dynamic-ai/api/agents/orders-assistant/interactions",
  "code": "interrupt-resolved",
  "retryable": false
}
```

The question expired while the form was open:

```http
HTTP/1.1 410 Gone
Content-Type: application/problem+json

{
  "type": "https://dynamic-ai/problems/interrupt-expired",
  "title": "The question expired",
  "status": 410,
  "detail": "Ask again or send a new message.",
  "instance": "/dynamic-ai/api/agents/orders-assistant/interactions",
  "code": "interrupt-expired",
  "retryable": false
}
```

A message was sent while a turn is still running:

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{
  "type": "https://dynamic-ai/problems/turn-active",
  "title": "The assistant is still answering",
  "status": 409,
  "detail": "Wait for the current answer or cancel it first.",
  "instance": "/dynamic-ai/api/agents/orders-assistant/interactions",
  "code": "turn-active",
  "retryable": true
}
```

A forged or foreign surface or action id (no existence oracle):

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{
  "type": "https://dynamic-ai/problems/not-found",
  "title": "Not found",
  "status": 404,
  "detail": "No such surface or action.",
  "instance": "/dynamic-ai/api/agents/orders-assistant/interactions"
}
```

### 15.5 Reload

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "type": "state.snapshot",
  "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
  "cursor": "0198f1c1-0a01-7f33-9c44-7a8b9c0d1e2f:31",
  "messages": [
    {
      "seq": 0,
      "role": "user",
      "text": "Show me ACME's open orders"
    },
    {
      "seq": 1,
      "role": "assistant",
      "turnId": "0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70",
      "text": "There are two customers called ACME.",
      "surfaces": [
        {
          "surfaceId": "0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50",
          "anchor": 36
        }
      ]
    }
  ],
  "surfaces": [
    {
      "surfaceId": "0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50",
      "conversationId": "0198f1c0-3a5e-7b10-8c2d-4f6a7b8c9d01",
      "turnId": "0198f1c0-3b20-7a44-9e11-2c3d4e5f6a70",
      "interruptId": "0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10",
      "revision": 2,
      "kind": "question",
      "origin": "model",
      "status": "completed",
      "catalog": "dai-ui/1",
      "title": "Which customer?",
      "tree": {
        "id": "root",
        "type": "form",
        "props": {
          "submit": "a_submit",
          "cancel": "a_dismiss"
        },
        "children": [
          {
            "id": "q",
            "type": "text",
            "props": {
              "value": "There are two customers called ACME. Which one do you mean?",
              "variant": "title"
            }
          },
          {
            "id": "f_customer",
            "type": "select",
            "props": {
              "name": "customer",
              "label": "Customer",
              "bind": "/form/customer",
              "required": true,
              "options": [
                {
                  "value": "4711",
                  "label": "ACME GmbH (Berlin)"
                },
                {
                  "value": "4712",
                  "label": "ACME Ltd (Leeds)"
                }
              ]
            }
          },
          {
            "id": "buttons",
            "type": "stack",
            "props": {
              "direction": "horizontal",
              "gap": "sm"
            },
            "children": [
              {
                "id": "b_submit",
                "type": "button",
                "props": {
                  "label": "Send",
                  "action": "a_submit",
                  "variant": "primary"
                }
              },
              {
                "id": "b_decline",
                "type": "button",
                "props": {
                  "label": "Skip",
                  "action": "a_decline",
                  "variant": "link"
                }
              }
            ]
          }
        ]
      },
      "data": {
        "form": {}
      },
      "actions": {
        "a_submit": {
          "kind": "respond",
          "outcome": "accept",
          "submit": "/form"
        },
        "a_decline": {
          "kind": "respond",
          "outcome": "decline",
          "label": "Skip"
        },
        "a_dismiss": {
          "kind": "respond",
          "outcome": "cancel"
        }
      },
      "expiresAt": "2026-10-01T10:45:00Z"
    }
  ],
  "interrupts": [
    {
      "interruptId": "0198f1c0-4c0f-7aa8-b7c9-6d5e4f3a2b10",
      "kind": "question",
      "status": "answered",
      "blocking": true,
      "surfaceId": "0198f1c0-4c11-7e55-8a66-0b1c2d3e4f50",
      "expiresAt": "2026-10-01T10:45:00Z"
    }
  ]
}
```

## 16. Failure modes & resilience

| Failure | Detection | Behaviour | Recovery |
|---|---|---|---|
| `ask_user` arguments invalid or blocked | validator | degrade to a plain-text question (principle 9) | user answers with a `message` |
| Surface fails validation (model or host authored) | `UiTreeValidator` | surface dropped, counter `ui.rejected{reason}`, text answer still delivered | none needed; fix the agent prompt or tool |
| SSE connection lost during a turn | client | reconnect with `Last-Event-ID`; if not replayable, load `state` | P2 makes it work across nodes |
| Node dies mid-turn | P2: lease expiry | reaper writes terminal `error` (`node-lost`); client sees it on resume | user resends |
| User double-clicks or retries after a timeout | `interactionId` | recorded outcome returned, nothing runs twice | none needed |
| Two tabs answer the same interrupt | compare-and-set | loser gets `409 interrupt-resolved` | client fetches state |
| Interrupt or surface expires | `expiresAt` check on access, sweep | `410 interrupt-expired`; surface becomes `expired` | user sends a new message |
| Proposal changed after review | digest | `409 stale-surface` with `current` | client re-renders the new review |
| Host write fails or conflicts | LLD-11 states | surface shows `FAILED`/`CONFLICT` with a sanitized reason; nothing is retried | user asks the assistant to propose again |
| PostgreSQL unavailable | store exception | interactions that need state answer `503`; plain chat without interrupts keeps working where it does not need the new tables; the data plane is unaffected (LLD-12) | automatic |
| Client lacks a node type | `context.client`, `fallback` | server downgrades or the client shows the fallback | upgrade the client |
| Rate limit or budget hit | gate | `429`; `respond`, `cancel`, `decline` exempt (§6.7) | wait |

## 17. Observability

| Kind | Name |
|---|---|
| Spans (child of `dai.agent.turn` when a turn runs) | `dai.chat.interaction` (type, outcome), `dai.chat.interrupt` (kind, status), `dai.chat.ui.validate` (surface kind, rejected) |
| Meters | `dynamic.ai.agent.chat.interactions{type,outcome}`, `…interrupts{kind,status}`, `…surfaces{kind,origin}`, `…ui.rejected{reason}`, `…interrupt.latency` (raise to resolve), `…idempotent.replays`, `…streams.open` (gauge) |
| Audit | `CHAT_ACTION`, `CHAT_INTERRUPT_RAISED`, `CHAT_INTERRUPT_RESOLVED`, `UI_COMMAND_ISSUED` (ids, kinds, outcomes, digest hashes; never values) |
| Logs | ids and codes only; never `text`, `values`, `context` or surface content |
| Trace viewer | turns ending in `INTERRUPT` show the raised interrupt and its resolution |

## 18. Performance & capacity

- One primary-key read of the surface (including handlers) per `action`; one compare-and-set per `respond`. Both are on small rows (typically < 10 KiB, capped at 256 KiB).
- `event` actions run through the normal tool pipeline, so its bulkheads, timeouts and parallelism (LLD-14) apply; they cost no tokens.
- Validation is linear in tree size (§5.5); a 400-node surface validates in milliseconds, and limits reject larger ones before any recursion.
- Stateless: no sticky sessions. SSE streams are the only long-lived connections; they are bounded by the existing per-stream buffer and idle timeout and by a per-node open-stream cap (LLD-14 bulkheads).
- Write volume: one surface insert and one interrupt insert per question; one update per surface change; interactions rows are tiny and purged after the window. P2 event persistence excludes token deltas on purpose.
- Capacity sketch: 1,000 active users, 1 interaction per user per minute is about 17 rows/s of small inserts and updates, well inside the PostgreSQL default of ADR-0021.

## 19. Test strategy

| # | Test | Level |
|---|---|---|
| T-1 | **Contract**: every example in this file validates; `negative-corpus.json` is rejected; Java `UiTreeValidator` passes the same fixtures as `validate.mjs` | unit |
| T-2 | `ask_user` suspension: scripted `ChatModel` emits `ask_user` (alone, mixed with a read tool, fragmented tool-call chunks, invalid arguments); assert one `ToolCallingAdvisor`, no other tool ran, memory is `USER, ASSISTANT(question)`, `finish_reason = INTERRUPT` | `HostApplicationIT` |
| T-3 | Answer on another node: raise on instance A, answer on instance B sharing PostgreSQL (Testcontainers); double submit; two tabs; expiry; decline; cancel | IT |
| T-4 | Proposal: tool proposes → review surface in the stream → confirm via `action` with digest → host revision shows the confirming user; stale digest `409`; forged action id `404`; foreign user `404`; confirm while rate limited and while the agent is kill-switched (decline allowed) | IT |
| T-5 | Idempotency: same id and body replays the outcome, different body `409`, retry after a lost response does not apply twice | IT |
| T-6 | Security corpus: markup, script, bidi and control characters, `javascript:` and `data:` URLs, hostile Markdown, duplicate JSON keys, hidden fields, oversize and deep trees (depth 1000), `selection` with unknown entities | unit and IT |
| T-7 | Patches: applying `ui.patch` equals replacing with the resulting surface (property test); stale `baseRevision` is refused | unit |
| T-8 | Reducer: live fold equals snapshot fold for generated event sequences; unknown events ignored | JS unit |
| T-9 | Renderer conformance and axe on question, table and review surfaces; keyboard-only run through ask and confirm | browser |
| T-10 | SSE: terminal event always present, heartbeat stops with the content, client cancel cancels the turn, batch mode equals stream mode | StepVerifier, MockMvc |
| T-11 | Load: 200 concurrent streams plus 20 interactions/s on a small node; no thread starvation | performance |

## 20. Phasing & work breakdown

| Phase | Scope | Exit criteria |
|---|---|---|
| **P1: interactions and interrupts** | V11 tables; `Interaction` parsing and gate; `/interactions` (SSE and JSON), `/state`, `/surfaces/{id}`; `ask_user` with `AskUserGate`; question and confirmation surfaces; proposal review surface, `proposal.*` events and `action`-based confirm through a shared `ProposalDecisionService`; emit `tool.*`; idempotency in PostgreSQL; new problem codes; node set of §5.4 except `chart`-class reserved types | T-1 to T-6, T-10 green; F-43, F-45, F-52 acceptance with the stream-linked review; the example flows 15.1 (text answer) and 15.2 run in `HostApplicationIT` |
| **P2: richness and durability** | `render_ui` with result handles; presenters; patches and `event` actions (`tool.page`, sort, refresh, apply); `ui.command`; durable event log, run lease, cross-node resume (OQ-42); `poll` | T-7, T-8, T-11; resume works across two nodes |
| **P3: extensions** | client commands and `step_up` interrupts; host node types and commands; AG-UI and MCP adapters if wanted (OQ-62) | per item |

Work items P1 by module, in order: (1) `core.ui` records, validator, `FormTreeBuilder` and the shared fixtures; (2) V11, entities, stores; (3) `ai`: gate, interrupt conversion, `AnswerFramer`, `StreamEvent` additions, `TurnRecorder.Finish.INTERRUPT`; (4) `ProposalDecisionService` extraction and `ProposalSurfaceFactory`; (5) `webmvc` controllers and problem codes; (6) autoconfigure wiring and properties; (7) `HostApplicationIT` flows; (8) `review-ui` reducer and renderer (can start in parallel after (1)). Spike S-1 (T-2) comes first because it decides §7.4.

## 21. Interoperability and prior art

Verified on 2026-09-30 from primary sources (the public docs hosts were blocked in the build sandbox, so repository copies were read): AG-UI `docs/concepts/events.mdx`, A2UI `specification/v0_8`, `v0_9`, `v1_0` protocol documents, the MCP 2025-11-25 elicitation page and the MCP Apps specification (SEP-1865) in `modelcontextprotocol/ext-apps`.

| Protocol | What it is | Relation to this design |
|---|---|---|
| **AG-UI** | event protocol agent ↔ frontend: `RunStarted/Finished/Error`, `TextMessage*`, `ToolCall*`, `StateSnapshot`, `StateDelta` (RFC 6902), `MessagesSnapshot`, `ActivitySnapshot/Delta`; interrupts end a run with `outcome: {type: "interrupt", interrupts: [...]}` and resume with a new run carrying `resume` | closest in spirit; **the suspend-then-resume model is the same**. Adapter mapping: `turn.start`→`RunStarted`, `text.delta`→`TextMessageContent`, `tool.*`→`ToolCall*`, `ui.surface`/`ui.patch`→`Activity*`, `state.snapshot`→`MessagesSnapshot`+`StateSnapshot`, `turn.end(interrupt)`→`RunFinished(interrupt)`, `respond`→`resume`. Our tree ops are id-addressed, so an adapter translates them to JSON Patch over the stored surface |
| **A2UI** | declarative UI JSON; flat adjacency list with `root`; `createSurface`, `updateComponents`, `updateDataModel`, `deleteSurface`; client `action` with `context`; catalogs; no executable code. Message names changed between v0.8 (`surfaceUpdate`, `beginRendering`, `userAction`) and v0.9/v1.0 | **ideas adopted**: surfaces, data model separate from structure with JSON Pointers, actions with resolved context, closed catalogs, no code. **Wire format not adopted**: it is still changing, and we want a nested tree (what the product asked for, simplest for server-built trees) with id-addressed patches. Revisit the flat form only if we stream model-authored trees token by token |
| **MCP elicitation** | server asks the client for input: `elicitation/create`, form mode with a flat primitive schema, URL mode, results `accept`/`decline`/`cancel`; forms must not collect secrets | **adopted verbatim** for question schemas and outcomes (`McpSchema.ElicitFormRequest`/`ElicitResult` exist in the vendored MCP SDK 2.0.0). A `question` interrupt can be mapped to an elicitation on a stateful transport |
| **MCP Apps** | `ui://` resources rendered as sandboxed HTML iframes, `postMessage` JSON-RPC | a different trade-off (executable UI in a sandbox). We choose declarative data: strict host CSP, default deny. A generic renderer could later be shipped as an MCP App resource |

## 22. Open questions

Recorded in `docs/open-questions.md`:

| ID | Question | Proposal |
|---|---|---|
| OQ-56 | Answer framing as user text, or real tool-call pairing (needs tool messages in memory, OQ-45) | framing for v1; revisit with OQ-45 |
| OQ-57 | Live tail across nodes for resumed streams: PostgreSQL polling or `LISTEN/NOTIFY` behind a `ChatEventBus` port | polling default |
| OQ-58 | Should a proposal decision start a model turn | no; record a server note |
| OQ-59 | Host-defined node types and commands: registry, signing, allow-list | P3 |
| OQ-60 | Persist surfaces when transcripts are off | only operational ones (open interrupts, active reviews), short TTL |
| OQ-61 | CSRF coverage of `/dynamic-ai/api/**` for cookie sessions | verify, then document |
| OQ-62 | Are AG-UI or MCP Apps adapters a product requirement | ask the product owner |

## Appendix A: node prop reference

Generated from `dai-ui-1.schema.json`. All props objects are closed.

#### `stack` (model_safe, container)

| Prop | Type / constraint |
|---|---|
| `direction` | `vertical`, `horizontal` |
| `gap` | `none`, `sm`, `md`, `lg` |
| `align` | `start`, `center`, `end`, `stretch` |
| `wrap` | boolean |

#### `card` (model_safe, container)

| Prop | Type / constraint |
|---|---|
| `title` | string ≤ 200 or `{"$data": pointer}` |
| `subtitle` | string ≤ 300 or `{"$data": pointer}` |
| `tone` | tone |

#### `section` (model_safe, container)

| Prop | Type / constraint |
|---|---|
| `heading` **(required)** | string 1–200 |
| `collapsible` | boolean |
| `collapsed` | boolean |

#### `divider` (model_safe)

No props.

#### `text` (model_safe)

| Prop | Type / constraint |
|---|---|
| `value` **(required)** | string ≤ 20000 or `{"$data": pointer}` |
| `variant` | `body`, `caption`, `title`, `heading`, `mono` |
| `format` | `plain`, `markdown` |
| `tone` | tone |

#### `badge` (model_safe)

| Prop | Type / constraint |
|---|---|
| `label` **(required)** | string 1–60 or `{"$data": pointer}` |
| `tone` | tone |

#### `callout` (model_safe, container)

| Prop | Type / constraint |
|---|---|
| `tone` **(required)** | tone |
| `title` | string ≤ 200 |

#### `code` (model_safe)

| Prop | Type / constraint |
|---|---|
| `value` **(required)** | string ≤ 20000 or `{"$data": pointer}` |
| `language` | string (pattern) |
| `wrap` | boolean |

#### `link` (model_safe)

| Prop | Type / constraint |
|---|---|
| `label` **(required)** | string 1–200 |
| `href` **(required)** | string (pattern) |

#### `image` (model_safe)

| Prop | Type / constraint |
|---|---|
| `src` **(required)** | string (pattern) |
| `alt` **(required)** | string 1–300 |
| `width` | integer 1..4000 |
| `height` | integer 1..4000 |

#### `kv` (model_safe)

| Prop | Type / constraint |
|---|---|
| `items` **(required)** | array ≤ 50 of object |
| `layout` | `inline`, `stacked` |

#### `table` (model_safe)

| Prop | Type / constraint |
|---|---|
| `caption` **(required)** | string 1–200 |
| `columns` **(required)** | array ≤ 30 of object |
| `rows` **(required)** | `{"$data": pointer}` or array ≤ 200 of object |
| `rowKey` | string (pattern) |
| `emptyText` | string ≤ 200 |
| `hasMore` | boolean or `{"$data": pointer}` |
| `onSort` | action id |
| `onPage` | action id |

#### `list` (model_safe)

| Prop | Type / constraint |
|---|---|
| `items` **(required)** | `{"$data": pointer}` or array ≤ 200 of string ≤ 500 or object |
| `ordered` | boolean |

#### `timeline` (model_safe)

| Prop | Type / constraint |
|---|---|
| `events` **(required)** | `{"$data": pointer}` or array ≤ 100 of object |

#### `entity_link` (model_safe)

| Prop | Type / constraint |
|---|---|
| `entity` **(required)** | string (pattern) |
| `id` **(required)** | string/integer |
| `label` **(required)** | string 1–200 |

#### `progress` (model_safe)

| Prop | Type / constraint |
|---|---|
| `value` | number 0..1 or `{"$data": pointer}` |
| `indeterminate` | boolean |
| `label` **(required)** | string 1–200 |

#### `form` (server_only, container)

| Prop | Type / constraint |
|---|---|
| `submit` **(required)** | action id |
| `cancel` | action id |
| `title` | string ≤ 200 |

#### `text_input` (server_only)

| Prop | Type / constraint |
|---|---|
| `name` **(required)** | string (pattern) |
| `label` **(required)** | string 1–200 |
| `bind` **(required)** | JSON Pointer |
| `required` | boolean |
| `help` | string ≤ 300 |
| `placeholder` | string ≤ 200 |
| `multiline` | boolean |
| `minLength` | integer 0..20000 |
| `maxLength` | integer 1..20000 |
| `pattern` | string ≤ 200 |
| `format` | `email`, `uri` |

#### `number_input` (server_only)

| Prop | Type / constraint |
|---|---|
| `name` **(required)** | string (pattern) |
| `label` **(required)** | string 1–200 |
| `bind` **(required)** | JSON Pointer |
| `required` | boolean |
| `help` | string ≤ 300 |
| `integer` | boolean |
| `minimum` | number |
| `maximum` | number |
| `step` | number |

#### `select` (server_only)

| Prop | Type / constraint |
|---|---|
| `name` **(required)** | string (pattern) |
| `label` **(required)** | string 1–200 |
| `bind` **(required)** | JSON Pointer |
| `required` | boolean |
| `help` | string ≤ 300 |
| `options` **(required)** | array ≤ 100 of object |
| `presentation` | `dropdown`, `radio` |

#### `multi_select` (server_only)

| Prop | Type / constraint |
|---|---|
| `name` **(required)** | string (pattern) |
| `label` **(required)** | string 1–200 |
| `bind` **(required)** | JSON Pointer |
| `required` | boolean |
| `help` | string ≤ 300 |
| `options` **(required)** | array ≤ 100 of object |
| `minItems` | integer 0..100 |
| `maxItems` | integer 1..100 |

#### `checkbox` (server_only)

| Prop | Type / constraint |
|---|---|
| `name` **(required)** | string (pattern) |
| `label` **(required)** | string 1–200 |
| `bind` **(required)** | JSON Pointer |
| `required` | boolean |
| `help` | string ≤ 300 |
| `presentation` | `checkbox`, `switch` |

#### `date_input` (server_only)

| Prop | Type / constraint |
|---|---|
| `name` **(required)** | string (pattern) |
| `label` **(required)** | string 1–200 |
| `bind` **(required)** | JSON Pointer |
| `required` | boolean |
| `help` | string ≤ 300 |
| `kind` | `date`, `date-time` |
| `minimum` | string ≤ 40 |
| `maximum` | string ≤ 40 |

#### `button` (server_only)

| Prop | Type / constraint |
|---|---|
| `label` **(required)** | string 1–100 |
| `action` **(required)** | action id |
| `variant` | `primary`, `secondary`, `danger`, `link` |
| `disabled` | boolean or `{"$data": pointer}` |

#### `suggestion_chips` (server_only)

| Prop | Type / constraint |
|---|---|
| `items` **(required)** | array ≤ 6 of object |

#### `change_review` (server_only)

| Prop | Type / constraint |
|---|---|
| `proposalId` **(required)** | UUID |
| `presentation` **(required)** | `diff`, `form`, `delete`, `bulk` |
| `changeKind` **(required)** | `CREATE`, `UPDATE`, `DELETE`, `BULK` |
| `summary` **(required)** | string 1–500 |
| `state` **(required)** | proposal state |
| `records` **(required)** | array ≤ 100 of object |
| `validation` | object |
| `approval` **(required)** | object |
| `expiresAt` | string |
| `contentHash` **(required)** | sha256 |
| `failure` | object |
| `hostRevision` | string ≤ 100 |


## Appendix B: checklists

**API design review** (a change to this protocol passes when all are true)

- [ ] a new client input is a new `Interaction` type or a new action kind, not a new endpoint
- [ ] every new event is additive and ignorable by an old client
- [ ] every new node type has a class, a closed props schema, `fallback` guidance, an accessibility note and negative-corpus entries
- [ ] nothing a model writes can create an action, a URL scheme, a command or markup outside the catalog
- [ ] the server can recompute everything it trusts from its own records
- [ ] reload produces the same state as live (snapshot equals fold)
- [ ] no per-node state is required to serve any request
- [ ] schema dispatch stays linear; limits are checked before recursion

**Implementer** (server): parse strictly with a private mapper; check limits first; owner-scope every lookup and answer `404`; compare-and-set interrupt resolution; persist then emit; never log values; extend the negative corpus with every bug found. (Client): one reducer; tolerant reader; never trust or compute what the server must decide; keep `interactionId` across retries; follow rules R1 to R10.
