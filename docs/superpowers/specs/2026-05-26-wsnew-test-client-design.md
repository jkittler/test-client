# wsnew Test Client — Design

**Date:** 2026-05-26
**Status:** Approved (design)
**Author:** brainstormed with Claude

## Goal

A small, standalone Kotlin command-line client that connects to the HCP server's
new local-print WebSocket endpoint (`/wsnew`, introduced on branch
`HCP-16292-POC-secured`) and exercises the full client→server protocol so a
developer can manually verify the feature end-to-end.

The client lives in its own git repo at `/Users/kita/Work/test-client` and has no
build coupling to `main_project`.

## What it must prove

1. WebSocket upgrade on `/wsnew`.
2. HMAC-SHA256 handshake (`HelloClient` → `HelloServer`), plus the POC no-HMAC
   escape hatch.
3. Parsing `HelloServer` and honouring `connectionAccepted` / `rejectionReason`.
4. JWT-based user session (`InitiateUserSession` → `InitiateUserSessionResponse`,
   capture `sessionCookie`).
5. Sending a `RemoteDelivery` wrapping a mid-level `AddDocument` request.
6. Receiving and pretty-printing a `PrintLocalDocument` pushed by the server.
7. Graceful shutdown.

## Server-side facts (verified against main_project @ HCP-16292-POC-secured)

- Endpoint path: `/wsnew` (`NettyIO.WebSocketUpgradePathNew`).
- Default port: `2563` (`OperationPrefs.DefaultLocalPrintWebSocketPortValue`).
- **TLS is required.** The server binds the local-print WS listener with
  `withTLS = true` (`TcpServer.scala:104`), so the client must use `wss://`,
  NOT `ws://`. (This corrects the original draft of this spec, which assumed
  plaintext `ws://`.) The dev server presents a per-account self-signed cert
  (e.g. `CN=test.localhost.nip.io`, issued by "Intermediate CA for account N"),
  so the client trusts all certs in dev (`trustAllCerts`). No client cert /
  mTLS is required (verified with `openssl s_client`).
- Frames are binary, each carrying a serialized `R_Envelope`
  (`LocalPrintWebSocketServerNettyIO`).
- Envelope `protocolVersion = 1`, server's own `applicationName = "HCP Server"`
  (`RemoteMessageSerializer.scala:366-367`). The client may use its own
  `applicationName` string; only `protocolVersion` matters.
- HMAC recipe (`HelloHandler.computeHmac`, `HelloHandler.scala:408`):
  `hex( HmacSHA256( apiKey, "$uuid|$timestamp|$accountDomain" ) )`,
  timestamp is ISO-8601 / RFC3339 UTC, drift tolerance 5 minutes,
  candidate keys = account active API keys + bootstrap key,
  constant-time comparison.
- No-HMAC escape hatch: a `HelloClient` without `timestamp`/`hmac_signature` is
  accepted without verification (`HelloHandler.scala:188`).
- Server→client print is triggered by `DocumentOutputService` looking up the
  connected client in `PrimaryRemoteMessaging.noRegistrationClients` by the
  document's `storageDetails.primaryStorageUuid`, or by the POC REST endpoint
  `POST .../local-print-ws/print/{documentId}/{outputPortId}`
  (`LocalPrintWsRequest.scala`).
- `RemoteDelivery → AddDocument` path:
  `R_Envelope.remoteDelivery` →
  `R_RemoteDelivery.message` (`R_RemoteDeliveryMessage`) →
  `messageType.midLevelRequest = 100` (`R_MidLevelRequest`) →
  `requestType.addDocument = 602` (`R_AddDocumentRequest { providerId, R_Document }`).

## Build approach

**Standalone Gradle project** (Approach A). Self-contained; anyone with a JDK +
Kotlin toolchain can clone and run it. Protobuf classes are generated locally by
`protobuf-gradle-plugin` from a **minimal `.proto`** that redeclares only the
messages we use, with **identical field numbers and types** to the server. Proto
wire format is field-number based, so this is wire-compatible without copying the
~20-file transitive import closure of the real `envelope.proto`.

**Fallback if the minimal proto for `R_Document` proves too fiddly:** depend on
`main_project`'s already-compiled `domain` jar as a flat `files(...)` dependency
and use the real generated classes. Documented here so it is a known escape, not
a surprise.

### Dependencies

- Kotlin JVM
- OkHttp (WebSocket client)
- `com.google.protobuf:protobuf-java` + `protobuf-gradle-plugin`
- Typesafe Config (HOCON)
- SLF4J + Logback

## Module layout

```
test-client/
├── build.gradle.kts
├── settings.gradle.kts
├── application.local.conf          # user-owned credentials, gitignored
├── src/main/
│   ├── kotlin/com/ysoft/wsnewtest/
│   │   ├── Main.kt                 # entry point, wiring, exit codes
│   │   ├── Config.kt               # HOCON -> ClientConfig, validation
│   │   ├── Hmac.kt                 # HmacSHA256 hex (pure function)
│   │   ├── Envelopes.kt            # build outgoing + parse incoming R_Envelope
│   │   ├── WsClient.kt             # OkHttp WebSocketListener, inbound queue
│   │   └── Flow.kt                 # protocol state machine
│   ├── proto/
│   │   └── envelope_min.proto      # minimal wire-compatible subset
│   └── resources/
│       ├── application.conf        # committed defaults
│       └── logback.xml
└── docs/superpowers/specs/2026-05-26-wsnew-test-client-design.md
```

### Component responsibilities

- **Config** — load HOCON (defaults + local override), validate required fields.
- **Hmac** — `(uuid, timestamp, domain, apiKey) -> hex` and nothing else.
- **Envelopes** — builders for each outgoing message; `parse(bytes)` returning a
  sealed type for inbound dispatch. No I/O.
- **WsClient** — open the socket, send binary frames, expose inbound frames as a
  queue/channel. No protocol logic.
- **Flow** — orchestrates: hello → (optional) session → (optional) remote
  delivery → receive loop. Owns exit codes.

## Protocol subset (`envelope_min.proto`)

`proto2`. Messages and field numbers exactly as on the server:

- `R_OutputPort { id=1, name=2 }` (minimal — only fields we read off a print cmd)
- `R_HelloClient { uuid=1, timestamp=3, hmac_signature=4, account_domain=5 }`
- `R_HelloServer { uuid=1, connectionAccepted=2, rejectionReason=6 }`
- `R_InitiateUserSession { requestId=1, userToken=2 }`
- `R_InitiateUserSessionResponse { requestId=1, oneof{ sessionCookie=2, errorMessage=3 } }`
- `R_LocalJobStored { document_id=1, document_name=2, input_port_id=3 }`
- `R_PrintLocalDocument { document_id=1, output_port_id=2, output_port=3 }`
- `R_Document { uuid=1, name=2 }` — **minimal stub; see caveat**
- `R_AddDocumentRequest { providerId=1 (required), document=2 (required) }`
- `R_MidLevelRequest { oneof{ addDocument=602 } }`
- `R_RemoteDeliveryMessage { oneof{ midLevelRequest=100 } }`
- `R_RemoteDelivery { message=1 (req), targetHost=2 (req), targetActorPath=3 (req), isResponse=6 (req), userSessionCookie=9 }`
- `R_Envelope { protocolVersion=1 (req), applicationName=2 (req), oneof contentType{ remoteDelivery=4, initiateUserSession=9, initiateUserSessionResponse=11, helloClient=12, helloServer=13, localJobStored=14, printLocalDocument=15 } }`

**Caveat (known cost):** the real `R_Document` has more `required` fields than the
stub. The first `AddDocument` send will likely be rejected at parse time on the
server. Plan: add fields to `R_Document` incrementally, driven by server log
parse errors (DEBUG hex dumps help), until accepted. If this becomes painful,
switch to the compiled-jar fallback above.

## Data flow

1. Load config (defaults + `application.local.conf`).
2. Open `wss://${host}:${port}${path}` (default `wss://localhost:2563/wsnew`),
   trusting all certs in dev.
3. Build & send `HelloClient`:
   - `useHmac=true` (default): set `timestamp = Instant.now()` (ISO-8601 UTC) and
     `hmac_signature`.
   - `useHmac=false`: omit both → POC escape hatch.
4. Await `HelloServer`. On `connectionAccepted=false`: log `rejectionReason`,
   exit 3.
5. If `jwt` configured: send `InitiateUserSession`, await response, store
   `sessionCookie` (log `errorMessage` and continue if absent).
6. If `sendRemoteDelivery=true`: send `RemoteDelivery { AddDocument }` carrying
   `userSessionCookie`.
7. Receive loop: log every inbound envelope; pretty-print `PrintLocalDocument`.
8. Stay alive until SIGINT; close socket with status 1000.

No automatic reconnect (deterministic test behaviour).

## Error handling

| Failure | Behavior |
|---|---|
| Missing required config field | Log field name, exit 1. |
| WS connect / upgrade fails | Log cause, exit 2. |
| `connectionAccepted=false` | Log `rejectionReason`, exit 3. |
| Hello timeout (10s) | Log, exit 4. |
| `InitiateUserSession` → `errorMessage` | Log, continue without cookie. |
| `RemoteDelivery` rejected server-side | Log close/parse error, exit; drives the R_Document field-filling loop. |
| Inbound not decodable as `R_Envelope` | Log hex of first 64 bytes, **continue**. |
| Unknown `contentType` field | Log field number, **continue**. |
| SIGINT | Close socket (1000), exit 0. |

### Exit codes

- `0` clean shutdown
- `1` config error
- `2` connect error
- `3` rejected by server
- `4` hello timeout

## Logging

SLF4J + Logback, console only, `INFO` default (`DEBUG` togglable in
`application.local.conf`). One line per protocol event; secrets (HMAC, cookie,
JWT) truncated. Full per-frame hex dump at `DEBUG`.

## Configuration

### `application.conf` (committed defaults)
```hocon
wsnew {
  host = "localhost"
  port = 2563
  path = "/wsnew"
  tls = true                 # server listener is withTLS=true -> wss://
  trustAllCerts = true       # accept dev self-signed per-account cert
  protocolVersion = 1
  applicationName = "wsnew-test-client"
  useHmac = true
  helloTimeoutSeconds = 10
  sendRemoteDelivery = false
}
```

A committed `application.local.conf.example` documents every override field;
copy it to the gitignored `application.local.conf` and edit for your env.

### `application.local.conf` (gitignored, user-owned)
```hocon
wsnew {
  uuid = "00000000-0000-0000-0000-000000000000"
  accountDomain = "test.example.com"
  apiKey = "REPLACE-ME"
  jwt = "eyJ..."                 # optional
  sendRemoteDelivery = true
  remoteDelivery {
    targetHost = "primary"       # verify against server logs
    targetActorPath = "/user/appMain"
    providerId = 1
    document { uuid = "1111...", name = "test.pdf" }
  }
}
```

`remoteDelivery.targetHost` / `targetActorPath` values must be verified against
what the server's `RemoteDelivery` dispatcher expects — to be confirmed during
implementation by reading the server routing and/or observing logs.

## Manual test plan

1. **Build:** `./gradlew run` (or `installDist` then run the start script).
2. **Start HCP:** `bloop run SiteApp-dev -m com.nps.eopng.siteapp.TestStarter`
   in `main_project`.
3. **Smoke (no HMAC):** `useHmac=false` → expect `HelloServer accepted=true`;
   HCP logs `HelloClient without HMAC fields, accepting without verification`.
4. **HMAC:** create account `test.example.com` + active API key in HCP, copy into
   local config, `useHmac=true` → expect `accepted=true`; HCP logs
   `HMAC matched one of N candidate key(s)`. Negative: corrupt one hex char of
   `apiKey` → expect `accepted=false`, exit 3.
5. **Session:** obtain a JWT for a user in that account, set `jwt` → expect
   `InitiateUserSessionResponse cookie=…`.
6. **Receive print:** ensure a document's `storageDetails.primaryStorageUuid`
   equals the client `uuid`; with client connected, POST to the REST endpoint
   `.../local-print-ws/print/{documentId}/{outputPortId}` (exact API base path +
   port to be confirmed during implementation) → expect client logs
   `PrintLocalDocument documentId=… outputPortId=… outputPort.name=…`.
7. **RemoteDelivery (iterative):** `sendRemoteDelivery=true` → watch HCP for
   `R_Document` parse errors, add missing required fields to `envelope_min.proto`,
   rebuild, repeat until accepted.

## Out of scope (v1)

- Automatic reconnect / exponential backoff.
- Heartbeats (server doesn't require them on `/wsnew`).
- `TerminateUserSession` (rely on disconnect).
- A full faithful `R_Document` (only fields the server requires for `AddDocument`).
- CA pinning / proper cert validation — dev uses `trustAllCerts`. Loading the
  real account CA could be added later.

## Implementation notes / learnings

- **TLS, not plaintext.** First connection attempts to `ws://localhost:2563/wsnew`
  failed with `unexpected end of stream` (server closed immediately). The server
  binds the listener with `withTLS=true`, so `wss://` + a trust-all
  `SSLSocketFactory` is required in dev. The client gained `tls` /
  `trustAllCerts` config flags and a TLS-enabled OkHttp client.
- OkHttp's `EventListener` does **not** fire for the WebSocket upgrade call
  (OkHttp swaps in `EventListener.NONE`), so connection diagnostics must come
  from `WebSocketListener` callbacks or external tools (`openssl s_client`,
  `curl --http1.1 -H "Upgrade: websocket" ...`).
