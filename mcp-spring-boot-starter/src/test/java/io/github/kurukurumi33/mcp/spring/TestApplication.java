package io.github.kurukurumi33.mcp.spring;

import io.github.kurukurumi33.mcp.demo.ComputeAdapter;
import io.github.kurukurumi33.mcp.demo.StorageAdapter;
import io.github.kurukurumi33.mcp.spi.BackendAdapter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Minimal host application for the integration tests. */
@SpringBootApplication
class TestApplication {

    @Bean
    BackendAdapter storageAdapter() {
        return new StorageAdapter();
    }

    @Bean
    BackendAdapter computeAdapter() {
        return new ComputeAdapter();
    }
}