package io.github.kurukurumi33.mcp.backends.s3;

import io.github.kurukurumi33.mcp.spi.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Behaviour that must hold without a storage server: the catalog is static, and an unreachable
 * backend degrades instead of throwing.
 */
class S3StorageAdapterTest {

    private static S3StorageAdapter unreachable() {
        return new S3StorageAdapter("storage", URI.create("http://127.0.0.1:1"), "us-east-1",
                "access", "secret", "missing-bucket");
    }

    @Test
    @DisplayName("tools() describes the catalog without contacting storage")
    void catalogDoesNotNeedTheBackend() {
        List<String> names = unreachable().tools().stream().map(ToolDefinition::name).toList();

        assertEquals(List.of("list_buckets", "list_objects", "put_text", "get_text", "delete_object"),
                names);
    }

    @Test
    @DisplayName("an unreachable store reports DOWN instead of throwing")
    void healthDegradesInsteadOfFailing() {
        assertFalse(unreachable().health().isCallable());
    }
}