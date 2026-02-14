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

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.client.TimedResult;
import io.valkey.javabenchmark.config.DriverConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Lettuce implementation of BenchmarkClient.
 *
 * @author Ilia Kolominsky
 */
public class LettuceBenchmarkClient implements BenchmarkClient {

    private static final Logger logger = LoggerFactory.getLogger(LettuceBenchmarkClient.class);

    private RedisClient redisClient;
    private RedisClusterClient clusterClient;
    private StatefulRedisConnection<byte[], byte[]> connection;
    private StatefulRedisClusterConnection<byte[], byte[]> clusterConnection;
    private RedisAsyncCommands<byte[], byte[]> asyncCommands;
    private RedisAdvancedClusterAsyncCommands<byte[], byte[]> clusterAsyncCommands;
    private boolean isClusterMode;
    private boolean connected;

    @Override
    public String getDriverId() {
        return "lettuce";
    }

    @Override
    public String getDescription() {
        return "Lettuce async Redis/Valkey client";
    }

    @Override
    public String getDriverVersion() {
        // Get Lettuce version from package implementation version
        Package pkg = RedisClient.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version != null ? version : "unknown";
    }

    @Override
    public void connect(String host, int port, DriverConfig driverConfig) throws ClientException {
        logger.info("Connecting Lettuce to {}:{} (cluster={})", host, port, driverConfig.isClusterMode());
        
        try {
            this.isClusterMode = driverConfig.isClusterMode();
            
            String scheme = driverConfig.isTlsEnabled() ? "rediss" : "redis";
            
            RedisURI.Builder uriBuilder = RedisURI.builder()
                    .withHost(host)
                    .withPort(port)
                    .withTimeout(Duration.ofSeconds(10));
            
            if (driverConfig.isTlsEnabled()) {
                uriBuilder.withSsl(true);
            }
            
            // Configure authentication if provided
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    uriBuilder.withAuthentication(auth.getUsername(), auth.getPassword().toCharArray());
                } else {
                    uriBuilder.withPassword(auth.getPassword().toCharArray());
                }
            }
            
            RedisURI redisUri = uriBuilder.build();
            
            if (isClusterMode) {
                clusterClient = RedisClusterClient.create(redisUri);
                clusterConnection = clusterClient.connect(ByteArrayCodec.INSTANCE);
                clusterAsyncCommands = clusterConnection.async();
            } else {
                redisClient = RedisClient.create(redisUri);
                connection = redisClient.connect(ByteArrayCodec.INSTANCE);
                asyncCommands = connection.async();
            }
            
            // Test connection
            String pingResponse;
            if (isClusterMode) {
                pingResponse = clusterAsyncCommands.ping().get();
            } else {
                pingResponse = asyncCommands.ping().get();
            }
            
            if (!"PONG".equals(pingResponse)) {
                throw new ClientException("Unexpected ping response: " + pingResponse);
            }
            
            connected = true;
            logger.info("Lettuce connected successfully");
            
        } catch (Exception e) {
            throw new ClientException("Failed to connect Lettuce to " + host + ":" + port, e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public CompletableFuture<TimedResult<Void>> set(byte[] key, byte[] value) {
        long start = System.nanoTime();
        CompletableFuture<String> future = isClusterMode 
                ? clusterAsyncCommands.set(key, value).toCompletableFuture()
                : asyncCommands.set(key, value).toCompletableFuture();
        return future.thenApply(r -> {
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.ofVoid(latencyMicros);
        });
    }

    @Override
    public CompletableFuture<TimedResult<byte[]>> get(byte[] key) {
        long start = System.nanoTime();
        CompletableFuture<byte[]> future = isClusterMode 
                ? clusterAsyncCommands.get(key).toCompletableFuture()
                : asyncCommands.get(key).toCompletableFuture();
        return future.thenApply(result -> {
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.of(result, latencyMicros);
        });
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping() {
        long start = System.nanoTime();
        CompletableFuture<String> future = isClusterMode 
                ? clusterAsyncCommands.ping().toCompletableFuture()
                : asyncCommands.ping().toCompletableFuture();
        return future.thenApply(result -> {
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.of(result, latencyMicros);
        });
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping(byte[] message) {
        // Lettuce doesn't have a direct ping with message for byte arrays
        // Use echo as a workaround
        long start = System.nanoTime();
        CompletableFuture<byte[]> future = isClusterMode 
                ? clusterAsyncCommands.echo(message).toCompletableFuture()
                : asyncCommands.echo(message).toCompletableFuture();
        return future.thenApply(b -> {
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.of(new String(b), latencyMicros);
        });
    }

    @Override
    public CompletableFuture<TimedResult<Long>> del(byte[]... keys) {
        long start = System.nanoTime();
        CompletableFuture<Long> future = isClusterMode 
                ? clusterAsyncCommands.del(keys).toCompletableFuture()
                : asyncCommands.del(keys).toCompletableFuture();
        return future.thenApply(result -> {
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.of(result, latencyMicros);
        });
    }

    @Override
    public CompletableFuture<TimedResult<Void>> flushDb() {
        long start = System.nanoTime();
        CompletableFuture<String> future = isClusterMode 
                ? clusterAsyncCommands.flushdb().toCompletableFuture()
                : asyncCommands.flushdb().toCompletableFuture();
        return future.thenApply(r -> {
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.ofVoid(latencyMicros);
        });
    }

    @Override
    public void close() {
        logger.info("Closing Lettuce connection");
        connected = false;
        
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                logger.warn("Error closing Lettuce connection: {}", e.getMessage());
            }
            connection = null;
        }
        
        if (clusterConnection != null) {
            try {
                clusterConnection.close();
            } catch (Exception e) {
                logger.warn("Error closing Lettuce cluster connection: {}", e.getMessage());
            }
            clusterConnection = null;
        }
        
        if (redisClient != null) {
            try {
                redisClient.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down Lettuce client: {}", e.getMessage());
            }
            redisClient = null;
        }
        
        if (clusterClient != null) {
            try {
                clusterClient.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down Lettuce cluster client: {}", e.getMessage());
            }
            clusterClient = null;
        }
    }
}