package io.github.kurukurumi33.mcp.spi;

import java.util.List;

/**
 * The extension point of the gateway: one implementation per backend you want to expose over MCP.
 *
 * <p>Implementations are plain objects — no framework required. The core discovers every adapter on
 * the classpath, asks each for its tools, and routes {@code tools/call} back to the owning adapter.
 * Adding a backend means adding an implementation and nothing else: no change to the protocol engine,
 * no change to the registry, no redeploy of the core.
 *
 * <p>Two rules keep the gateway's {@code tools/list} trustworthy even when a backend is sick:
 * <ul>
 *   <li>{@link #tools()} must describe the tools without contacting the backend. It runs at startup
 *       and on every {@code tools/list}; a network call here would make an unrelated outage look like
 *       a broken catalog.</li>
 *   <li>Reachability is reported separately through {@link #health()}, which the gateway runs on its
 *       own schedule and exposes through {@code server/discover} and actuator.</li>
 * </ul>
 *
 * <p>Long-running tools report progress by calling {@link RequestContext#progress} — the protocol
 * carries progress as notifications on the request's own stream, so no separate streaming entry
 * point exists.
 */
public interface BackendAdapter {

    /**
     * Short identifier that prefixes every tool name from this adapter, e.g. {@code storage} turns
     * {@code list_files} into {@code storage_list_files}. Must be stable — clients bake the qualified
     * name into prompts and saved workflows.
     *
     * <p>Allowed characters are lowercase letters, digits and underscore.
     */
    String namespace();

    /** One-line description of what this backend is, shown in {@code server/discover}. */
    default String description() {
        return namespace();
    }

    /**
     * Whether tool names are published as {@code namespace_name}.
     *
     * <p>True for ordinary backends, because two backends both publishing {@code list} must not
     * collide on one flat catalog. A backend whose tool names are already globally unique by
     * contract — an existing MCP server being passed through, or the conformance fixtures — can
     * opt out and keep its names exactly as they are.
     */
    default boolean prefixedToolNames() {
        return true;
    }

    /**
     * The tools this backend exposes, named <em>without</em> the namespace prefix.
     *
     * <p>Must be side-effect free and must not throw because the backend is unreachable.
     */
    List<ToolDefinition> tools();

    /**
     * Runs one tool.
     *
     * @param toolName the unqualified name, as returned from {@link #tools()}
     * @param context  arguments, client metadata and the notification sink
     * @return a completed result, a failure, or a request for more input
     */
    ToolResult execute(String toolName, RequestContext context);

    /**
     * Current reachability of the backend behind this adapter.
     *
     * <p>Defaults to {@link HealthStatus#UP} so adapters with no external dependency need not
     * implement it. Must not throw and must not block for long.
     */
    default HealthStatus health() {
        return HealthStatus.UP;
    }
}