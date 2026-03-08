/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.valkey.javabenchmark.client.impl;

import glide.api.GlideClient;
import glide.api.GlideClusterClient;
import glide.api.models.GlideString;
import glide.api.models.configuration.*;
import io.valkey.javabenchmark.client.AsyncHelper;
import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.client.TimedResult;
import io.valkey.javabenchmark.config.DriverConfig;
import io.valkey.javabenchmark.util.VersionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Valkey GLIDE implementation of BenchmarkClient.
 *
 * @author Ilia Kolominsky
 */
public class ValkeyGlideBenchmarkClient implements BenchmarkClient {

    private static final Logger logger = LoggerFactory.getLogger(ValkeyGlideBenchmarkClient.class);

    private GlideClient glideClient;
    private GlideClusterClient glideClusterClient;
    private boolean isClusterMode;
    private boolean connected;

    @Override
    public String getDriverId() {
        return "valkey-glide";
    }

    @Override
    public String getDescription() {
        return "Valkey GLIDE high-performance client";
    }

    @Override
    public String getDriverVersion() {
        return VersionHelper.getVersion(GlideClient.class, "io.valkey", "valkey-glide");
    }

    @Override
    public void connect(String host, int port, DriverConfig driverConfig) throws ClientException {
        logger.info("Connecting ValkeyGlide to {}:{} (cluster={})", host, port, driverConfig.isClusterMode());
        
        try {
            this.isClusterMode = driverConfig.isClusterMode();
            
            NodeAddress nodeAddress = NodeAddress.builder()
                    .host(host)
                    .port(port)
                    .build();
            
            if (isClusterMode) {
                var configBuilder = GlideClusterClientConfiguration.builder()
                        .address(nodeAddress);
                
                // Configure TLS if enabled
                if (driverConfig.isTlsEnabled()) {
                    configBuilder.useTLS(true);
                }
                
                // Configure authentication if provided
                if (driverConfig.hasAuth()) {
                    DriverConfig.AuthConfig auth = driverConfig.getAuth();
                    if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                        configBuilder.credentials(ServerCredentials.builder()
                                .username(auth.getUsername())
                                .password(auth.getPassword())
                                .build());
                    } else {
                        configBuilder.credentials(ServerCredentials.builder()
                                .password(auth.getPassword())
                                .build());
                    }
                }
                
                glideClusterClient = GlideClusterClient.createClient(configBuilder.build()).get();
            } else {
                var configBuilder = GlideClientConfiguration.builder()
                        .address(nodeAddress);
                
                // Configure TLS if enabled
                if (driverConfig.isTlsEnabled()) {
                    configBuilder.useTLS(true);
                }
                
                // Configure authentication if provided
                if (driverConfig.hasAuth()) {
                    DriverConfig.AuthConfig auth = driverConfig.getAuth();
                    if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                        configBuilder.credentials(ServerCredentials.builder()
                                .username(auth.getUsername())
                                .password(auth.getPassword())
                                .build());
                    } else {
                        configBuilder.credentials(ServerCredentials.builder()
                                .password(auth.getPassword())
                                .build());
                    }
                }
                
                glideClient = GlideClient.createClient(configBuilder.build()).get();
            }
            
            // Test connection
            String pingResponse;
            if (isClusterMode) {
                pingResponse = glideClusterClient.ping().get();
            } else {
                pingResponse = glideClient.ping().get();
            }
            
            if (!"PONG".equals(pingResponse)) {
                throw new ClientException("Unexpected ping response: " + pingResponse);
            }
            
            connected = true;
            logger.info("ValkeyGlide connected successfully");
            
        } catch (Exception e) {
            throw new ClientException("Failed to connect ValkeyGlide to " + host + ":" + port, e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public CompletableFuture<TimedResult<Void>> set(byte[] key, byte[] value) {
        GlideString gsKey = GlideString.of(key);
        GlideString gsValue = GlideString.of(value);
        return AsyncHelper.timedVoid(() -> {
            if (isClusterMode) {
                glideClusterClient.set(gsKey, gsValue).get();
            } else {
                glideClient.set(gsKey, gsValue).get();
            }
        });
    }

    @Override
    public CompletableFuture<TimedResult<byte[]>> get(byte[] key) {
        GlideString gsKey = GlideString.of(key);
        return AsyncHelper.timed(() -> {
            GlideString gs = isClusterMode
                    ? glideClusterClient.get(gsKey).get()
                    : glideClient.get(gsKey).get();
            return gs != null ? gs.getBytes() : null;
        });
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping() {
        return AsyncHelper.timed(() -> isClusterMode
                ? glideClusterClient.ping().get()
                : glideClient.ping().get());
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping(byte[] message) {
        GlideString gsMessage = GlideString.of(message);
        return AsyncHelper.timed(() -> {
            GlideString gs = isClusterMode
                    ? glideClusterClient.ping(gsMessage).get()
                    : glideClient.ping(gsMessage).get();
            return gs.toString();
        });
    }

    @Override
    public CompletableFuture<TimedResult<Long>> del(byte[]... keys) {
        GlideString[] gsKeys = new GlideString[keys.length];
        for (int i = 0; i < keys.length; i++) {
            gsKeys[i] = GlideString.of(keys[i]);
        }
        return AsyncHelper.timed(() -> isClusterMode
                ? glideClusterClient.del(gsKeys).get()
                : glideClient.del(gsKeys).get());
    }

    @Override
    public CompletableFuture<TimedResult<Void>> flushDb() {
        return AsyncHelper.timedVoid(() -> {
            if (isClusterMode) {
                glideClusterClient.flushdb().get();
            } else {
                glideClient.flushdb().get();
            }
        });
    }

    @Override
    public void close() {
        logger.info("Closing ValkeyGlide connection");
        connected = false;
        
        if (glideClient != null) {
            try {
                glideClient.close();
            } catch (Exception e) {
                logger.warn("Error closing GlideClient: {}", e.getMessage());
            }
            glideClient = null;
        }
        
        if (glideClusterClient != null) {
            try {
                glideClusterClient.close();
            } catch (Exception e) {
                logger.warn("Error closing GlideClusterClient: {}", e.getMessage());
            }
            glideClusterClient = null;
        }
    }
}