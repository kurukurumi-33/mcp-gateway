package io.github.kurukurumi33.mcp.registry;

import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The flat catalog of everything the gateway can do, built once from the discovered adapters.
 *
 * <p>Construction is where collisions surface. Two adapters claiming the same namespace, or two tools
 * from one adapter sharing a name, would produce a catalog with an ambiguous entry — a client could
 * route a call to the wrong backend. Rather than resolve that at call time, the registry refuses to
 * build, so the gateway fails at startup where the mistake is cheap to fix.
 */
public final class ToolRegistry {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9_]*");

    private final List<BackendAdapter> adapters;
    private final Map<String, RegisteredTool> byName;

    public ToolRegistry(List<BackendAdapter> adapters) {
        this.adapters = List.copyOf(adapters);

        Map<String, RegisteredTool> index = new LinkedHashMap<>();
        List<String> seenNamespaces = new ArrayList<>();

        for (BackendAdapter adapter : this.adapters) {
            String namespace = adapter.namespace();
            if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
                throw new IllegalStateException(
                        "Invalid adapter namespace '" + namespace + "' in " + adapter.getClass().getName()
                                + " — expected [a-z][a-z0-9_]*");
            }
            if (seenNamespaces.contains(namespace)) {
                throw new IllegalStateException("Duplicate adapter namespace '" + namespace + "'");
            }
            seenNamespaces.add(namespace);

            for (ToolDefinition definition : adapter.tools()) {
                RegisteredTool tool = new RegisteredTool(adapter, definition);
                RegisteredTool clash = index.putIfAbsent(tool.qualifiedName(), tool);
                if (clash != null) {
                    throw new IllegalStateException("Duplicate tool name '" + tool.qualifiedName() + "'");
                }
            }
        }

        // Map.copyOf does not preserve iteration order, and tools/list order is part of what clients
        // cache. Keep the declaration order and freeze it instead.
        this.byName = Collections.unmodifiableMap(new LinkedHashMap<>(index));
    }

    /** Every registered tool, in declaration order. */
    public List<RegisteredTool> tools() {
        return List.copyOf(byName.values());
    }

    /** Every adapter, in declaration order. */
    public List<BackendAdapter> adapters() {
        return adapters;
    }

    /** Resolves a qualified name to the tool that owns it. */
    public Optional<RegisteredTool> find(String qualifiedName) {
        return Optional.ofNullable(byName.get(qualifiedName));
    }

    public int size() {
        return byName.size();
    }
}