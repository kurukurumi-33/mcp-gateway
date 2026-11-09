package io.github.kurukurumi33.mcp.registry;

import io.github.kurukurumi33.mcp.protocol.Json;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.PromptDefinition;
import io.github.kurukurumi33.mcp.spi.RequestContext;
import io.github.kurukurumi33.mcp.spi.ResourceContent;
import io.github.kurukurumi33.mcp.spi.ResourceDefinition;
import io.github.kurukurumi33.mcp.spi.ResourceTemplate;
import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import io.github.kurukurumi33.mcp.spi.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the three catalogs and the namespacing rules that keep them unambiguous.
 */
class GatewayRegistryTest {

    @Test
    @DisplayName("two backends can publish the same tool, resource and prompt name")
    void namespacesKeepTheCatalogUnambiguous() {
        GatewayRegistry registry = new GatewayRegistry(List.of(
                new StubAdapter("alpha").withResource("notes/todo.txt").withPrompt("summarize"),
                new StubAdapter("beta").withResource("notes/todo.txt").withPrompt("summarize")));

        assertEquals(List.of("alpha_list", "beta_list"),
                registry.tools().stream().map(RegisteredTool::qualifiedName).toList());
        assertEquals(List.of("mcp://alpha/notes/todo.txt", "mcp://beta/notes/todo.txt"),
                registry.resources().stream().map(RegisteredResource::qualifiedUri).toList());
        assertEquals(List.of("alpha_summarize", "beta_summarize"),
                registry.prompts().stream().map(RegisteredPrompt::qualifiedName).toList());
    }

    @Test
    @DisplayName("a resource template is published inside its namespace")
    void templatesAreQualified() {
        GatewayRegistry registry = new GatewayRegistry(List.of(
                new StubAdapter("store").withTemplate("objects/{key}")));

        assertEquals("mcp://store/objects/{key}", registry.resourceTemplates().get(0).qualifiedUriTemplate());
    }

    @Test
    @DisplayName("a read uri resolves to a namespace and the backend-local path")
    void resourceTargetStripsTheScheme() {
        GatewayRegistry registry = new GatewayRegistry(List.of(new StubAdapter("store")));

        ResourceUris.Parsed parsed = registry.resourceTarget("mcp://store/objects/report.csv").orElseThrow();
        assertEquals("store", parsed.namespace());
        assertEquals("objects/report.csv", parsed.uri());
    }

    @Test
    @DisplayName("a uri the gateway does not own resolves to nothing")
    void foreignSchemeIsNotRouted() {
        GatewayRegistry registry = new GatewayRegistry(List.of(new StubAdapter("store")));

        // The conformance suite uses exactly this shape to test the not-found path.
        assertTrue(registry.resourceTarget("test://nonexistent-resource").isEmpty());
        assertTrue(registry.resourceTarget("mcp://unknown/whatever").isEmpty());
        assertTrue(registry.resourceTarget("mcp://store").isEmpty());
        assertTrue(registry.resourceTarget(null).isEmpty());
    }

    @Test
    @DisplayName("one adapter declaring the same uri twice fails at startup")
    void duplicateResourceUriIsRejected() {
        // The namespace prefix makes a cross-adapter collision impossible, so this guard only ever
        // fires for a backend that lists one uri twice. It still has to fire: the second entry would
        // never be reachable.
        StubAdapter broken = new StubAdapter("store")
                .withResource("a.txt")
                .withResource("a.txt");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new GatewayRegistry(List.of(broken)));
        assertTrue(error.getMessage().contains("mcp://store/a.txt"), error.getMessage());
    }

    @Test
    @DisplayName("one adapter declaring the same prompt twice fails at startup")
    void duplicatePromptNameIsRejected() {
        StubAdapter broken = new StubAdapter("store").withPrompt("summarize").withPrompt("summarize");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new GatewayRegistry(List.of(broken)));
        assertTrue(error.getMessage().contains("store_summarize"), error.getMessage());
    }

    @Test
    @DisplayName("two adapters cannot share a namespace")
    void duplicateNamespaceIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new GatewayRegistry(List.of(new StubAdapter("store"), new StubAdapter("store"))));
        assertTrue(error.getMessage().contains("Duplicate adapter namespace"), error.getMessage());
    }

    // ------------------------------------------------------------------ fixture

    private static final class StubAdapter implements BackendAdapter {

        private final String namespace;
        private final List<ResourceDefinition> resources = new ArrayList<>();
        private final List<ResourceTemplate> templates = new ArrayList<>();
        private final List<PromptDefinition> prompts = new ArrayList<>();

        private StubAdapter(String namespace) {
            this.namespace = namespace;
        }

        StubAdapter withResource(String uri) {
            resources.add(ResourceDefinition.of(uri, uri));
            return this;
        }

        StubAdapter withTemplate(String uriTemplate) {
            templates.add(ResourceTemplate.of(uriTemplate, uriTemplate, "text/plain"));
            return this;
        }

        StubAdapter withPrompt(String name) {
            prompts.add(PromptDefinition.of(name, name));
            return this;
        }

        @Override
        public String namespace() {
            return namespace;
        }

        @Override
        public List<ToolDefinition> tools() {
            return List.of(ToolDefinition.of("list", "list", Json.obj()));
        }

        @Override
        public ToolResult execute(String toolName, RequestContext context) {
            return ToolResult.text("ok");
        }

        @Override
        public List<ResourceDefinition> resources() {
            return resources;
        }

        @Override
        public List<ResourceTemplate> resourceTemplates() {
            return templates;
        }

        @Override
        public List<PromptDefinition> prompts() {
            return prompts;
        }

        @Override
        public Optional<ResourceContent> readResource(String uri) {
            return Optional.empty();
        }
    }
}