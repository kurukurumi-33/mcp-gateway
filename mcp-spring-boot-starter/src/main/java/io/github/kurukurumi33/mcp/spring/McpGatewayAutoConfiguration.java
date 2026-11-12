package io.github.kurukurumi33.mcp.spring;

import io.github.kurukurumi33.mcp.registry.GatewayRegistry;
import io.github.kurukurumi33.mcp.server.AdmissionControl;
import io.github.kurukurumi33.mcp.server.CallLimiter;
import io.github.kurukurumi33.mcp.server.McpServerEngine;
import io.github.kurukurumi33.mcp.server.RequestStateCodec;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    public GatewayRegistry mcpGatewayRegistry(List<BackendAdapter> adapters) {
        return new GatewayRegistry(adapters);
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestStateCodec mcpRequestStateCodec(McpGatewayProperties properties) {
        Duration ttl = Duration.ofSeconds(properties.getRequestStateTtlSeconds());
        String secret = properties.getRequestStateSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("mcp.gateway.request-state-secret is not set; using a random key. "
                    + "Multi-round calls will not survive a restart. Set a stable secret in production.");
            return RequestStateCodec.random(ttl);
        }
        return new RequestStateCodec(secret.getBytes(StandardCharsets.UTF_8), ttl);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdmissionControl mcpAdmissionControl(McpGatewayProperties properties,
                                                ObjectProvider<CallLimiter> limiter) {
        return AdmissionControl.of(
                properties.getMaxConcurrentCallsPerBackend(),
                properties.getMaxQueuedCallsPerBackend(),
                properties.getAdmissionQueueWaitMs(),
                properties.getCallTimeoutMs(),
                limiter.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public McpServerEngine mcpServerEngine(GatewayRegistry registry, RequestStateCodec codec,
                                           AdmissionControl admissionControl,
                                           McpGatewayProperties properties) {
        return new McpServerEngine(
                registry,
                codec,
                new McpServerEngine.ServerInfo(properties.getServerName(), properties.getServerVersion()),
                properties.getCacheTtlMs(),
                admissionControl);
    }

    @Bean
    @ConditionalOnMissingBean
    public McpController mcpController(McpServerEngine engine, McpGatewayProperties properties) {
        return new McpController(engine, properties);
    }

    /**
     * Redis-backed shared rate limit. Optional on two levels: the client classes have to be present
     * and the feature has to be switched on. Enabling it without the client is an error at startup
     * rather than a silent "unlimited".
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    @ConditionalOnProperty(prefix = "mcp.gateway.rate-limit", name = "enabled", havingValue = "true")
    static class RedisRateLimitConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CallLimiter mcpCallLimiter(StringRedisTemplate redis, McpGatewayProperties properties,
                                   ObjectProvider<MeterRegistry> meters) {
            McpGatewayProperties.RateLimit config = properties.getRateLimit();
            return new RedisCallLimiter(redis, config.getCapacity(), config.getRefillPerSecond(),
                    config.getKeyPrefix(), config.isFailOpen(), config.getTimeoutMs(),
                    meters.getIfAvailable());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.data.redis.core.StringRedisTemplate")
    @ConditionalOnProperty(prefix = "mcp.gateway.rate-limit", name = "enabled", havingValue = "true")
    static class RedisRateLimitMissingConfiguration {

        @Bean
        Object mcpRateLimitNeedsRedis() {
            throw new IllegalStateException("mcp.gateway.rate-limit.enabled=true requires "
                    + "spring-boot-starter-data-redis on the classpath");
        }
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
        McpBackendHealthIndicator mcpBackendHealthIndicator(GatewayRegistry registry) {
            return new McpBackendHealthIndicator(registry);
        }
    }
}