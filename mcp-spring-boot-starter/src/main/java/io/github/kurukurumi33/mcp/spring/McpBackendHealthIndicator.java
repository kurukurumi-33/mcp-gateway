package io.github.kurukurumi33.mcp.spring;

import io.github.kurukurumi33.mcp.registry.ToolRegistry;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.github.kurukurumi33.mcp.spi.HealthStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes per-backend reachability under {@code /actuator/health}.
 *
 * <p>One sick backend does not take the gateway down. Liveness asks "can this process still serve
 * anything?", and the answer is yes as long as at least one backend is reachable — the gateway still
 * answers {@code server/discover} and still routes the healthy tools. Marking the whole app DOWN for
 * one unreachable database would have Kubernetes restart a process that has nothing wrong with it.
 *
 * <p>When every backend is down the indicator reports DOWN, because at that point the process is
 * alive but cannot do the job it exists for.
 */
public class McpBackendHealthIndicator implements HealthIndicator {

    private final ToolRegistry registry;

    public McpBackendHealthIndicator(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Map<String, Object> backends = new LinkedHashMap<>();
        boolean anyCallable = registry.adapters().isEmpty();

        for (BackendAdapter adapter : registry.adapters()) {
            HealthStatus status = adapter.health();
            anyCallable |= status.isCallable();
            backends.put(adapter.namespace(), Map.of(
                    "state", status.state().name().toLowerCase(),
                    "detail", status.detail(),
                    "tools", adapter.tools().size()));
        }

        Health.Builder builder = anyCallable ? Health.up() : Health.down();
        return builder
                .withDetail("tools", registry.size())
                .withDetail("backends", backends)
                .build();
    }
}