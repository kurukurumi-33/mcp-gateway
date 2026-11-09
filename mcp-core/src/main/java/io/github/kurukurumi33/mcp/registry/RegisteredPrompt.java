package io.github.kurukurumi33.mcp.registry;

import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.PromptDefinition;

/**
 * A prompt together with the adapter that renders it.
 *
 * <p>Prompt names are always namespaced, unlike tool names: {@code prompts/get} addresses a prompt by
 * name alone, with none of the structure a resource uri carries, so there is no second place for the
 * namespace to live.
 */
public record RegisteredPrompt(BackendAdapter adapter, PromptDefinition definition) {

    /** The name clients see, e.g. {@code storage_summarize}. */
    public String qualifiedName() {
        return adapter.namespace() + "_" + definition.name();
    }
}