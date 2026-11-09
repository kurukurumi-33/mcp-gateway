package io.github.kurukurumi33.mcp.spi;

/**
 * One argument a prompt accepts, mirroring the protocol's {@code PromptArgument}.
 *
 * @param name        argument name, as the caller passes it in {@code prompts/get}
 * @param description what it is for; the client shows this when asking the user
 * @param required    whether {@code prompts/get} must supply it
 */
public record PromptArgument(String name, String description, boolean required) {

    public PromptArgument {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("prompt argument name must not be blank");
        }
    }

    /** An optional argument. */
    public static PromptArgument of(String name, String description) {
        return new PromptArgument(name, description, false);
    }

    /** An argument the caller must supply. */
    public static PromptArgument required(String name, String description) {
        return new PromptArgument(name, description, true);
    }
}