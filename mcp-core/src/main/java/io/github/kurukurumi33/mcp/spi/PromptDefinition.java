package io.github.kurukurumi33.mcp.spi;

import java.util.List;

/**
 * A prompt template a backend offers.
 *
 * <p>{@code name} is unqualified, exactly like {@link ToolDefinition#name()}: the registry prefixes it
 * with the adapter's namespace, so two backends can both publish a {@code summarize} prompt.
 *
 * @param name        prompt name within its namespace
 * @param description shown to the model and to the user picking a prompt
 * @param arguments   the arguments the template accepts; empty when it takes none
 */
public record PromptDefinition(String name, String description, List<PromptArgument> arguments) {

    public PromptDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("prompt name must not be blank");
        }
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        if (arguments.stream().map(PromptArgument::name).distinct().count() != arguments.size()) {
            throw new IllegalArgumentException("prompt '" + name + "' declares a duplicate argument name");
        }
    }

    /** A prompt with no arguments. */
    public static PromptDefinition of(String name, String description) {
        return new PromptDefinition(name, description, List.of());
    }
}