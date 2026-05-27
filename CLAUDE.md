# wsnew Test Client

A small standalone **Kotlin** command-line client that connects to the HCP
server's new local-print WebSocket endpoint (`/wsnew`, introduced on
`main_project` branch `HCP-16292-POC-secured`) and exercises the client→server
protocol so you can manually verify the feature end-to-end.

It is a throwaway test harness — not production code. It has no build coupling to
`main_project`; the protobuf messages are redeclared as a minimal,
wire-compatible subset in `src/main/proto/envelope_min.proto`.

## What it does

Drives the `/wsnew` protocol in order:

1. Opens a **TLS** WebSocket to `wss://host:port/wsnew` (the server listener is
   `withTLS=true`; the client trusts the dev self-signed cert).
2. Sends `HelloClient` and reads `HelloServer`, honouring `connectionAccepted` /
   `rejectionReason`. Supports both the HMAC-SHA256 handshake and the POC
   no-HMAC escape hatch.
3. Optionally sends `InitiateUserSession` (JWT) and stores the returned session
   cookie.
4. Optionally sends a `RemoteDelivery` wrapping an `AddDocument` mid-level
   request.
5. Stays connected and pretty-prints any inbound `PrintLocalDocument` until
   Ctrl-C.

## Layout

```
src/main/kotlin/com/ysoft/wsnewtest/
  Main.kt        entry point, exit codes
  Config.kt      HOCON config loader (defaults + local override)
  Hmac.kt        HmacSHA256 hex (matches server HelloHandler.computeHmac)
  Envelopes.kt   build outgoing / parse inbound R_Envelope
  WsClient.kt    OkHttp WebSocket transport (+ dev trust-all TLS)
  Flow.kt        protocol state machine
src/main/proto/envelope_min.proto   minimal wire-compatible message subset
src/test/kotlin/.../HmacTest.kt     locks the HMAC recipe to an openssl reference
docs/superpowers/specs/             design doc / plan for this app
```

## Configure

Defaults live in `src/main/resources/application.conf`. Override them in
`application.local.conf` (gitignored) in the project root:

```sh
cp application.local.conf.example application.local.conf
# edit accountDomain, uuid, useHmac/apiKey, jwt, etc.
```

Key fields: `host`, `port` (2563), `tls`/`trustAllCerts` (keep true for dev),
`uuid`, `accountDomain`, `useHmac` + `apiKey`, `jwt`, `sendRemoteDelivery`.

## Build & run

```sh
./gradlew build                 # compile + run HMAC unit test
./gradlew installDist           # build a launchable binary
BIN=./build/install/wsnew-test-client/bin/wsnew-test-client

# Run cumulative phases — each stops after the named step (great for debugging):
$BIN connect     # just open the TLS WebSocket
$BIN hello       # + HelloClient/HelloServer
$BIN session     # + InitiateUserSession (needs jwt)
$BIN adddoc      # + RemoteDelivery/AddDocument, then drain for the reaction
$BIN listen      # + stay connected, print inbound PrintLocalDocument
$BIN             # full (= hello -> session -> [adddoc if configured] -> listen)
```

Set `WSNEW_LOG_LEVEL=DEBUG` for verbose logging (per-frame hex + envelope
summaries). Exit codes: `0` clean, `1` config error, `2` connect error,
`3` rejected by server, `4` hello timeout.

## Prerequisites

- JDK 21.
- An HCP server (the `HCP-16292-POC-secured` branch) running with the local-print
  WS listener on port 2563.
- For the HMAC path: an account whose domain matches `accountDomain` and one of
  its active API keys (or the bootstrap key).
- For the user-session path: a JWT for a user in that account.

## Quick manual verification

1. `useHmac = false` → expect `HelloServer accepted=true` (escape hatch).
2. Set `useHmac = true` + a valid `apiKey` → expect `accepted=true`; corrupt the
   key → expect `accepted=false` and exit 3.
3. Set `jwt` → expect `InitiateUserSessionResponse cookie=…`.
4. With the client connected, POST to the POC trigger
   `.../local-print-ws/print/{documentId}/{outputPortId}` for a document whose
   `storageDetails.primaryStorageUuid` equals this client's `uuid` → expect a
   `PrintLocalDocument` log line.

See `docs/superpowers/specs/` for the full design and known caveats (notably the
minimal `R_Document` stub may need fields added before the server accepts
`AddDocument`).

## Troubleshooting

**`/wsnew` connects (TLS) but hangs with no `HelloServer` / hello timeout.**
The server side of port 2563 is not serving `/wsnew`. Verify which handler is
bound:

```sh
# 101 => that path's WS handshake works on this port; hang/empty => not served there
curl -k -s -D - -o /dev/null --http1.1 \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  https://<account-domain>:2563/wsnew | head -1
```

If `/ws` returns `101` on 2563 but `/wsnew` hangs, the running HCP build is older
than HCP-16292 (2563 is still a legacy `/ws` listener). Rebuild and restart HCP
from a branch that contains `LocalPrintWebSocketServerNettyIO` so 2563 serves
`/wsnew`.

Note: the local-print WS listener does **SNI-based per-account routing** — connect
using the customer domain (e.g. `testcustomer.localhost.nip.io`), not `localhost`,
so the server presents the account's cert and routes correctly.

## Getting a user JWT (for InitiateUserSession)

```sh
curl -k -s "https://<account-domain>:7300/api/v1/login?authtype=0&userid=<user>&password=<pass>" \
  -H "X-API-Key: dev-api-key" | jq -r .token.access_token
```

Put the `access_token` into `wsnew.jwt` in `application.local.conf`.
