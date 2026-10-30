# MCP Gateway

[![CI](https://github.com/kurukurumi-33/mcp-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/kurukurumi-33/mcp-gateway/actions/workflows/ci.yml)

A multi-backend gateway framework for the **MCP 2026-07-28** stateless protocol, written in Java.

One endpoint in front of many backends. Each backend is a small `BackendAdapter` implementation; the
gateway owns the protocol, the tool catalog, health reporting and multi-round calls, so adding a
backend never means touching the protocol engine.

## Why this exists

The official MCP Java SDK does not speak this revision yet. As of **2026-10**, both `2.1.0` (stable)
and `2.2.0-M1` (milestone) top out at protocol `2025-11-25`:

```
$ javap -constants io.modelcontextprotocol.spec.ProtocolVersions
  MCP_2024_11_05 = "2024-11-05"
  MCP_2025_03_26 = "2025-03-26"
  MCP_2025_06_18 = "2025-06-18"
  MCP_2025_11_25 = "2025-11-25"      <-- no 2026-07-28
```

The session types the new revision removes — `McpSession`, `InitializeRequest`, `McpServerSession` —
are all still present. There is no `server/discover` and no multi-round request support. So a Java
server that speaks `2026-07-28` has to carry its own protocol layer.

Separately, existing "MCP gateway" projects aggregate *other MCP servers*. This one goes the other
way: it adapts plain backends directly, with no MCP server in between.

## What 2026-07-28 changes (and what is implemented here)

| Change | Status |
|---|---|
| No session, no `Mcp-Session-Id` — any node serves any request | implemented |
| `initialize` handshake removed; per-request `_meta` carries version, client info, capabilities | implemented |
| `server/discover` is mandatory | implemented |
| `resultType` required on every result (`complete` / `input_required`) | implemented |
| `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` headers must mirror the body | implemented, with rejection |
| Multi-round requests: server returns `input_required` + `inputRequests`, client replays with `inputResponses` | implemented |
| Error codes `-32020` / `-32021` / `-32022` | implemented |
| Cacheable results (`ttlMs`, `cacheScope`) | implemented |
| Progress and log notifications on the request's own stream; logging only when the client opts in | implemented |
| `subscriptions/listen`, tasks extension, OAuth, stdio transport | not implemented |

## Quick start

```bash
mvn verify                     # protocol, adapter and Spring transport tests, plus a coverage report

# Install the sibling modules once, then run the app on :8931 (MCP endpoint + actuator health)
mvn -DskipTests install
mvn -pl mcp-app spring-boot:run

# Walk the whole surface against a live server and print each step:
mvn -pl mcp-core exec:java -Ddemo.mainClass=io.github.kurukurumi33.mcp.devtools.DevClientMain

# ...or leave the framework-free dev server on http://127.0.0.1:8931/mcp and poke it yourself:
mvn -pl mcp-core exec:java
```

The walkthrough covers discovery, the namespaced catalog, a plain call, a two-round request, streaming
notifications and the four rejections the revision requires. It boots a server on an ephemeral port in
the same process but talks to it over real HTTP, so what it prints is what a client would see.

Talking to either server by hand:

```bash
curl -s -X POST http://127.0.0.1:8931/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: server/discover' \
  -d '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

Both `_meta` members are required. Without a handshake there is nothing to negotiate against, so a
request that omits either one is rejected as invalid params rather than defaulted.

Ask for `Accept: text/event-stream` on a call whose tool reports progress and the response switches to
SSE; without it you get a single JSON body.

## Testing

`mvn verify` runs 38 tests and writes a JaCoCo report per module under `target/site/jacoco/`. CI runs
the same command on every push against JDK 21, which is the release target rather than the local JDK.

| Module | Line coverage | What the tests are for |
|---|---|---|
| `mcp-core` | 87% | protocol constants, the engine's header and `_meta` validation, MRTR round trips, `requestState` signing, the registry |
| `mcp-backends` | 60% | S3 adapter (unit) and DuckDB guard, truncation and error shaping |
| `mcp-spring-boot-starter` | 74% | the HTTP endpoint over a real port: status codes, SSE switch, actuator aggregation |

Two exclusions are deliberate. `mcp-core` skips `devtools/` and `conformance/` in the report: the dev
server is a harness no test drives, and the conformance fixtures exist to be called over the wire by
the official suite, so counting them would measure code nothing here executes. `mcp-backends` sits
lower because its three S3 integration tests skip without an endpoint; that is the gap between 60%
here and what the same module reports with storage running.

What the tests cannot reach is the reason `docs/manual-tests.http` exists: an HTTP status code that
disagrees with the JSON-RPC body, a `Content-Type` that should have switched to `text/event-stream`,
and whether SSE messages actually arrive before the response completes. Each of those is a fact about
the wire, not about a return value.

## Conformance

The official conformance suite plays the client, drives the server, and checks every response against
the specification. This revision is still counted as the draft there, so the run needs the alpha CLI
and the `draft` suite:

```bash
mvn -DskipTests package
java -jar mcp-app/target/mcp-app-0.1.0-SNAPSHOT.jar --mcp.gateway.conformance-fixtures=true

npx @modelcontextprotocol/conformance@alpha server \
  --url http://127.0.0.1:8931/mcp --suite draft
```

Current result: **79 passed, 9 failed** across the draft server scenarios.

| Scenario | Result |
|---|---|
| `server-stateless` | 25 / 25 |
| `http-header-validation` | 14 / 14 |
| `input-required-result-*` (13 scenarios) | all pass except the prompt-based one |
| `sep-2164-resource-not-found` | 2 / 2 |
| `caching` | 4 / 7 |
| `http-custom-header-server-validation` | 1 / 6 |
| `input-required-result-non-tool-request` | 1 / 2 |

The remaining failures are missing features rather than protocol errors: `prompts/*` and
`resources/*` are not implemented, so neither the caching hints on those lists nor `prompts/get` can
be exercised, and the `x-mcp-header` extension is not implemented at all.

The `test_*` fixtures the suite drives live in `mcp-core/.../conformance/` and are registered only
when `mcp.gateway.conformance-fixtures` is true.

## Object storage

`mcp-backends` holds `S3StorageAdapter`, written against the S3 API rather than one vendor, so the
same adapter serves SeaweedFS, MinIO, Ceph, Alibaba Cloud OSS or AWS — only the endpoint changes.
Path-style addressing is forced because self-hosted implementations rarely do virtual-host buckets
on a bare address.

```bash
MCP_S3_ENDPOINT=http://127.0.0.1:8333 \
MCP_S3_ACCESS_KEY=mcpaccess MCP_S3_SECRET_KEY=mcpsecretmcpsecret \
java -jar mcp-app/target/mcp-app-0.1.0-SNAPSHOT.jar
```

With no endpoint configured the app falls back to the in-memory demo adapter. The integration test
is gated on the same variable, so the build stays green on a machine with no storage:

```bash
MCP_S3_ENDPOINT=http://127.0.0.1:8333 mvn -pl mcp-backends -am test
```

## SQL backend

`DuckDBQueryAdapter` exposes one read-only tool, `analytics_run_sql`. It is the backend that lets a
model look at data rather than at object keys; the catalog and query layers build on it.

```bash
curl -s -X POST http://127.0.0.1:8931/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/call' \
  -H 'Mcp-Name: analytics_run_sql' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"analytics_run_sql","arguments":{"sql":"SELECT customer, amount FROM orders ORDER BY amount DESC"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

The guard has three layers, because one is easy to bypass:

- the statement must start with a read-only keyword, checked **after** string literals and comments
  are stripped, so `SELECT 'drop table x'` is not mistaken for a write;
- a second statement separator is rejected, so `SELECT 1; DROP TABLE t` never reaches the driver;
- results are capped with `setMaxRows`, so `SELECT *` on a large table cannot fill the gateway heap.

`jdbc:duckdb:` is in-memory and seeded with sample `orders` / `events` tables at startup. Point
`MCP_DUCKDB_URL` at a file to persist; the seed is then skipped, so a restart never re-inserts rows.

| Setting | Default | Meaning |
|---|---|---|
| `MCP_DUCKDB_URL` | `jdbc:duckdb:` | in-memory or a `.duckdb` file |
| `mcp.gateway.duckdb.max-rows` | 200 | hard cap per call |
| `mcp.gateway.duckdb.timeout-seconds` | 5 | passed to `setQueryTimeout` when supported |

## Using it from Spring Boot

Depend on the starter, declare your adapters as beans, and the endpoint appears:

```java
@SpringBootApplication
public class MyGateway {
    public static void main(String[] args) { SpringApplication.run(MyGateway.class, args); }

    @Bean BackendAdapter warehouse() { return new WarehouseAdapter(); }
}
```

```yaml
mcp:
  gateway:
    path: /mcp
    server-name: my-gateway
    request-state-secret: ${MCP_REQUEST_STATE_SECRET}
```

Everything else — registry, engine, controller, health contributor — is autoconfigured and every bean
can be overridden by declaring your own. With actuator on the classpath, `/actuator/health` reports
each backend's state under the `mcpBackend` component.

The dev server in `mcp-core` binds the JDK's own HTTP server and exists so the engine can be exercised
without a framework. It is not hardened — no TLS, no auth, no limits.

## Adding a backend

Implement `BackendAdapter` and register it. Nothing in the protocol engine changes.

```java
public final class WarehouseAdapter implements BackendAdapter {

    @Override public String namespace() { return "warehouse"; }

    @Override public List<ToolDefinition> tools() {
        return List.of(ToolDefinition.of(
                "row_count",
                "Counts rows in a table.",
                Json.obj().put("type", "object")
                        .set("properties", Json.obj().set("table", Json.obj().put("type", "string")))
                        .set("required", Json.arr().add("table"))));
    }

    @Override public ToolResult execute(String toolName, RequestContext context) {
        return ToolResult.structured(Json.obj().put("rows", countRows(context.stringArg("table"))));
    }

    @Override public HealthStatus health() {
        return canReachWarehouse() ? HealthStatus.UP : HealthStatus.down("connection refused");
    }
}
```

Two rules keep the catalog trustworthy:

- `tools()` must describe tools **without contacting the backend**. It runs at startup and on every
  `tools/list`; a network call there turns an unrelated outage into a broken catalog.
- `health()` is how reachability is reported. A backend that is down keeps its tools listed but marks
  them unavailable, so an agent can see that a capability exists rather than losing it silently.

## Layout

```
mcp-gateway/
├── mcp-core/                  protocol engine, BackendAdapter SPI, tool registry, dev transport
│   └── src/main/java/io/github/kurukurumi33/mcp/
│       ├── protocol/          wire constants, JSON-RPC envelope, error codes
│       ├── spi/               BackendAdapter, ToolDefinition, ToolResult, RequestContext
│       ├── registry/          namespaced catalog with collision detection
│       ├── server/            McpServerEngine, RequestStateCodec
│       ├── devtools/          JDK-HttpServer transport + main
│       ├── conformance/       diagnostic fixtures for the official test suite
│       └── demo/              three in-memory sample adapters
├── mcp-backends/              real adapters: S3StorageAdapter (any S3 endpoint), DuckDBQueryAdapter
├── mcp-spring-boot-starter/   autoconfiguration, POST endpoint, actuator health contributor
└── mcp-app/                   runnable sample: the starter with demo and real adapters
```

`mcp-core` has no Spring dependency, so the engine and SPI work in any Java runtime. The starter is one
thin binding; the next adapters are the catalog layer over `information_schema` and a Spark backend.

## Notes on two deliberate choices

**`requestState` is signed.** Removing sessions left one place for the server to remember a call in
flight: an opaque token the client replays. Anything holding that token can edit it. Since the gateway
acts on what comes back, an unsigned token would let a caller rewrite the operation it is resuming.
Tokens are `base64url(payload).base64url(HMAC-SHA256(payload))`, and a bad signature is a hard
`-32602` rather than a fallback — a token that does not verify is not stale, it is forged.

**Streaming is progress notifications, not a separate API.** MCP has no "streaming tool result": the
final result is a single message, and interim feedback travels as `notifications/progress`. So
`BackendAdapter` has no streaming entry point — a long-running tool calls `context.progress(...)` and
the engine puts those messages on the request's own response stream. This also keeps one code path
instead of two that drift apart.

## Roadmap

- Catalog layer: expose `information_schema` so a model can see tables before querying them
- Adapters: Spark and HBase behind the same SPI
- Multi-instance deployment (nginx upstream, shared signing key) with a documented availability test
- `subscriptions/listen`, tasks extension, OAuth

## License

Apache License 2.0.