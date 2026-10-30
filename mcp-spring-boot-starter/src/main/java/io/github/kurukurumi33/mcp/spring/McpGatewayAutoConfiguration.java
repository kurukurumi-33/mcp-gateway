package io.github.kurukurumi33.mcp.spring;

import io.github.kurukurumi33.mcp.registry.ToolRegistry;
import io.github.kurukurumi33.mcp.server.McpServerEngine;
import io.github.kurukurumi33.mcp.server.RequestStateCodec;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Wires the protocol engine into a Spring application from whatever {@link BackendAdapter} beans exist.
 *
 * <p>An application enables the gateway by depending on this starter and declaring adapters; it does
 * not register the engine, the registry or the controller itself. Every bean here is
 * {@code @ConditionalOnMissingBean}, so an application that needs to customise one (a different
 * registry, its own controller) simply declares it.
 */
@AutoConfiguration
@EnableConfigurationProperties(McpGatewayProperties.class)
@ConditionalOnProperty(prefix = "mcp.gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpGatewayAutoConfiguration {

    private static final Log log = LogFactory.getLog(McpGatewayAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry mcpToolRegistry(List<BackendAdapter> adapters) {
        return new ToolRegistry(adapters);
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestStateCodec mcpRequestStateCodec(McpGatewayProperties properties) {
        String secret = properties.getRequestStateSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("mcp.gateway.request-state-secret is not set; using a random key. "
                    + "Multi-round calls will not survive a restart. Set a stable secret in production.");
            return RequestStateCodec.random();
        }
        return new RequestStateCodec(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    @ConditionalOnMissingBean
    public McpServerEngine mcpServerEngine(ToolRegistry registry, RequestStateCodec codec,
                                           McpGatewayProperties properties) {
        return new McpServerEngine(
                registry,
                codec,
                new McpServerEngine.ServerInfo(properties.getServerName(), properties.getServerVersion()),
                properties.getCacheTtlMs());
    }

    @Bean
    @ConditionalOnMissingBean
    public McpController mcpController(McpServerEngine engine, McpGatewayProperties properties) {
        return new McpController(engine, properties);
    }

    /**
     * Kept in its own configuration so the app still starts when actuator health is not on the
     * classpath — the starter treats it as optional.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    static class HealthConfiguration {

        @Bean
        @ConditionalOnMissingBean
        McpBackendHealthIndicator mcpBackendHealthIndicator(ToolRegistry registry) {
            return new McpBackendHealthIndicator(registry);
        }
    }
}