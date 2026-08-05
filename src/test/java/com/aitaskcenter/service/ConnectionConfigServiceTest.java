package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aitaskcenter.model.ConnectionConfig;
import org.junit.jupiter.api.Test;

class ConnectionConfigServiceTest {
    @Test
    void replacesSavedLoopbackHostWhenContainerAliasIsConfigured() {
        ConnectionConfigService service = new ConnectionConfigService(
                null, "host.docker.internal");

        assertEquals(
                "jdbc:postgresql://host.docker.internal:5432/rob_english_word",
                service.jdbcUrl(postgres("127.0.0.1")));
    }

    @Test
    void preservesLoopbackHostForHostDevelopment() {
        ConnectionConfigService service = new ConnectionConfigService(
                null, "");

        assertEquals(
                "jdbc:postgresql://localhost:5432/rob_english_word",
                service.jdbcUrl(postgres("localhost")));
    }

    private ConnectionConfig postgres(String host) {
        ConnectionConfig config = new ConnectionConfig();
        config.setConnectionType("postgresql");
        config.setConnectionUrl(host);
        config.setPort(5432);
        config.setDatabaseName("rob_english_word");
        return config;
    }
}
