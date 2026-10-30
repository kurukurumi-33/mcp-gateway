package io.github.kurukurumi33.mcp.app;

import io.github.kurukurumi33.mcp.backends.duckdb.DuckDBQueryAdapter;
import io.github.kurukurumi33.mcp.backends.s3.S3StorageAdapter;
import io.github.kurukurumi33.mcp.conformance.ConformanceAdapter;
import io.github.kurukurumi33.mcp.demo.ComputeAdapter;
import io.github.kurukurumi33.mcp.demo.StorageAdapter;
import io.github.kurukurumi33.mcp.demo.SystemAdapter;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.List;

/**
 * Sample application: three demo adapters, one MCP endpoint, actuator health.
 *
 * <p>Everything else — the registry, the engine, the controller, the health indicator — is provided
 * by the starter. Adding a real backend means adding another {@code @Bean BackendAdapter} here and
 * nothing else.
 */
@SpringBootApplication
public class McpGatewayApplication {

    /**
     * Rows the sample analytics backend starts with. In-memory DuckDB keeps them for the life of the
     * process; when {@code mcp.gateway.duckdb.url} points at a file, the seed is ignored so a restart
     * never re-inserts them.
     */
    private static final List<String> DEMO_SEED = List.of(
            "CREATE TABLE IF NOT EXISTS orders ("
                    + "id INTEGER, customer VARCHAR, amount DECIMAL(10,2), created_at DATE)",
            "INSERT INTO orders VALUES "
                    + "(1,'alice',120.50,DATE '2026-09-01'),"
                    + "(2,'bob',89.90,DATE '2026-09-03'),"
                    + "(3,'carol',245.00,DATE '2026-09-07'),"
                    + "(4,'dave',12.30,DATE '2026-09-11')",
            "CREATE TABLE IF NOT EXISTS events (id INTEGER, name VARCHAR, occurred_at TIMESTAMP)",
            "INSERT INTO events VALUES "
                    + "(1,'login',TIMESTAMP '2026-09-01 09:00:00'),"
                    + "(2,'query',TIMESTAMP '2026-09-01 09:05:00')");

    public static void main(String[] args) {
        SpringApplication.run(McpGatewayApplication.class, args);
    }

    @Bean
    BackendAdapter systemAdapter() {
        return new SystemAdapter();
    }

    @Bean
    BackendAdapter storageAdapter(Environment environment) {
        String endpoint = environment.getProperty("mcp.gateway.s3.endpoint");
        if (endpoint == null || endpoint.isBlank()) {
            // No S3 configured: fall back to the in-memory demo so the sample still runs.
            return new StorageAdapter();
        }
        return new S3StorageAdapter("storage",
                URI.create(endpoint),
                environment.getProperty("mcp.gateway.s3.region", "us-east-1"),
                environment.getProperty("mcp.gateway.s3.access-key", ""),
                environment.getProperty("mcp.gateway.s3.secret-key", ""),
                environment.getProperty("mcp.gateway.s3.bucket", "mcp-demo"));
    }

    @Bean
    BackendAdapter computeAdapter() {
        return new ComputeAdapter();
    }

    /**
     * Read-only SQL over DuckDB. In-memory by default so the sample runs with no external service;
     * point {@code mcp.gateway.duckdb.url} at a file to keep the data across restarts.
     */
    @Bean(destroyMethod = "close")
    BackendAdapter analyticsAdapter(Environment environment) {
        return new DuckDBQueryAdapter(
                "analytics",
                environment.getProperty("mcp.gateway.duckdb.url", "jdbc:duckdb:"),
                environment.getProperty("mcp.gateway.duckdb.max-rows", Integer.class, 200),
                environment.getProperty("mcp.gateway.duckdb.timeout-seconds", Integer.class, 5),
                DEMO_SEED);
    }

    /**
     * Diagnostic fixtures for the official conformance suite. Off by default: they exist only so
     * the suite can provoke capability and multi-round behaviours, and they are not tooling a real
     * deployment should expose.
     */
    @Bean
    @ConditionalOnProperty(name = "mcp.gateway.conformance-fixtures", havingValue = "true")
    BackendAdapter conformanceAdapter() {
        return new ConformanceAdapter();
    }
}