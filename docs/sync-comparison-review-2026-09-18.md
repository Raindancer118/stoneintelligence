# StoneIntelligence sync comparison review

Date: 2026-09-18  
StoneIntelligence revision: `4c1d2964c389f58f88e9cbeb8dffd2a1d4def1b1`

## Scope and reference revisions

This is a read-only source comparison, not a generic architecture review. I read the relevant plugin and relay code, `Plan.md`, and all ADRs. The review therefore treats these as intentional decisions rather than findings by themselves:

- Yjs is the authoritative note representation; Markdown is a projection.
- The Java relay deliberately stores opaque Yjs updates instead of interpreting them.
- One multiplexed WebSocket per vault is deliberate.
- WebSocket authentication uses short-lived, single-use tickets.
- `OperationJournal` and the room registry are currently in-memory/single-instance by documented choice.
- E2EE is planned but not yet implemented.

Reference source was read at these commits:

| Repository | Revision |
|---|---|
| `yjs/y-websocket` | `f786ae973510505a129b31d14a166b2809d1f506` |
| `ueberdosis/hocuspocus` | `a8e3d285c398676d8b8a2fcacbf4592cc79cff8d` |
| `yjs/y-codemirror.next` | `91db20f03fbbda764fccfbcbb8257712b5f9a6bd` |
| `caaatto/obsidian-cosync` | `ab170391e713eff2ab092ce5b370259aeb667c64` |
| `chikn8/obsidian-collab` | `94590a85eba056a64de7aefd0c14f82993fc6c19` |
| `leonestis/obsidian-concord` | `a33c6291b41d0cd024c2d8d69c906608b949fe4e` |

Line references to reference projects are relative to `/tmp/codex-research/sources/<repo>/`. They are pinned to the commits above.

## Executive summary

The explicit `TYPE_CATCHUP_COMPLETE` frame is a meaningful improvement over a quiet-period guess, but StoneIntelligence is still missing the more important property mature Yjs providers rely on: a state-vector handshake on every connection. As written, a local Yjs update created while the shared socket is disconnected is silently discarded and is not retransmitted after reconnect. That is a real data-loss bug.

The most urgent server-side issue is authorization: a client that can `JOIN` with `READ` permission may send document updates, so `READ` is effectively `WRITE`. The code documents this limitation, but it remains an exploitable authorization bug rather than a harmless trade-off.

The other high-priority problems are stale/late awareness, a catchup/live-update ordering race, timeout-as-success during initial seeding, and editor-binding races caused by Obsidian reusing `EditorView` instances. These can each produce user-visible corruption, ghost cursors, or edits applied to the wrong document.

## Priority overview

| Priority | Severity | Finding |
|---:|---|---|
| P0 | **Bug — data loss** | Offline/local updates are dropped and never reconciled after reconnect |
| P0 | **Bug — authorization** | `READ` permission grants effective WebSocket write access |
| P1 | **Bug — data corruption** | Catchup-complete can overtake a concurrent live update |
| P1 | **Bug — data corruption** | Catchup timeout is treated as success and may seed an untrusted empty document |
| P1 | **Bug — presence** | Awareness is neither replayed nor explicitly removed across joins/disconnects |
| P1 | **Bug — editor correctness** | Async binding and a single global binding slot can attach the wrong Y.Text/view |
| P1 | **Bug/risk — auth lifecycle** | Logout or terminal refresh rejection does not stop an already authenticated socket |
| P1 | **Risk — resource/backpressure** | Unbounded full-history joins and shared-socket fanout have no flow control |
| P2 | **Risk — availability** | Reconnect uses fixed delay, has duplicate scheduling paths, and lacks a watchdog |
| P2 | **Risk — bootstrap** | Two clients can independently seed the same genuinely empty note |
| P2 | **Bug/risk — CM6 lifecycle** | A new default UndoManager is created on each rebind; initial editor state may stay stale |
| P2 | **Bug — protocol validation** | Unknown message types are persisted as document updates |
| P2 | **Risk — fanout isolation** | One failed recipient send aborts broadcast to later recipients |
| P2 | **Risk — lifecycle leak** | Last leave/unload does not close the shared socket or destroy Yjs resources |
| P3 | **Nice-to-have** | “connected” means JOIN sent, not JOIN accepted or document caught up |

## Detailed findings

### P0 — Offline updates are silently lost and never reconciled

**Severity:** Bug — data loss

**StoneIntelligence evidence**

- `plugin/src/sync/SyncClient.ts:70-77` emits every local Y.Doc update once.
- `plugin/src/sync/multiplexedTransport.ts:241-256` silently returns when the physical socket is not open.
- On reconnect, `plugin/src/sync/multiplexedTransport.ts:145-156` only re-enqueues JOINs. There is no durable outbox, acknowledgement, state vector, or resend of current document state.
- The server's JOIN path only pushes server history (`platform-api/.../sync/relay/SyncRelayService.java:29-35`); it never asks the client what the server is missing.

This means an edit made after socket loss but before reconnection mutates the local `Y.Doc`, is dropped by the transport, and will never reach the server unless a later mechanism happens to encode the missing structs. The current protocol has no such mechanism. Replaying the server's log cannot recover client-only data.

**Reference pattern**

- `y-websocket/src/y-websocket.js:256-280` sends Yjs Sync Step 1 on every open and republishes local awareness. The state-vector exchange naturally transmits client updates the remote side lacks.
- Hocuspocus does the same in `packages/provider/src/HocuspocusProvider.ts:537-569`; its close path explicitly says buffered updates may be dropped because the reconnect handshake reconciles them from the document (`:594-605`).
- The hobby mux implementation also sends Sync Step 1 for every virtual provider on socket open (`obsidian-collab/plugin/src/collab/MuxProvider.ts:316-319,367-371`).

**Action**

Replace the one-way “history dump” protocol with the Yjs sync protocol per note, carried inside the existing multiplex envelope. At minimum, every JOIN/reconnect must exchange state vectors and transfer missing structs in both directions. A durable per-note outbox with server acknowledgements can supplement this, but an in-memory queue alone is insufficient across plugin restart.

Add an integration test: disconnect A, edit A, reconnect A, then connect B and assert B receives the offline edit. Repeat across plugin/process restart if offline durability is a product requirement.

### P0 — `READ` permission grants effective write access

**Severity:** Bug — authorization/security

**StoneIntelligence evidence**

- JOIN checks only `Permission.READ` (`SyncWebSocketHandler.java:84-104`).
- Once joined, any non-awareness/non-control frame is accepted as an update and persisted (`SyncWebSocketHandler.java:67-80`; `SyncRelayService.java:37-41`).
- A distinct `Permission.WRITE` exists (`platform-api/.../identity/Permission.java:3-8`) and path-aware checks already exist (`VaultAccessGuard.java:25-35`).
- `TicketController.java:15-24` explicitly documents that READ currently grants full channel read/write.

An actor with read-only access can therefore permanently mutate a note's CRDT history. This is concrete source-to-sink exploitability, not merely a missing defense-in-depth control.

**Reference pattern**

- Hocuspocus authenticates each document before establishing its connection and returns the effective read-only capability (`packages/server/src/ClientConnection.ts:502-543`).
- The per-document `Connection` carries `readOnly` as authenticated state (`packages/server/src/Connection.ts:64-85`), rather than treating socket admission as blanket write authorization.

**Action**

At JOIN, resolve and retain per-note capabilities for that session. Require path-scoped `WRITE` for `TYPE_DOC_UPDATE`; READ may receive catchup and, if desired, awareness. Re-evaluate or close affected joins when ACL/path rules change. Return an explicit JOIN-denied/read-only response so the plugin can disable editing or surface the state.

Add security tests proving a READ-only actor can catch up but cannot append, while WRITE works only within the allowed path scope.

### P1 — Catchup-complete is not ordered against concurrent live updates

**Severity:** Bug — data corruption race

**StoneIntelligence evidence**

`SyncRelayService.onJoin` performs three independent actions: add the session to the live registry, read history, then send `TYPE_CATCHUP_COMPLETE` (`SyncRelayService.java:29-35`). A concurrent `onUpdate` independently appends and broadcasts (`:37-41`). `WebSocketSyncSession.send` serializes each frame (`SyncWebSocketHandler.java:189-204`), but it does not serialize the whole join snapshot with concurrent broadcasts.

A possible ordering is:

1. Joiner enters the room.
2. `listSince(0)` returns an empty/old list.
3. Another client appends an update and starts a broadcast to the joiner.
4. JOIN sends catchup-complete before that broadcast acquires the per-session send lock.
5. The joiner observes empty + complete, seeds local full text, then receives the remote update. Independent full-text inserts merge additively.

**Reference pattern**

- y-websocket marks a provider synced only when it receives Yjs Sync Step 2 (`y-websocket/src/y-websocket.js:31-51`).
- Hocuspocus likewise sets `synced` on Sync Step 2 (`packages/provider/src/MessageReceiver.ts:75-91`). This establishes document convergence through the CRDT protocol instead of treating database enumeration EOF as convergence.

**Action**

Best: use the Yjs sync handshake per virtual document. If the custom history protocol remains, add a per-room sequencer: capture a high-water sequence, send history through that sequence, queue later live updates for the joining session, send catchup-complete, then release queued updates in order. Include the high-water sequence in the completion frame and track it client-side.

Add a deterministic test that pauses the join between history query and completion while another client appends.

### P1 — Catchup timeout is still treated as successful sync

**Severity:** Bug — data corruption/data loss

**StoneIntelligence evidence**

- `awaitConnected` resolves after 30 seconds even if the note never joined (`plugin/src/main.ts:465-484`).
- `awaitCatchupComplete` also resolves after 30 seconds without seeing the explicit signal (`:492-506`).
- `mergeInitialContent` then treats an empty `Y.Text` as authoritative evidence of an empty server and inserts the whole local file (`:581-605`).

The new explicit frame removes the old timing guess only on the success path. The timeout path still converts “unknown/not synced” into “server is empty,” exactly the unsafe inference the signal was meant to eliminate. Combined with the dropped-send bug, the seed may also be discarded while the UI proceeds as if initialization succeeded.

**Reference pattern**

- Hocuspocus documents that `synced` means only the initial handshake, not persistence of all local updates (`packages/provider/src/HocuspocusProvider.ts:490-495`).
- Concord explicitly models `"synced" | "timeout"` and forbids seed/adopt/merge on timeout (`obsidian-concord/plugin/src/util.ts:97-150`; `plugin/src/text-session.ts:98-115`).

**Action**

Make timeout a failure/paused state, never a successful prerequisite for seeding. Keep retrying in the background and run initial reconciliation only after a genuine catchup/sync event. Older servers without completion support should fail closed with an upgrade message, not bootstrap from an untrusted empty document.

### P1 — Awareness has stale-cursor and late-join gaps

**Severity:** Bug — presence correctness

**StoneIntelligence evidence**

- Awareness is forwarded but not retained (`SyncRelayService.java:43-50`). A late joiner receives no current states until peers next emit a heartbeat/change.
- Leave/connection-close only removes room membership (`SyncRelayService.java:52-54`; `SyncWebSocketHandler.java:119-129`); no awareness tombstone is broadcast.
- The plugin does not remove remote awareness on close (`SyncClient.ts:112-125`) and does not republish local awareness on reconnect. Because JOIN itself generates no awareness event, the local state can remain invisible after reconnect.

Peers can show ghost cursors until awareness GC, and a late joiner/reconnected client can temporarily show nobody. Multiplexing amplifies this because one physical drop invalidates every note's presence at once.

**Reference pattern**

- y-websocket removes all remote states on close and republishes local state on open (`y-websocket/src/y-websocket.js:167-196,264-280`).
- Hocuspocus sends current document awareness to a new connection (`packages/server/src/Connection.ts:224-238`) and tracks/removes that connection's client IDs on disconnect (`packages/server/src/Document.ts:174-185`). Its client also clears remote awareness on close (`packages/provider/src/HocuspocusProvider.ts:594-616`).
- The hobby mux mirrors these semantics (`obsidian-collab/plugin/src/collab/MuxProvider.ts:181-187,316-327,374-389`).

**Action**

Track awareness client IDs per `(session,note)` on the relay, send the current awareness snapshot on JOIN, and broadcast removals on LEAVE/socket close. On the plugin, clear remote states immediately on physical disconnect and explicitly resend local state after each successful JOIN/reconnect. Ensure session teardown clears its local state and destroys awareness.

### P1 — Obsidian editor binding can target the wrong file/view

**Severity:** Bug — editor correctness and possible cross-note corruption

**StoneIntelligence evidence**

- Every `file-open` starts an unsequenced async binding operation (`plugin/src/main.ts:215-220`).
- `updateLiveEditorBinding` captures the current `EditorView`, awaits `startSync`, then reconfigures that captured view without checking whether it still displays the same file (`:611-643`). A rapid A→B switch can complete A after B and bind A's Y.Text to B's reused editor.
- One global `liveBoundPath` and one compartment model only one active binding. Multiple Markdown panes/leaves can retain an old extension while the corresponding session is marked non-live, allowing the CM binding and full-text bridge to act concurrently.

**Reference pattern**

- CoSync serializes binding operations specifically because Obsidian fires overlapping leaf/file events (`obsidian-cosync/src/main.ts:237-265`) and maintains per-`EditorView` state (`src/sync.ts:562-618`).
- Concord's vendored binding documents the upstream reconfiguration trap: Obsidian reuses an `EditorView`, while upstream plugins capture Y.Text/awareness at construction (`obsidian-concord/plugin/src/yedit/README.md:5-31`).

**Action**

Use a binding registry keyed by `EditorView`, with the expected file path/note ID and an explicit unbind callback. Serialize refreshes per view or use a monotonically increasing generation token. After every await, verify that the view is alive and still displays the expected file before dispatching. Handle all visible Markdown leaves, not only `activeEditor`.

Add tests for rapid A→B→A switching, two simultaneous panes, settings reload, and plugin unload.

### P1 — Logout/terminal auth failure leaves the authorized socket alive

**Severity:** Bug/risk — authentication lifecycle

**StoneIntelligence evidence**

- `logout()` only clears stored tokens (`plugin/src/main.ts:190-193`). The already ticket-authenticated WebSocket and note sessions remain active.
- A definitive refresh rejection clears tokens (`:152-167`), but `MultiplexedTransport` catches all ticket/URL acquisition errors identically and retries forever every two seconds (`multiplexedTransport.ts:180-204`). There is no persistent “login required” state.
- `saveSettings()` rebuilds REST/auth clients but does not tear down the cached transport or existing sessions (`main.ts:762-771`), so server/vault/OIDC changes can leave the old authenticated channel running.

The recently fixed error classification is correct inside token refresh. It is not yet propagated into the WebSocket lifecycle.

**Reference pattern**

- Hocuspocus obtains a token on each provider open (`packages/provider/src/HocuspocusProvider.ts:537-552`) and cleans per-document state on authentication failure (`packages/server/src/ClientConnection.ts:502-558`).
- Concord stops the reconnect loop on authentication failure and requires an explicit token change/reconnect for recovery (`obsidian-concord/plugin/src/main.ts:546-575`).

**Action**

Propagate typed terminal/transient failures from `getAccessToken`/`TicketClient` into the transport. On logout or terminal rejection: stop reconnecting, close/destroy the shared socket, pause all sessions, clear presence, and expose a durable “login required” status. On relevant settings changes: destroy and rebuild the connection/session boundary instead of only swapping clients. Keep transient network/5xx failures retryable with backoff.

### P1 — Full-history JOIN and shared-socket fanout are unbounded

**Severity:** Risk — memory, latency, availability, backpressure

**StoneIntelligence evidence**

- Every JOIN materializes and sends the full history from sequence zero (`SyncRelayService.java:29-35`; `JdbcSnapshotStore.java:56-66`).
- Repeated JOIN of an already joined note still replays history because `Set.add` is not checked before `relay.onJoin` (`SyncWebSocketHandler.java:91-105`).
- There is no cap on joined notes per socket, JOIN rate, history bytes/frames, or outbound buffered data.
- All note traffic shares one TCP/WebSocket stream, and each send is serialized on the whole session (`SyncWebSocketHandler.java:189-204`). A large catchup therefore creates head-of-line blocking for active-note edits and awareness. One physical drop takes down every virtual note.

**Reference pattern**

- Hocuspocus does support deliberate multi-document multiplexing through a shared `HocuspocusProviderWebsocket` and a `providerMap` keyed by effective document name (`packages/provider/src/HocuspocusProviderWebsocket.ts:103-106,222-257`). It is not limited to one socket per document when configured this way.
- It serializes message processing per document connection (`packages/server/src/Connection.ts:57-60,244-302`), caps pending unauthenticated documents (`packages/server/src/ClientConnection.ts:598-609`), and can merge/batch pending Yjs updates (`packages/provider/src/HocuspocusProvider.ts:410-488`).

**Action**

Keep multiplexing, but add isolation: idempotent JOIN, maximum joined notes and JOIN rate, maximum frame/history bytes, bounded per-note outbound queues, and a fair scheduler that prioritizes the active note/awareness over background catchup. Compact update history into snapshots so reconnect cost is bounded. Monitor queue depth, buffered bytes, catchup duration, disconnect fanout, and dropped/rejected frames. If vaults become large, shard notes over a small bounded number of physical sockets (for example active/foreground plus hashed background lanes), not one socket per note.

### P2 — Reconnect loop has no exponential backoff, jitter, or health watchdog

**Severity:** Risk — reconnect storms and stuck sockets

**StoneIntelligence evidence**

- Retry delay is always 2 seconds (`multiplexedTransport.ts:99-110,194-204`).
- Both `onerror` and `onclose` can schedule retries; no timer handle or generation guard deduplicates pending retries (`:159-178,194-204`).
- `onerror` nulls `realSocket` without closing/detaching the old socket. A late event from that socket can mutate current state.
- There is no ping/no-message watchdog and no terminal close-code classification.

**Reference pattern**

- y-websocket uses capped exponential backoff (`y-websocket/src/y-websocket.js:198-220`), resets it only after a successful sync (`:569-579`), detaches stale handlers/clears remote awareness (`:167-196`), treats 4400–4499 as terminal (`:140-153,205-225`), and has a no-message watchdog (`:493-526`). It does **not** add jitter.
- Hocuspocus exposes factor, cap, attempts, timeout, cancellation, and jitter in its retry controller (`packages/provider/src/HocuspocusProviderWebsocket.ts:270-310`).
- The hobby mux gives a concise jitter/timer/generation example (`obsidian-collab/plugin/src/collab/MuxProvider.ts:44-48,108-179`).

**Action**

Create one reconnect state machine with a single cancellable timer, socket-generation guards, capped exponential backoff with full jitter, and terminal/transient classification. Reset attempts only after a meaningful successful state (at least socket authenticated and one required document caught up), not merely TCP open. Close and detach stale sockets. Add heartbeat/no-message detection.

### P2 — Simultaneous first seeding can duplicate content

**Severity:** Risk — data corruption race

After a genuinely empty catchup, every client independently executes “if Y.Text empty, insert my whole local file” (`plugin/src/main.ts:592-599`). Two devices initializing the same note can both pass the empty check and insert independent content. Yjs correctly preserves both inserts, which looks like duplicated text.

CoSync acknowledges the same race but uses only a 600 ms grace period (`obsidian-cosync/src/sync.ts:330-345`), which is a cautionary anti-pattern rather than a robust solution.

**Action**

Make bootstrap a server-coordinated compare-and-set: one initialization update wins only if the note has no committed state/high-water sequence, and losers resync before deciding how to reconcile their local file. Alternatively make note creation carry the initial CRDT update atomically. Test two simultaneous creators with same and different local content.

### P2 — CodeMirror initial-state and UndoManager lifecycle are fragile

**Severity:** Bug/risk — editor consistency and undo behavior

**StoneIntelligence evidence**

- `yCollab(...)` is created without options on every bind (`plugin/src/main.ts:637-643`). Upstream therefore constructs a new `Y.UndoManager` by default (`y-codemirror.next/src/index.js:12-46`). Rebinding loses Yjs undo history and leaves unclear ownership/destruction; it may also conflict with Obsidian's native undo stack.
- The official binding observes changes after attachment; it does not by itself guarantee that an already-populated Y.Text replaces a reused editor's existing content. StoneIntelligence does not explicitly reconcile the editor state before binding.
- Reconfiguring with the same upstream plugin identity is itself known to be problematic in reused Obsidian views; CoSync forces old-plugin destruction with a fresh compartment (`obsidian-cosync/src/sync.ts:588-618`), while Concord uses a dynamic-context fork (`obsidian-concord/plugin/src/yedit/README.md:5-31`).

**Action**

Choose and test an explicit undo policy. Either pass `{ undoManager: false }` and rely on Obsidian, or create one session-owned `Y.UndoManager`, reuse it across view rebinds, and destroy it with the session. Before attaching, reconcile the current editor snapshot to the already-synced Y.Text without echoing it back. Ensure the old plugin instance is actually destroyed/rebound; do not assume same-compartment reconfigure changes its constructor-captured Y.Text.

### P2 — Unknown frame types are persisted as document updates

**Severity:** Bug — protocol validation and history poisoning

`SyncWebSocketHandler` routes every unrecognized message type through `relay.onUpdate` (`SyncWebSocketHandler.java:67-80`). `SyncClient` similarly applies every non-awareness/non-catchup message as a Yjs update (`plugin/src/sync/SyncClient.ts:102-110`). A server-only control type sent client→server, a future protocol version, or arbitrary bytes can therefore enter persistent history. Every future join then replays the bad payload.

**Reference pattern**

y-websocket dispatches only registered message handlers and reports unsupported types (`y-websocket/src/y-websocket.js:130-135`). Hocuspocus parses typed messages and closes a per-document connection on decode/handler failure (`packages/server/src/Connection.ts:252-300`).

**Action**

Use exhaustive switches and direction-specific allowlists: only `TYPE_DOC_UPDATE` may reach persistence. Reject unknown, server-only, malformed, oversized, or invalid-direction frames with a protocol close/error. For server-readable notes, validate that payloads are decodable Yjs updates before committing; for future ciphertext notes, validate only the authenticated/versioned envelope and size without breaking the opaque-server decision.

### P2 — One failed recipient can abort room broadcast

**Severity:** Risk — partial delivery/availability

`SyncRoomRegistry.broadcastExcept` invokes each action without isolating exceptions (`SyncRoomRegistry.java:31-37`). `WebSocketSyncSession.send` throws on I/O failure (`SyncWebSocketHandler.java:189-204`). A stale/broken recipient can therefore abort the loop, preventing later healthy recipients from receiving the update; the persisted update may then appear only after their next full catchup.

Hocuspocus isolates send failure by closing the affected connection (`packages/server/src/Connection.ts:164-181`).

**Action**

Catch send failure per recipient, evict/close that recipient, record a metric, and continue broadcasting. Add a test with one throwing session between two healthy sessions.

### P2 — Shared transport and Yjs resources outlive their last consumer

**Severity:** Risk — lifecycle/resource leak

- `MultiplexedTransport.leave` removes the virtual socket but never closes the physical socket when the map becomes empty (`multiplexedTransport.ts:229-239`).
- Plugin unload stops sessions but does not destroy/null the transport (`plugin/src/main.ts:312-316`).
- `SyncClient.disconnect` does not remove its Y.Doc/awareness listeners or destroy awareness/doc resources (`SyncClient.ts:121-125`).

The hobby mux closes its shared connection when the last provider leaves and destroys listeners/awareness (`obsidian-collab/plugin/src/collab/MuxProvider.ts:90-105,337-344`). CoSync and Concord likewise explicitly destroy provider, persistence, awareness/local state, and Y.Doc (`obsidian-cosync/src/sync.ts:740-764`; `obsidian-concord/plugin/src/text-session.ts:680-718`).

**Action**

Give `MultiplexedTransport` an idempotent `destroy()` and close-on-empty policy. Give `SyncClient` an idempotent destroy path that unregisters named handlers, removes local awareness, destroys awareness, and destroys its owned Y.Doc. Invoke both on unload, logout, terminal auth failure, vault switch, and transport-setting changes.

### P3 — Connection status is too optimistic

**Severity:** Nice-to-have with correctness implications

The transport emits virtual `open` immediately after sending JOIN (`multiplexedTransport.ts:206-227`), and `SyncClient` maps this to `connected` (`SyncClient.ts:91-97`). The server silently ignores missing/denied notes (`SyncWebSocketHandler.java:84-104`). The UI therefore cannot distinguish TCP open, JOIN sent, JOIN accepted, caught up, locally dirty, and fully acknowledged.

Hocuspocus has an explicit per-document authentication response before establishing the document connection (`packages/server/src/ClientConnection.ts:531-543`), and its provider distinguishes handshake `synced` from persisted/unsynced changes (`packages/provider/src/HocuspocusProvider.ts:490-505`).

**Action**

Add per-note `JOIN_OK`/`JOIN_DENIED` (with read-only capability) and a state model such as `connecting → joined → catching-up → synced`, orthogonal to `offline-dirty`/`unacknowledged`. Do not label JOIN transmission as connection success.

## Requested topic conclusions

### Reconnect/backoff

StoneIntelligence currently has fixed 2-second retry, no jitter, no cap progression, no retry cancellation handle, no health watchdog, and no terminal-auth/close classification. y-websocket has capped exponential backoff but no jitter; Hocuspocus has configurable exponential retry with jitter and cancellation. Adopt a single jittered state machine, but pair it with state-vector reconciliation—the retry timing alone does not prevent lost edits.

### Auth refresh and WebSocket lifecycle

The recent 400/401-only token invalidation fix is sound. The remaining over-broad behavior is downstream: all fresh-ticket failures are treated as indefinitely retryable, while terminal refresh rejection should pause sync; conversely logout/config changes are under-broad because they do not invalidate the existing authenticated channel. Propagate typed error semantics across the whole connection lifecycle.

### Awareness/presence

Current forwarding works for live changes but not lifecycle correctness. Add current-state replay for late joiners, explicit tombstones on leave, remote cleanup on disconnect, local republish on reconnect, and deterministic resource teardown.

### Catchup/sync completion

`TYPE_CATCHUP_COMPLETE` is better than a silence timer and is correctly named as database catchup completion. It is weaker than Yjs Sync Step 2: it does not prove two-way convergence or persistence of local updates. It also needs an ordering barrier against concurrent live updates, and timeout must never be converted into success. Prefer the standard Yjs sync handshake inside each multiplexed channel.

### Multiplexing

The choice is viable and Hocuspocus itself supports multiple document providers over one shared WebSocket. The risks are implementation concerns, not a reason to abandon the ADR: TCP head-of-line blocking, global failure fanout, shared backpressure, unbounded joins/history, and unfairness between active-note edits and background catchup. Mitigate with per-doc queues, fair priority scheduling, bounded buffers/caps, compaction, metrics, and optionally a small number of lanes.

## Lessons from the three hobby plugins

These are useful implementation clues, not stronger authorities than y-websocket/Hocuspocus:

- **CoSync:** good per-view binding registry and serialized bind path; explicit pre-bind content reconciliation; session-owned UndoManager and full cleanup (`src/main.ts:237-265`, `src/sync.ts:562-618,740-764`). Caution: one provider/socket per visited room remains open, and its 600 ms fresh-room grace is still a timing guess (`src/sync.ts:1-3,336-340`).
- **obsidian-collab:** its custom mux preserves the essential Yjs behavior StoneIntelligence lacks—Sync Step 1 and awareness republish per virtual document after physical reconnect, plus remote-awareness cleanup and jittered reconnect (`plugin/src/collab/MuxProvider.ts:44-48,172-187,316-389`). Caution: disconnected sends are still dropped (`:155-162`), but state-vector sync repairs document updates; that only works because the Y.Doc remains authoritative.
- **Concord:** uses Hocuspocus's shared WebSocket with explicit per-document attach (`plugin/src/text-session.ts:130-139`), never treats timeout as sync (`plugin/src/util.ts:97-150`), has a persistent auth-failed gate (`plugin/src/main.ts:546-575`), and cleans presence/session resources (`plugin/src/text-session.ts:680-718`). Caution: it vendors a fork of the CM6 binding to handle Obsidian's dynamic view context (`plugin/src/yedit/README.md:1-45`), which solves real behavior but creates a maintenance burden StoneIntelligence should avoid unless tests prove upstream cannot be managed through lifecycle controls.

## Recommended implementation order

1. Introduce per-note Yjs Sync Step 1/2 over the existing multiplex envelope; add the offline-edit regression test.
2. Enforce WRITE separately from READ and add explicit JOIN responses/capabilities.
3. Make initial reconciliation fail closed on timeout and serialize catchup/live updates (or let the standard handshake replace that path).
4. Implement awareness replay/removal/resend and complete destroy semantics.
5. Fix editor binding with per-view ownership, generation guards, explicit initial reconciliation, and an explicit undo policy.
6. Replace reconnect timing with a typed, cancellable, jittered state machine and wire logout/settings/terminal auth into teardown.
7. Add join/history/buffer limits, compaction, fair scheduling, and fanout error isolation before scaling vault size or server concurrency.

## Minimum regression suite

- Edit offline, reconnect, and verify a second device receives the edit.
- Drop the shared socket while several docs have local changes; verify all converge.
- READ-only user attempts a crafted doc-update frame; verify no persistence or broadcast.
- Concurrent JOIN snapshot and live update; verify update order and no seed duplication.
- Catchup timeout; verify no local seeding and a visible paused/error state.
- Two clients initialize the same empty note concurrently.
- Late join sees existing cursors; disconnect/leave removes them immediately.
- Rapid A→B→A file switching and two panes; verify each editor is bound only to its own Y.Text.
- Broken broadcast recipient does not prevent healthy recipients from receiving.
- Logout, terminal refresh rejection, vault switch, and plugin unload close the physical socket and destroy all sessions.