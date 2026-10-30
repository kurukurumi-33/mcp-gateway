package io.github.kurukurumi33.mcp.demo;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;

import java.util.List;

/**
 * Stands in for a batch engine such as Spark.
 *
 * <p>{@code slow_sum} is the streaming example. It has no streaming entry point of its own: progress
 * and log messages go through {@link RequestContext}, and the engine puts them on the request's own
 * response stream. Log output is dropped unless the client asked for it on this call, which the
 * context enforces rather than the adapter.
 */
public final class ComputeAdapter implements BackendAdapter {

    @Override
    public String namespace() {
        return "compute";
    }

    @Override
    public String description() {
        return "Batch jobs (demo data)";
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                ToolDefinition.builder("slow_sum")
                        .description("Adds 1..n one step at a time, reporting progress. Use to test streaming.")
                        .inputSchema(Json.obj()
                                .put("type", "object")
                                .set("properties", Json.obj().set("n", Json.obj()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("default", 3)))
                                .set("required", Json.arr().add("n")))
                        .outputSchema(Json.obj()
                                .put("type", "object")
                                .set("properties", Json.obj().set("sum", Json.obj().put("type", "integer")))
                                .set("required", Json.arr().add("sum")))
                        .streaming(true)
                        .build());
    }

    @Override
    public ToolResult execute(String toolName, RequestContext context) {
        if (!"slow_sum".equals(toolName)) {
            return ToolResult.error("compute has no tool named '" + toolName + "'");
        }

        Integer requested = context.intArg("n");
        int n = requested == null ? 3 : requested;
        if (n < 1) {
            return ToolResult.error("'n' must be at least 1");
        }

        long sum = 0;
        for (int step = 1; step <= n; step++) {
            sum += step;
            context.progress(step, (double) n, "added " + step);
            context.log("info", "step " + step + " of " + n);
            sleepQuietly(20);
        }
        return ToolResult.of("sum = " + sum, Json.obj().put("sum", sum));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}