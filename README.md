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
| Resources: `resources/list`, `resources/templates/list`, `resources/read` | implemented |
| Prompts: `prompts/list`, `prompts/get` | implemented |
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
notifications, resources and prompts, and the four rejections the revision requires. It boots a server
on an ephemeral port in the same process but talks to it over real HTTP, so what it prints is what a
client would see.

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

## Docker

The whole stack — gateway, object storage, bucket — comes up with one command:

```bash
docker compose up --build -d
curl -s http://127.0.0.1:8931/actuator/health
```

Three services, and each exists for a reason visible at runtime:

| Service | Why it is a separate container |
|---|---|
| `seaweedfs` | an S3 endpoint that is not AWS, so the adapter is exercised against the same API a real deployment uses. MinIO was the obvious choice until it pulled its Docker Hub images in 2025; SeaweedFS serves the same protocol on `:8333` |
| `createbucket` | the adapter's `health()` is a `HeadBucket`, and nothing in the gateway ever creates a bucket — so `docker/create-bucket.sh` runs once and exits before the gateway starts |
| `gateway` | the multi-stage image; the build stage compiles with `maven:3.9-eclipse-temurin-21`, the runtime stage is a JRE with a non-root user and a healthcheck on `/actuator/health` |

`MCP_REQUEST_STATE_SECRET` is pinned in `docker-compose.yml` rather than left to the random per-process
key. That is what makes a second replica possible: a `requestState` signed by one instance has to
verify on the other, so the key is deployment-wide state, not process state.

The S3 integration tests point at the same storage the compose stack exposes:

```bash
MCP_S3_ENDPOINT=http://127.0.0.1:8333 \
MCP_S3_ACCESS_KEY=mcpaccess MCP_S3_SECRET_KEY=mcpsecretmcpsecret \
mvn -pl mcp-backends -am test
```

```bash
docker compose down          # keep the volume
docker compose down -v       # drop the objects too
```

## Multi-instance

The protocol's claim is that any node can serve any request. `docker/multi-instance-demo.sh`
turns that from a design note into an experiment:

```bash
bash docker/multi-instance-demo.sh
```

It starts two gateway instances behind nginx and runs eight steps.

| Step | What happens | What it establishes |
|---|---|---|
| 1 | `storage_put_text` on node A writes `demo/incident.txt` | a shared backend, so the object exists for both nodes |
| 2 | `storage_delete_object` on node A answers `input_required` plus a `requestState` | the round ended without the server keeping anything |
| 3 | node A is stopped | the node that asked the question is gone |
| 4 | the token is replayed against node B with the confirmation | the rest of the call needs nothing from A |
| 5 | node B lists the bucket and the key is gone | the effect really happened, not just a 200 |
| 6 | the token's payload is edited and replayed | `-32602`, `requestState failed signature verification` |
| 7 | with A still down, three `tools/list` requests go through nginx | no sticky sessions, and the balancer can use the other node |
| 8 | node A is started again | the stack is left usable |

Two things make step 4 possible. The round-1 response carries the whole state, so there is nothing on
A to lose. And both containers are given the same `MCP_REQUEST_STATE_SECRET`, so B can verify a token
A signed. Step 4 also asserts the response has no `Mcp-Session-Id` header, which is the field this
revision deleted.

The limits are worth stating plainly, because "stateless" describes the protocol layer, not the
whole system:

- **An adapter must be stateless for the same test to pass.** The in-memory demo `StorageAdapter`
  would fail it: the object it wrote lives in A's heap. The S3 adapter passes because the bucket is
  the shared state, which is what step 1 is there to set up.
- **A stream that dies cannot be resumed.** Progress notifications ride the response body of one
  request. If the node carrying that body is killed, the client sees a truncated stream; only the
  next round of a multi-round call is recoverable, and only because the client holds the token.
- **One key, no rotation.** Every instance needs the same secret, so rotating it invalidates all
  tokens in flight at the moment of the swap.

## Testing

`mvn verify` runs 59 tests and writes a JaCoCo report per module under `target/site/jacoco/`. CI runs
the same command on every push against JDK 21, which is the release target rather than the local JDK.

| Module | Line coverage | What the tests are for |
|---|---|---|
| `mcp-core` | 86% | protocol constants, the engine's header and `_meta` validation, MRTR round trips, `requestState` signing, the three catalogs |
| `mcp-backends` | 79% | S3 adapter (unit and against SeaweedFS), DuckDB guard, truncation, catalog and error shaping |
| `mcp-spring-boot-starter` | 75% | the HTTP endpoint over a real port: status codes, SSE switch, actuator aggregation |

Two exclusions are deliberate. `mcp-core` skips `devtools/` and `conformance/` in the report: the dev
server is a harness no test drives, and the conformance fixtures exist to be called over the wire by
the official suite, so counting them would measure code nothing here executes.

The `mcp-backends` figure is the one measured with storage running — `docker compose up -d seaweedfs`,
then the same `mvn verify` with `MCP_S3_ENDPOINT` set. Without it the three S3 integration tests skip
themselves and the module reports 62%, which is what the gap between the two numbers is: the adapter's
upload, listing, delete-with-confirmation and `readResource` paths all need a real endpoint. CI runs
the same SeaweedFS container so the number there matches this one.

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

Current result: **85 passed, 6 failed** across the draft server scenarios.

| Scenario | Result |
|---|---|
| `server-stateless` | 25 / 25 |
| `http-header-validation` | 14 / 14 |
| `input-required-result-*` (13 scenarios) | 12 fully pass; `non-tool-request` is 1 / 2 |
| `sep-2164-resource-not-found` | 4 / 4 |
| `caching` | 8 / 8 |
| `http-custom-header-server-validation` | 1 / 6 |

The six remaining failures are two features rather than protocol errors. Five belong to the
`x-mcp-header` extension, which is not implemented. The sixth is `prompts/get` returning an
`input_required` result: multi-round requests are implemented for `tools/call` only, and `PromptResult`
has no shape for asking the client anything.

The `test_*` fixtures the suite drives live in `mcp-core/.../conformance/` and are registered only
when `mcp.gateway.conformance-fixtures` is true.

## Resources and prompts

Resources and prompts are part of the same SPI, as default methods, so a backend that has neither
implements nothing extra.

| Method | What the engine does |
|---|---|
| `resources/list` | flattens every adapter's resources into one list of `mcp://<namespace>/<path>` uris |
| `resources/templates/list` | the same for RFC 6570 templates |
| `resources/read` | splits the uri, routes to the owning adapter, shapes text or blob contents |
| `prompts/list` | publishes prompt names prefixed with the namespace, arguments included |
| `prompts/get` | validates required arguments, then routes to the adapter to render messages |

Four decisions are visible in the wire format:

- **Adapters declare backend-local paths, never uris.** An adapter says `reports/q3.parquet`; the
  gateway publishes `mcp://storage/reports/q3.parquet`. Two backends can both hold a
  `reports/q3.parquet`, and swapping the storage implementation does not invalidate uris clients
  already cached. Routing then needs no index lookup: the first path segment is the namespace.
- **Text or blob is the adapter's call.** `ResourceContent` requires exactly one of `text` and `blob`,
  and the factory that produced it decides. Guessing from the mime type in the engine would misfile
  every object uploaded by hand as `application/octet-stream`.
- **A missing resource is a JSON-RPC error, not a tool error.** The answer is `-32602` with
  `error.data.uri`, never a `200` with `contents: []`, because a client renders an empty array as an
  empty file.
- **Prompt arguments are validated in the engine.** Required arguments are declared in
  `PromptDefinition`, so omitting one is rejected with `-32602` before the adapter runs.

```java
@Override public List<ResourceDefinition> resources() {
    return List.of(ResourceDefinition.of("reports/q3.parquet", "Q3 report",
            "application/vnd.apache.parquet"));
}

@Override public List<ResourceTemplate> resourceTemplates() {
    return List.of(ResourceTemplate.of("{+key}", "any object by key", null));
}

@Override public Optional<ResourceContent> readResource(String uri) {
    byte[] bytes = fetch(uri);
    return Optional.of(isProbablyText(bytes)
            ? ResourceContent.text(uri, "text/plain", new String(bytes, UTF_8))
            : ResourceContent.binary(uri, "application/octet-stream", bytes));
}
```

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

`DuckDBQueryAdapter` is the backend that lets a model look at data rather than at object keys. It
exposes three tools, in the order a model is meant to use them:

| Tool | Purpose |
|---|---|
| `analytics_list_tables` | lists the tables and views in a schema, `main` by default |
| `analytics_describe_table` | columns, types, nullability and defaults for one table |
| `analytics_run_sql` | one read-only statement, returns columns plus rows |

The catalog is part of this backend rather than a separate one because an in-memory DuckDB database
exists only on its connection. A second adapter would have to share that connection, which turns one
resource into two backends that go down together. Same namespace also means a model sees
`analytics_list_tables` and `analytics_run_sql` next to each other and knows they read the same data.

```bash
curl -s -X POST http://127.0.0.1:8931/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/call' \
  -H 'Mcp-Name: analytics_run_sql' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"analytics_run_sql","arguments":{"sql":"SELECT customer, amount FROM orders ORDER BY amount DESC"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

The catalog tools bind their arguments as JDBC parameters instead of building the statement from
them, so a table name is always data. `describe_table` on a name that does not exist is a failed
call that tells the model to run `list_tables` first, rather than a driver error it has to decode.

`run_sql`'s guard has three layers, because one is easy to bypass:

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

`resources()`, `resourceTemplates()`, `readResource()`, `prompts()` and `getPrompt()` are default
methods, so a backend with no resources or prompts never mentions them. Overriding one is enough;
nothing else in the engine or registry changes.

## Layout

```
mcp-gateway/
├── mcp-core/                  protocol engine, BackendAdapter SPI, catalog, dev transport
│   └── src/main/java/io/github/kurukurumi33/mcp/
│       ├── protocol/          wire constants, JSON-RPC envelope, error codes
│       ├── spi/               BackendAdapter, tools, resources, prompts, RequestContext
│       ├── registry/          namespaced catalogs with collision detection, mcp:// routing
│       ├── server/            McpServerEngine, RequestStateCodec
│       ├── devtools/          JDK-HttpServer transport + main
│       ├── conformance/       diagnostic fixtures for the official test suite
│       └── demo/              three in-memory sample adapters
├── mcp-backends/              real adapters: S3StorageAdapter (any S3 endpoint), DuckDBQueryAdapter
├── mcp-spring-boot-starter/   autoconfiguration, POST endpoint, actuator health contributor
├── mcp-app/                   runnable sample: the starter with demo and real adapters
├── docker/                    nginx config, bucket bootstrap, multi-instance demo
├── docker-compose.yml         gateway plus S3-compatible storage in one command
└── docker-compose.multi.yml   two instances behind nginx, for the failover experiment
```

`mcp-core` has no Spring dependency, so the engine and SPI work in any Java runtime. The starter is one
thin binding; the next adapter behind the same SPI is a Spark backend.

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

- Adapters: Spark and HBase behind the same SPI
- `prompts/get` returning `input_required`, so multi-round prompts work as `tools/call` does
- The `x-mcp-header` extension, which the last five conformance checks cover
- `subscriptions/listen`, tasks extension, OAuth

## License

Apache License 2.0.