package io.github.kurukurumi33.mcp.protocol;

/**
 * Wire-level constants of the MCP 2026-07-28 stateless revision.
 *
 * <p>Everything here is dictated by the specification; nothing is configurable on purpose.
 * See {@code docs/specification/2026-07-28} in the modelcontextprotocol repository.
 */
public final class McpProtocol {

    private McpProtocol() {}

    /** The only protocol revision this gateway speaks. */
    public static final String PROTOCOL_VERSION = "2026-07-28";

    /** Required on every request; its value must equal {@link #PROTOCOL_VERSION}. */
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

    /** Required on every request; must mirror the body's {@code method}. */
    public static final String HEADER_METHOD = "Mcp-Method";

    /** Required when the method addresses a named entity; must mirror {@code params.name}/{@code params.uri}. */
    public static final String HEADER_NAME = "Mcp-Name";

    /** Key prefix for per-request context, replaced the removed {@code initialize} handshake. */
    public static final String META_PREFIX = "io.modelcontextprotocol/";

    public static final String META_PROTOCOL_VERSION = META_PREFIX + "protocolVersion";
    public static final String META_CLIENT_INFO = META_PREFIX + "clientInfo";
    public static final String META_CLIENT_CAPABILITIES = META_PREFIX + "clientCapabilities";
    public static final String META_SERVER_INFO = META_PREFIX + "serverInfo";
    public static final String META_LOG_LEVEL = META_PREFIX + "logLevel";

    /** Mandatory on every result since 2026-07-28. */
    public static final String RESULT_TYPE_COMPLETE = "complete";
    public static final String RESULT_TYPE_INPUT_REQUIRED = "input_required";

    // ---- methods ----

    public static final String METHOD_DISCOVER = "server/discover";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";
    public static final String METHOD_PROMPTS_LIST = "prompts/list";
    public static final String METHOD_RESOURCES_LIST = "resources/list";
    public static final String METHOD_RESOURCES_READ = "resources/read";

    // ---- JSON-RPC / MCP error codes (2026-07-28 segmentation) ----

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    /** Transport headers disagree with the body. */
    public static final int HEADER_MISMATCH = -32020;
    /** The request needs a client capability the client did not declare. */
    public static final int MISSING_REQUIRED_CLIENT_CAPABILITY = -32021;
    /** The requested (or absent) protocol revision is not supported. */
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;
}