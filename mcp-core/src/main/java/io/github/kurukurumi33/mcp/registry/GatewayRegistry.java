package io.github.kurukurumi33.mcp.registry;

import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.PromptDefinition;
import io.github.kurukurumi33.mcp.spi.ResourceDefinition;
import io.github.kurukurumi33.mcp.spi.ResourceTemplate;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The flat catalogs of everything the gateway can do — tools, resources, prompts — built once from the
 * discovered adapters.
 *
 * <p>Construction is where collisions surface. Two adapters claiming the same namespace, two tools
 * with one qualified name, two resources with one {@code mcp://} uri, or two prompts with one name
 * would each produce a catalog entry that routes to the wrong backend. Rather than resolve that at
 * call time, the registry refuses to build, so the gateway fails at startup where the mistake is
 * cheap to fix.
 *
 * <p>The three catalogs are keyed differently on purpose. Tools and prompts are addressed by name and
 * get the namespace as a prefix; resources are addressed by uri and get the namespace as a path
 * segment, because uris already have their own structure and a prefix would just be a slower way to
 * write a path.
 */
public final class GatewayRegistry {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9_]*");

    private final List<BackendAdapter> adapters;
    private final Map<String, BackendAdapter> byNamespace;
    private final Map<String, RegisteredTool> tools;
    private final Map<String, RegisteredResource> resources;
    private final Map<String, RegisteredPrompt> prompts;
    private final List<RegisteredResourceTemplate> templates;

    public GatewayRegistry(List<BackendAdapter> adapters) {
        this.adapters = List.copyOf(adapters);

        Map<String, BackendAdapter> namespaces = new LinkedHashMap<>();
        Map<String, RegisteredTool> toolIndex = new LinkedHashMap<>();
        Map<String, RegisteredResource> resourceIndex = new LinkedHashMap<>();
        Map<String, RegisteredPrompt> promptIndex = new LinkedHashMap<>();
        List<RegisteredResourceTemplate> templateList = new ArrayList<>();

        for (BackendAdapter adapter : this.adapters) {
            String namespace = adapter.namespace();
            if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
                throw new IllegalStateException(
                        "Invalid adapter namespace '" + namespace + "' in " + adapter.getClass().getName()
                                + " — expected [a-z][a-z0-9_]*");
            }
            if (namespaces.putIfAbsent(namespace, adapter) != null) {
                throw new IllegalStateException("Duplicate adapter namespace '" + namespace + "'");
            }

            for (ToolDefinition definition : adapter.tools()) {
                RegisteredTool tool = new RegisteredTool(adapter, definition);
                if (toolIndex.putIfAbsent(tool.qualifiedName(), tool) != null) {
                    throw new IllegalStateException("Duplicate tool name '" + tool.qualifiedName() + "'");
                }
            }

            for (ResourceDefinition definition : adapter.resources()) {
                RegisteredResource resource = new RegisteredResource(adapter, definition);
                if (resourceIndex.putIfAbsent(resource.qualifiedUri(), resource) != null) {
                    throw new IllegalStateException("Duplicate resource uri '" + resource.qualifiedUri() + "'");
                }
            }

            for (ResourceTemplate template : adapter.resourceTemplates()) {
                templateList.add(new RegisteredResourceTemplate(adapter, template));
            }

            for (PromptDefinition definition : adapter.prompts()) {
                RegisteredPrompt prompt = new RegisteredPrompt(adapter, definition);
                if (promptIndex.putIfAbsent(prompt.qualifiedName(), prompt) != null) {
                    throw new IllegalStateException("Duplicate prompt name '" + prompt.qualifiedName() + "'");
                }
            }
        }

        // Map.copyOf does not preserve iteration order, and list order is part of what clients
        // cache. Keep the declaration order and freeze it instead.
        this.byNamespace = Collections.unmodifiableMap(new LinkedHashMap<>(namespaces));
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(toolIndex));
        this.resources = Collections.unmodifiableMap(new LinkedHashMap<>(resourceIndex));
        this.prompts = Collections.unmodifiableMap(new LinkedHashMap<>(promptIndex));
        this.templates = List.copyOf(templateList);
    }

    /** Every adapter, in declaration order. */
    public List<BackendAdapter> adapters() {
        return adapters;
    }

    /** Resolves a namespace to its adapter. */
    public Optional<BackendAdapter> adapter(String namespace) {
        return Optional.ofNullable(byNamespace.get(namespace));
    }

    /** Every registered tool, in declaration order. */
    public List<RegisteredTool> tools() {
        return List.copyOf(tools.values());
    }

    /** Every registered resource, in declaration order. */
    public List<RegisteredResource> resources() {
        return List.copyOf(resources.values());
    }

    /** Every resource template, with its namespace available for rendering. */
    public List<RegisteredResourceTemplate> resourceTemplates() {
        return templates;
    }

    /** Every registered prompt, in declaration order. */
    public List<RegisteredPrompt> prompts() {
        return List.copyOf(prompts.values());
    }

    /** Resolves a qualified tool name to the tool that owns it. */
    public Optional<RegisteredTool> findTool(String qualifiedName) {
        return Optional.ofNullable(tools.get(qualifiedName));
    }

    /** Resolves a qualified prompt name to the prompt that owns it. */
    public Optional<RegisteredPrompt> findPrompt(String qualifiedName) {
        return Optional.ofNullable(prompts.get(qualifiedName));
    }

    /**
     * Resolves a qualified resource uri to the adapter that serves it.
     *
     * <p>Deliberately does not consult the resource index: a uri built from a template is not listed
     * anywhere, and a backend may serve a uri it never advertised. Routing is by namespace, and the
     * adapter decides whether the path exists.
     */
    public Optional<ResourceUris.Parsed> resourceTarget(String qualifiedUri) {
        return ResourceUris.parse(qualifiedUri)
                .filter(parsed -> byNamespace.containsKey(parsed.namespace()));
    }

    public int toolCount() {
        return tools.size();
    }
}