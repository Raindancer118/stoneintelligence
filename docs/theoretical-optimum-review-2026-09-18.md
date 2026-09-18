# Part 1: Verification

## Scope and method

Review baseline: `4c1d2964c389f58f88e9cbeb8dffd2a1d4def1b1`. Current `main`: `092db7777733b9fc21f21092707b63124b03443b`. I read all nine commit diffs in order, then re-read the resulting code in `plugin/src/` and `platform-api/src/main/`, including the call sites and tests. I also checked `Plan.md` and ADR 0002 and treated these as fixed constraints:

- The Java relay stores and forwards opaque Yjs bytes; it does not interpret document content.
- One physical WebSocket multiplexes many notes.

Verification run on current `main`:

- `plugin`: `npm test -- --run` — **100/100 tests passed**.
- `plugin`: `npm run build` — **typecheck and production bundle passed**.
- `platform-api`: `./mvnw -pl platform-api test` — **115/115 tests passed**.
- The repository remained clean (`git status --short` empty).

Passing current tests means the checked paths work; it does not establish red-before/green-after. For that, each added test was inspected against the parent implementation and mentally reverted as requested.

## Executive verdict

| # | Commit | Claim verdict | Test verdict | Important residual/new issue |
|---:|---|---|---|---|
| 1 | `89b2c10` WRITE authorization | **Substantially correct** | Strong, red-before/green-after | Writable capability is stale after note deletion/repeated JOIN/ACL change; unknown frame types still take the write path |
| 2 | `a7e32eb` offline repair | **Correct only for a surviving in-memory `Y.Doc`; broader claim is partial** | Non-vacuous but narrow | No restart durability; every catchup stores another full-state blob |
| 3 | `56343ba` join/update race | **Correct in the current single-JVM relay** | Non-vacuous; not fully deterministic because of `Thread.sleep(200)` | Network/DB I/O occurs under a per-note monitor; awareness is outside the barrier |
| 4 | `9c9f968` timeout safety | **Safety fixed, liveness regressed** | **No test added** | A connected session that misses `CATCHUP_COMPLETE` waits in 30-second cycles forever without forcing a rejoin |
| 5 | `5617288` awareness disconnect/republication | **Correct for this client’s physical socket loss** | Two strong tests; one named test is vacuous | Does not remove a departed peer from already-connected clients on virtual LEAVE/socket close |
| 6 | `9293095` awareness replay | **Replay works; lifecycle remains partial** | Strong red-before/green-after coverage | Leave/update race, empty-map retention, no active tombstone, unbounded opaque payload retention |
| 7 | `488f083` auth teardown | **Established-socket path fixed; terminality is incomplete** | Current tests miss the critical in-flight case | `destroy()` can create and retain a socket after an awaited ticket request resolves |
| 8 | `8fdc2bf` CM6 generation gate | **Partial** | **No test added** | Does not verify view/file identity or liveness; old panes remain bound; one global binding slot cannot model multiple panes |
| 9 | `092db77` recipient isolation | **Basic claim correct** | Strong red-before/green-after | Catching every `RuntimeException` hides programming faults and leaves a failed physical session half-joined in other registries |

The nine commits improve the system materially, but they do **not** close all P0/P1 behavior described by their aggregate claims. The highest-confidence new defect is the destroy-during-ticket-fetch race. The highest-risk still-open correctness areas are durable offline recovery, CM6 per-view ownership, and catchup liveness.

## 1. `89b2c10` — enforce WRITE permission

### What is correct

`SyncWebSocketHandler` now maintains separate joined/readable and writable note sets (`SyncWebSocketHandler.java:43-50`). JOIN still requires path-scoped READ, while WRITE is resolved separately and only notes in `writableNotesBySession` reach `relay.onUpdate` (`:103-120`, `:138-149`). This closes the concrete source-to-sink bypass: a READ-only actor can receive catchup/awareness but cannot persist or broadcast a document update.

The three tests are meaningful:

- The persistence and broadcast negative tests fail against the parent because the old default branch accepted every frame from any joined session.
- The positive writer test prevents an accidentally deny-all implementation.

### Residual and newly exposed problems

1. **Deleted-note capability survives.** `WebSocketSyncSession.notifyNoteDeleted` removes the note only from `joinedNotesBySession` (`:210-215`), not `writableNotesBySession`. The new default write guard consults only the latter. A client that ignores `TYPE_NOTE_DELETED` may therefore keep sending frames for the deleted note. Whether the append ultimately fails depends on database/tombstone constraints, but the authorization state is demonstrably wrong. The deletion path needs to remove both capabilities.
2. **A repeated JOIN cannot downgrade the cached capability.** If a session was writable and a later JOIN resolves only READ, the code simply refrains from adding WRITE; it does not remove an earlier entry. More generally, ACL changes are not re-evaluated until LEAVE/reconnect.
3. **Unknown and server-only frame types still persist as document updates.** The `default` branch at `:85-89` treats every type other than JOIN/LEAVE/AWARENESS as a document update if the cached WRITE bit is present. `TYPE_NOTE_DELETED`, `TYPE_CATCHUP_COMPLETE`, future control frames, and arbitrary type bytes are therefore accepted client-to-server. This was a separate first-pass P2 and remains open.
4. **The client receives no explicit JOIN result or effective capability.** A read-only client still edits its local Y.Doc and sends updates that disappear silently. The security boundary is fixed, but the UX/data-integrity contract is not.

**Verdict:** the claimed authorization bypass is fixed, with a concrete deletion/downgrade cleanup regression in the new capability cache.

## 2. `a7e32eb` — resend full Yjs state after catchup

### What is correct

On every `TYPE_CATCHUP_COMPLETE`, `SyncClient` now sends `Y.encodeStateAsUpdate(this.doc)` before invoking the catchup callback (`SyncClient.ts:102-108`, `:123-138`). For a local edit made while the same `SyncClient` and `Y.Doc` remain alive but the transport is down, this repairs the lost send. Applying the full update is semantically idempotent at Yjs replicas.

The test is non-vacuous: reverting `resendFullStateAsRepair()` leaves the second fake socket with no document update and the reconstructed receiver remains empty.

### Limits and regressions

1. **It is not durable offline recovery.** On an Obsidian/plugin restart, the old Y.Doc is gone. A new empty Y.Doc is created (`main.ts:575-580`); after server catchup, `mergeInitialContent` treats non-empty server state as authoritative and overwrites a differing Markdown file (`:649-660`). A saved offline edit in the Markdown file is therefore not converted back into the same Yjs history and can still be lost. The test keeps the same Y.Doc and does not cover restart.
2. **The commit message’s restart example is unsupported by the implementation.** There is no local Yjs persistence provider or durable content outbox in this path.
3. **Every successful catchup persists a full-document update even when nothing was missing.** Yjs receivers deduplicate structs, but the opaque relay persists the redundant blob. Reconnect cost and history size become proportional to full document size times reconnect count.
4. **There is no server ACK for the repair update.** `CATCHUP_COMPLETE` only proves the preceding server-to-client history enumeration ended; it does not prove the client-to-server repair was committed.

**Verdict:** valid tactical repair for transient disconnects within one process; not a complete fix for “offline edits” as a product guarantee.

## 3. `56343ba` — per-note atomic join/catchup/update

### What is correct

`onJoin` and `onUpdate` synchronize on the same per-`NoteId` monitor (`SyncRelayService.java:18-29`, `:48-84`). In the current single relay instance, an update is now either:

- committed before the join reads history and therefore delivered in history, or
- committed after the join sends completion and therefore delivered live.

It is no longer both. The test’s blocked `listSince` reproduces the original interleaving, and reverting the lock normally produces two copies. The test is not perfectly deterministic despite its comment: it relies on `Thread.sleep(200)` to give the update thread time to finish in the parent implementation. A severely delayed thread could make the reverted test pass. A latch/instrumented append would make the red-before proof deterministic.

### Composition with later awareness replay

There is **no lock-order deadlock** between the new note monitor and awareness replay in the current code:

- `onJoin` holds the note monitor and sends through the per-WebSocket `synchronized(session)` block.
- `onAwarenessUpdate` does not acquire the note monitor.
- No send callback holds the WebSocket monitor and then tries to acquire the note monitor.

However, the composition is not theoretically clean:

1. `onJoin` now holds the note monitor across a database query and every network send, including every later-added retained awareness frame. A slow joiner blocks all updates for that note. A full-history join can therefore pause active editing for the complete catchup duration.
2. Awareness updates are outside the monitor. A concurrent awareness broadcast can interleave with retained replay and `CATCHUP_COMPLETE`. Yjs awareness clocks usually make duplicate/older state harmless, but the server does not provide an ordered presence boundary.
3. If a direct history/awareness/completion send throws, `onJoin` aborts after the session was put into the room. Unlike `broadcastExcept`, the join path has no cleanup-on-failure.
4. `noteLocks` never removes entries, so it grows once per ever-seen note.

**Verdict:** correct minimal single-instance fix; safe from deadlock in the inspected composition, but expensive and not the optimum sequencing design.

## 4. `9c9f968` — timeout is no longer success

### What is correct

`awaitConnected` and `awaitCatchupComplete` now return `false` on timeout, and `mergeInitialContent` performs no seed/adopt decision unless both returned true (`main.ts:501-547`, `:631-662`). That closes the corruption path where “unknown” was interpreted as “confirmed empty.”

### Missing test and liveness regression

No automated test was added, so there is no repository proof of red-before/green-after for this commit.

The loop is **not a CPU busy-loop** in production: every failed catchup attempt waits 30 seconds. It can, however, hang forever:

1. The socket opens and `client.status` remains `connected`.
2. The one `CATCHUP_COMPLETE` frame is lost, rejected, arrives before the callback is installed, or comes from an old server that never emits it.
3. `awaitCatchupComplete` returns `false` after 30 seconds.
4. The loop immediately sees `connected`, installs another catchup callback, and waits another 30 seconds—but it never sends a new JOIN and never forces a reconnect.

Nothing can produce the awaited event, so initial merge never completes. Disconnect while waiting also consumes the remainder of the 30-second timer instead of aborting promptly. Additionally, session identity is checked only at the top of the loop; there is no final identity/cancellation check after catchup and before the file read/write.

**Verdict:** the unsafe seed is fixed; the retry strategy needs an active rejoin/reconnect or cancellable state machine.

## 5. `5617288` — clear/republish awareness on reconnect

### What is correct

On ordinary socket close and explicit disconnect, the client removes every remote awareness ID while preserving its local ID (`SyncClient.ts:113-120`, `:152-171`). After catchup, it republishes the local state (`:135-149`). This matches the relevant y-websocket provider behavior.

The remote-clear and local-republish tests both fail if the fix is reverted. The test named `should_notClearRemoteAwareness_when_theSocketClosesBecauseTheNoteWasDeleted` does not create a remote state or assert preservation, so it is vacuous with respect to its name; it only proves the early-return path calls the deletion callback without throwing.

### Remaining gap

This clears ghosts on **this client when its whole physical socket closes**. It does not tell still-connected peers that one remote virtual note session left while their shared socket remains healthy. Those peers depend on the awareness protocol’s 30-second expiry. Thus the commit’s specific client-side claim is correct, but the broader “stale remote awareness” problem is only partially solved.

## 6. `9293095` — retained awareness replay

### What is correct

The relay keeps the latest opaque payload per `(note, session)`, replays other sessions’ payloads to a joining session, removes the sender on LEAVE, and removes the note cache on deletion (`SyncRelayService.java:30-41`, `:58-73`, `:92-112`). The server still does not parse awareness bytes. The late-join, latest-only, self-exclusion, and leave tests are non-vacuous and fail against the parent.

### Residual/new issues

1. **No active removal for already-connected peers.** This is acknowledged in the commit. Server opacity does not actually make it unsolvable; a session-scoped envelope/control message can solve it without parsing Yjs bytes (Part 2).
2. **Leave/update race.** `onAwarenessUpdate` and `onLeave` do not share a sequencing boundary. A late in-flight awareness frame can be inserted after `onLeave` removed the cache entry, resurrecting stale retained presence for future joiners.
3. **Outer maps never shrink on ordinary leave.** The per-note inner map may become empty, but its `latestAwarenessBySession` entry remains. `noteLocks` has the same lifetime leak.
4. **Payload size/rate are unbounded.** Any READ-capable joined client can make the relay retain an arbitrarily large awareness blob per note/session and broadcast at arbitrary frequency.
5. **“Latest payload” is only a complete state under the current honest-client convention.** An awareness update can encode any set of client IDs. If a session sends disjoint incremental payloads, retaining only the last blob is not necessarily a full snapshot for that session. The current plugin normally emits its one local client ID, but the wire contract does not enforce that.
6. **Join failure leaks membership.** Awareness replay was added inside the note monitor after `registry.join`; a send exception aborts the method without removing the just-added session.

**Verdict:** late-join replay works for the current plugin; full presence lifecycle and abuse resistance remain open.

## 7. `488f083` — destroy shared socket on auth loss

### What is correct

For an already-created socket, logout or a terminal refresh rejection now stops note sessions, closes the transport, clears its virtual sockets/queue, and prevents reconnect callbacks from creating another socket (`main.ts:161-198`, `:220-223`; `multiplexedTransport.ts:206-232`). The three transport tests are non-vacuous for these established-state paths.

### New high-confidence race: destroy during ticket acquisition

`ensureRealSocketConnecting` checks `destroyed` only before setting `connecting`, then awaits `getUrl()` (`multiplexedTransport.ts:148-155`). If `destroy()` runs while that promise is pending:

1. `destroyed` becomes true, virtual sockets are cleared, and there is no `realSocket` yet to close.
2. `getUrl()` resolves.
3. The method unconditionally calls `createRealSocket(url)`, installs handlers, and stores it in `realSocket` (`:155-191`).

The supposedly terminal destroy has now created a physical socket after logout. Its eventual callbacks will not reconnect because `destroyed` is true, but the new socket itself is never closed by the completed `destroy()` call. None of the tests holds `getUrl` pending across `destroy()`.

Further gaps:

- Logout and terminal-refresh handling await `saveData` before teardown. If persistence fails, `stopAllSyncDueToAuthLoss()` is skipped.
- `onunload()` stops virtual sessions but does not call `transport.destroy()` (`main.ts:343-347`). Because `leave()` never closes an idle physical socket, plugin unload can leave it alive.
- Old-socket callbacks are not guarded by socket identity/generation. Outside terminal destroy, a late `onclose` from an obsolete socket can null out a newer socket.
- Reconnect sleep promises are not cancellable; they merely check `destroyed` after resolving.

**Verdict:** useful fix, but not terminal under the exact in-flight race highlighted in the task.

## 8. `8fdc2bf` — CM6 binding generation counter

### What is correct

If overlapping `updateLiveEditorBinding` calls are all represented by newer invocations, only the newest generation may dispatch after `startSync` resolves (`main.ts:670-710`). That fixes the specific A-starts, B-finishes, A-finishes-last interleaving.

No test was added. The fix therefore has no executable regression proof.

### Scenarios the generation counter does not cover

1. **Captured view changed meaning without a newer callback.** The method captures `view` before the await and never verifies afterward that it is alive and still displays `file`. A generation counter only detects another method invocation; it cannot detect view reuse/detachment or an event whose `file` argument was already out of phase with `activeEditorView()`.
2. **Multiple panes.** There is one `liveBoundPath` and no `EditorView -> binding` registry. Switching to a different pane sets the old session’s flag to false but never dispatches `reconfigure([])` to the old view. That old pane can remain yCollab-bound while the session now also enables the coarse full-text observer path.
3. **Wrong unbind target.** For `file === null`, only the currently active view is cleared; a previously bound inactive view remains configured.
4. **Stop/delete/logout does not explicitly unconfigure the view.** `stopSync` disconnects the client and removes the observer/session, but does not remove the CM extension from the view first.
5. **A new default `Y.UndoManager` is still created on every bind.** Ownership and destruction remain implicit.

**Verdict:** partial race guard, not a correct editor-binding lifecycle.

## 9. `092db77` — isolate failed broadcast recipients

### What is correct

`broadcastExcept` now continues after one recipient throws and evicts the failing recipient from that note’s room (`SyncRoomRegistry.java:31-50`). Both tests are non-vacuous: reverting the change makes the first broadcast throw before the later recipient and makes the second retry the broken recipient.

### New/residual issues

1. **Catch is too broad.** Any `RuntimeException` from the action—including a programming error—is silently interpreted as a dead recipient. Catch `SyncSessionSendException` (or a narrow transport exception), log it, and let invariant/programming failures surface.
2. **Cleanup is note-local, but the failure is physical-session-wide.** A send failure on the shared WebSocket removes the session only from this one room. It remains in every other room, `joinedNotesBySession`, `writableNotesBySession`, and retained-awareness maps.
3. **Half-joined sender state.** The handler still considers the failed session joined/writable, so it may continue to send and persist updates even though it no longer receives broadcasts for the note.
4. **No failure metrics/logging.** Healthy-recipient delivery is preserved, but operationally the eviction is invisible.

**Verdict:** fixes loop abortion, but cleanup should be centralized at the physical-session boundary.

## Cross-commit findings

These are not attributable to only one commit but matter to the claimed outcome:

- **The current protocol provides at-least-once Yjs application, not exactly-once delivery.** Duplicate application is safe at the CRDT layer, but duplicate opaque blobs remain in storage and there is no per-recipient ACK/cursor.
- **Catchup completion is still one-way.** It proves server enumeration ended, not that the server has durably accepted all local edits.
- **No durable local Yjs state/outbox exists.** Process restart remains the decisive offline-data-loss boundary.
- **Physical-socket and per-note lifecycles are conflated.** A send failure is physical; JOIN/catchup/presence state is per note; current cleanup updates only whichever map the triggering path happens to know about.
- **Current status is too coarse.** `connected` means the virtual JOIN was sent and `dispatchOpen` ran, not JOIN accepted, caught up, local outbox acknowledged, or presence published.

# Part 2: Theoretical Optimum

## Design target under the two fixed constraints

The optimum is not to replace Yjs with OT or to open one socket per document. It is:

> A single authenticated physical WebSocket carrying independent, sequenced per-note streams; the server treats Yjs document and awareness payloads as opaque bytes, but understands transport metadata, durable sequence numbers, capabilities, leases, acknowledgements, bounds, and lifecycle.

Figma’s production design is useful here as a transport lesson, not as a proposed CRDT replacement: its server defines event order, reconnect downloads current state, and offline edits are reapplied over it. Automerge’s sync protocol contributes the requirement that per-peer/per-document sync state be restarted safely after disconnection and that each peer’s messages remain ordered. Yjs remains the document algorithm.

## 1. Catchup plus live updates: an effectively-once ordered stream

### Important terminology

Literal exactly-once network delivery cannot be guaranteed across disconnects where an ACK can be lost. The achievable and useful property is **effectively-once application**:

- retry until acknowledged (at-least-once transport),
- durable unique mutation IDs and server sequence numbers,
- idempotent insert/deduplication,
- contiguous ordered application at the client.

Yjs already makes duplicate update application harmless, but transport-level deduplication is still needed to avoid duplicated storage, bandwidth, and ambiguous progress.

### Recommended envelope

Every frame should carry transport metadata separate from the opaque payload:

```text
protocolVersion
messageKind
noteId
joinEpoch            // new value for each virtual rejoin
serverSequence?      // assigned after durable append
clientMutationId?    // stable deviceId + monotonic counter/UUID
payloadLength
opaquePayload
```

The server may read all of this metadata without decoding `opaquePayload`, so ADR 0002 remains intact.

### Join algorithm

Use the existing per-note `server_sequence` in `UpdateRecord`; it is already present but currently discarded by `SyncRelayService`.

1. Client sends `JOIN(noteId, joinEpoch, lastAppliedServerSequence)`.
2. Server authorizes READ and resolves WRITE capability. It responds explicitly with `JOIN_ACCEPTED(joinEpoch, capability, highWaterSequence)` or `JOIN_DENIED`.
3. The high-water mark and subscription boundary are created atomically, but **no database query or network send occurs while holding the note sequencer**.
4. Server streams durable log records `(lastApplied, highWater]`, each with its real `serverSequence`.
5. Updates committed concurrently receive sequences `> highWater` and enter that subscriber’s bounded per-note mailbox. An equivalent design may send them immediately if the client buffers by sequence and join epoch.
6. Server enqueues `CATCHUP_END(joinEpoch, highWater)` in the same per-note outbound sequencer.
7. The mailbox drains in sequence order, then the subscriber transitions to LIVE.
8. Client ignores old join epochs, deduplicates already-applied sequences, buffers gaps, and requests replay from the first missing sequence.

This is the standard **snapshot/high-water plus log-tail** pattern. It is strictly better than holding the note lock across full history and socket sends: the critical section is short, the persisted log is the ordering authority, and a slow joiner only fills its own bounded mailbox.

### Client-to-server durability

For each local Yjs update:

1. Persist `{noteId, clientMutationId, update}` locally before considering it sendable.
2. Send/retry until `UPDATE_COMMITTED(clientMutationId, serverSequence)` arrives.
3. Server enforces a unique key such as `(note_id, device_id, client_mutation_id)`. A duplicate retry returns the original sequence instead of inserting another blob.
4. Remove the local outbox entry only after the commit ACK.

Persist the Y.Doc locally as well—`y-indexeddb` is the canonical Yjs browser pattern and explicitly supports offline editing across reloads. Load local Yjs persistence **before** network JOIN. This preserves CRDT identity across plugin restart and eliminates the current “Markdown differs, server always wins” data-loss boundary. The Markdown file remains a projection, consistent with ADR 0002.

A periodic full-state repair may remain as a belt-and-suspenders recovery frame, but it should be exceptional, deduplicated, and checkpointed—not appended after every catchup.

### Fairness and backpressure on one socket

One TCP stream necessarily retains packet-level head-of-line blocking. Application scheduling can still prevent a background note from monopolizing it:

- one bounded outbound queue per note;
- weighted round-robin/deficit round-robin across notes;
- highest priority for control/ACK, then active-note edits, then awareness, then background catchup;
- byte limits, not only message-count limits;
- pause/resume catchup chunks when `WebSocket.bufferedAmount` or server queue depth crosses thresholds;
- maximum joined notes, concurrent catchups, frame size, and JOIN rate;
- explicit overflow behavior: abort/restart that note’s catchup, never drop an arbitrary document update silently.

### Gap assessment

**Gap: large.** Current code has a useful server sequence in storage and a correct single-JVM lock, but it replays from zero, ignores sequences, holds I/O under a monitor, has no ACK/outbox/resume cursor, and has no durable local Y.Doc.

**Next action:** introduce versioned envelopes plus `JOIN_ACCEPTED(highWater)`/sequenced history/`UPDATE_COMMITTED`; persist Y.Doc and the mutation outbox locally. This is the highest-value architectural change.

**Sources:** named snapshot-plus-log-tail and idempotent-receiver patterns; Yjs `y-indexeddb/README.md`; Automerge `SYNC.md`; Figma, “How Figma’s multiplayer technology works”; the existing `JdbcSnapshotStore`/`UpdateRecord` sequence model.

## 2. Awareness/presence lifecycle without server decoding

### Recommended lease design

The server does not need to parse Yjs awareness to perform exact session cleanup.

1. Each successful per-note JOIN creates a server-issued `presenceLeaseId` tied to `(physicalSession, noteId, joinEpoch)`.
2. Every awareness frame carries that lease ID outside the opaque Yjs bytes.
3. Server retains at most one bounded opaque awareness snapshot per live lease, with `lastSeen` and a short TTL.
4. Late joiners receive `AWARENESS_SNAPSHOT(leaseId, opaquePayload)` for each unexpired lease.
5. Clients decode the awareness payload—as they already do—and maintain `leaseId -> set<awarenessClientId>`.
6. On clean leave, the client first emits the Yjs `state=null` update for its local ID, then LEAVE. The server forwards the opaque tombstone and ends the lease.
7. On abrupt socket loss, the server broadcasts `PRESENCE_LEASE_ENDED(leaseId)`. Recipients remove all client IDs mapped to that lease locally. The server never needs to know those IDs.
8. Expiry and note deletion use the same lease-ended control frame. All lease operations are sequenced with JOIN/LEAVE for that note.

This preserves the opacity constraint and closes the currently acknowledged ghost-cursor gap. It also prevents one session from clearing another session’s presence because the server owns lease association.

Follow the Yjs awareness semantics: state `null` on clean disconnect, refresh approximately every 15 seconds, expire after 30 seconds, apply only increasing clocks. Add payload-size and rate limits and require READ membership for presence.

### Gap assessment

**Gap: medium.** Replay, reconnect republish, and local remote-state cleanup now exist. Missing pieces are active peer removal, lease ownership, race-free leave/update sequencing, TTL/limits, and exact cache cleanup.

**Next action:** add `presenceLeaseId` and `PRESENCE_LEASE_ENDED`; move retained presence into a bounded lease registry and add disconnect/LEAVE/rejoin race tests.

**Sources:** `yjs/y-protocols` `PROTOCOL.md` awareness semantics; `y-websocket/src/y-websocket.js` close/open handling; Hocuspocus `packages/server/src/Document.ts` and `Connection.ts`; Hocuspocus v4’s named `sessionAwareness` pattern.

## 3. Reconnect/backoff for the shared socket

### One explicit state machine

```text
IDLE
  -> FETCHING_TICKET
  -> CONNECTING_SOCKET
  -> OPEN
  -> JOINING_REQUIRED_NOTES
  -> HEALTHY
  -> BACKING_OFF (transient failure)
  -> AUTH_REQUIRED / STOPPED (terminal failure)
```

Required mechanics:

- A monotonically increasing transport generation. Every awaited continuation and every socket callback checks both generation and `socket === currentSocket` before mutating state.
- An `AbortController` for ticket acquisition, one cancellable reconnect timer, and idempotent `destroy()` that increments generation **before** cancellation/close.
- Recheck generation immediately after ticket acquisition and immediately after socket construction; close a just-created stale socket.
- Detach handlers from obsolete sockets before closing them.
- Capped exponential backoff with full jitter, e.g. `random(0, min(cap, base * 2^attempt))`; pause while offline and resume on the browser/OS online signal.
- Reset the attempt counter only after a meaningful stable state—authenticated socket plus at least required notes joined/caught up—not merely TCP open.
- Terminal close-code band for revoked/invalid auth and nonexistent/forbidden resources; transient band for overload/retry. Because browsers hide HTTP upgrade details, accept then close with an application code when a precise terminal reason is needed.
- Application-level ping/pong and a no-message watchdog; browser JavaScript cannot originate WebSocket control ping frames directly.
- Per-note join epochs so late frames from the previous physical connection cannot complete the new join.

Retry should normally continue indefinitely for an offline-first editor, but backoff must be capped and status visible. “Maximum retries” is appropriate for aggressive ticket calls; after exhaustion, remain idle/offline and resume on user action/network change rather than hammering.

### Gap assessment

**Gap: large.** Current reconnect is fixed at two seconds, has no jitter/watchdog/generation, cannot cancel pending ticket fetch, and can resurrect a socket after destroy.

**Next action:** replace boolean flags and fire-and-forget sleeps with a generation-guarded state machine; add deterministic fake-clock tests for destroy-during-ticket-fetch, old-socket late close, duplicate error+close, terminal close, jitter bounds, and watchdog reconnect.

**Sources:** `y-websocket/src/y-websocket.js` exponential backoff, terminal 44xx handling, stale awareness cleanup, and watchdog; Hocuspocus `HocuspocusProviderWebsocket.ts` cancellable retry/factor/cap/jitter; Hocuspocus v4 application Ping/Pong; AWS’s named full-jitter backoff formula.

## 4. Resource lifecycle and ownership

### Ownership model

| Owner | Owns | Destroy/keep rule |
|---|---|---|
| Plugin/vault context | one `MultiplexedTransport` | Destroy immediately on logout, terminal auth, unload, vault/server/OIDC change; close after an idle grace period when no notes/background tasks remain |
| Note session | `SyncClient`, `Y.Doc`, Awareness, local persistence, outbox, timers | Keep while a view is bound, local changes are unacked, or a background operation explicitly leases it; otherwise evict after durable local persistence and server catchup |
| Editor view binding | Compartment binding, stable per-note `Y.UndoManager`, listeners | Registry keyed by `EditorView`; unbind on view/file change before rebinding, on view disposal, stopSync, logout, and unload |
| Async initialization | waits/read/write operations | Own an abort signal/session generation; recheck after every await and before every side effect |

### Exact teardown order for a note

1. Mark the session closing/cancel its generation so no pending async continuation may write.
2. Unbind the specific EditorView and destroy/release its binding/UndoManager according to explicit ownership.
3. Best-effort publish local awareness `null`, then send LEAVE while the transport is still usable.
4. Stop local observers and provider listeners.
5. Flush/persist the local Y.Doc/outbox.
6. `awareness.destroy()` and `doc.destroy()` when no other owner shares them.
7. Remove session indexes and release the physical transport lease.

Yjs documents and Awareness both have explicit destruction APIs; current `stopSync` calls neither. `onunload` must destroy the transport even if zero virtual sockets remain. Settings changes that alter vault/server/auth identity must tear down and rebuild the whole connection boundary; swapping REST clients under an authenticated live socket is unsafe.

### Gap assessment

**Gap: large.** Current sessions create Y.Doc/Awareness instances but never destroy them; the physical socket stays open at zero notes and on unload; async initial merge is not cancellable; and editor binding ownership is global rather than per view.

**Next action:** add `SyncClient.destroy()`, a per-view binding registry, session abort/generation tokens, transport reference counting/idle close, and a single plugin teardown path reused by logout, unload, and connection-affecting settings changes.

**Sources:** Yjs `Y.Doc` API (`doc.destroy()` clears handlers and destroys attached bindings/providers); Yjs Awareness API (`awareness.destroy()`); Hocuspocus `HocuspocusProvider.ts`/`HocuspocusProviderWebsocket.ts` destroy paths; y-codemirror.next view-plugin destroy hooks.

## 5. Editor binding optimum

The binding unit must be `(EditorView, filePath, noteId, sessionGeneration)`, not a global active path.

- Maintain `Map<EditorView, BindingRecord>`.
- At event start and after every await, resolve the actual file displayed by that exact view and compare it with the record.
- Unbind the previous record from that view before installing another.
- Support every visible Markdown view independently.
- Keep a stable UndoManager per note/view policy instead of accepting y-codemirror.next’s default new manager on every `yCollab(...)` call.
- Treat dispatch on a destroyed view as cancellation, not as a general error.
- Test A→B→A, two simultaneous panes, active-pane changes without file change, view disposal during `startSync`, rename/delete, logout, and plugin unload.

**Gap: large.** The generation counter addresses one interleaving but not the ownership model.

**Next action:** replace `liveBoundPath` with a per-view registry and add an Obsidian-like fake view harness before further lifecycle changes.

**Sources:** CoSync’s serialized/per-view binding approach; Concord’s documented Obsidian EditorView reuse problem; y-codemirror.next `src/index.js` and view-plugin destroy methods.

## 6. Authorization and protocol integrity

The optimum JOIN response includes effective capabilities and a join epoch. Document updates require WRITE; awareness can require READ. Capability state must be removed on deletion/leave and invalidated on ACL/path changes, either through:

- an ACL version attached to the JOIN and checked cheaply on update, or
- server-driven `CAPABILITY_CHANGED`/forced rejoin events.

Use direction-specific exhaustive frame switches. Only `TYPE_DOC_UPDATE` may enter durable history. Validate protocol version, direction, payload length, note membership, join epoch, and ciphertext/plaintext envelope constraints before append. Unknown types should produce a protocol error/close, not persistence.

**Gap: medium.** The central WRITE bypass is fixed; capability freshness, explicit read-only UX, deletion cleanup, and exhaustive protocol validation remain.

**Next action:** add explicit JOIN ACK/capability and replace both client/server default branches with exhaustive allowlists.

## 7. Failure isolation, compaction, and observability

### Failure isolation

A physical send failure should transition the whole physical session to failed exactly once, remove it from every room/capability/presence lease, and close the socket. Catch only expected send exceptions. Per-note delivery should then recover from its durable server sequence on reconnect.

### Opaque history compaction

Because the server cannot merge Yjs updates, use client-produced opaque checkpoints:

- authorized client uploads a full-state checkpoint tied to high-water sequence `H`;
- server stores checkpoint plus subsequent log tail;
- new joiners receive the newest checkpoint then updates `> H`;
- retain older history for a safety window and do not delete it merely because an arbitrary writer supplied a checkpoint;
- checkpoint acceptance, retention, and rollback must be explicit policy, especially for encrypted notes.

This bounds catchup without server-side Yjs decoding. Durable mutation IDs prevent repeated full-state repair blobs from multiplying storage.

### Bootstrap

The simultaneous-empty-note seed race remains. Make initialization a server-side metadata CAS: `INITIALIZE_IF_EMPTY(clientMutationId, opaqueInitialUpdate)`. The server need not parse the update; it only atomically checks whether the note log has a committed first record. The loser receives the winner’s sequence and rejoins/reconciles.

### Metrics and tests

Track per-note and physical-socket metrics: queue bytes, oldest queued age, catchup duration/bytes, replay gaps, reconnect attempt/reason, terminal auth stops, dropped/oversized frames, ACK latency, active note sessions, retained presence leases, and cleanup counts.

Add a deterministic protocol model/fault suite that injects disconnects at every boundary: before/after DB commit, before/after broadcast, before/after ACK, during ticket fetch, during history replay, between catchup end and live drain, and during teardown. Assert convergence, no unacknowledged-edit loss, bounded queues, and eventual resource cleanup.

**Gap: large** for compaction/backpressure/model testing; **small-to-medium** for basic recipient isolation.

**Next action:** after sequenced ACK/resume is in place, implement checkpoint-plus-tail and fault-injection tests; do not optimize full-history replay before the ordering contract is explicit.

## Consolidated priority order

1. **Fix immediate regressions:** destroy-during-ticket-fetch; remove writable capability on note deletion; teardown transport on unload; narrow send exception handling.
2. **Make offline edits durable:** local Y.Doc persistence plus durable mutation outbox and commit ACK.
3. **Replace long-held note lock with sequence/high-water/mailbox catchup.**
4. **Move editor binding to per-EditorView ownership.**
5. **Add awareness leases and lease-ended cleanup.**
6. **Build the reconnect state machine with generation guards, cancellation, jitter, and watchdog.**
7. **Add protocol allowlists, bounds, fair scheduling, checkpoint-plus-tail compaction, and operational metrics.**

## Reference implementations and named patterns

- **Yjs wire semantics:** [`yjs/y-protocols` — `PROTOCOL.md`](https://github.com/yjs/y-protocols/blob/master/PROTOCOL.md), especially Sync Step 1/2 and awareness clocks/null/expiry.
- **Yjs reconnect/presence lifecycle:** [`yjs/y-websocket` — `src/y-websocket.js`](https://github.com/yjs/y-websocket/blob/master/src/y-websocket.js) and its README for exponential backoff, terminal close codes, watchdog, awareness clear/republication, and provider destruction.
- **Durable offline Yjs:** [`yjs/y-indexeddb` — `README.md`](https://github.com/yjs/y-indexeddb/blob/master/README.md).
- **Mature multiplex/retry/lifecycle:** [`ueberdosis/hocuspocus` — `packages/provider/src/HocuspocusProviderWebsocket.ts`, `HocuspocusProvider.ts`, `packages/server/src/Connection.ts`, `Document.ts`](https://github.com/ueberdosis/hocuspocus/tree/main/packages), including current session-awareness and application Ping/Pong work.
- **Independent CRDT sync reference:** [Automerge sync protocol — `SYNC.md`](https://github.com/automerge/automerge/blob/main/SYNC.md), particularly per-peer sync state, reconnect recovery, and ordered delivery per peer.
- **Production centralized collaboration tradeoffs:** Figma, [“How Figma’s multiplayer technology works”](https://www.figma.com/blog/how-figmas-multiplayer-technology-works/) and [“Making multiplayer more reliable”](https://www.figma.com/blog/making-multiplayer-more-reliable/). These inform ordering, WAL/checkpointing, ACKs, and offline reapplication—not a recommendation to abandon Yjs.
- **Retry policy:** AWS, [Retry behavior](https://docs.aws.amazon.com/sdkref/latest/guide/feature-retry-behavior.html), for capped exponential backoff with full jitter.
- **Idempotent receiver:** Martin Fowler/Gregor Hohpe, named **Idempotent Receiver** pattern; combine with durable mutation IDs and unique constraints.
- **Editor integration:** `yjs/y-codemirror.next` `src/index.js`, `src/y-sync.js`, `src/y-undomanager.js`, plus the first-pass CoSync and Concord view-lifecycle references.

