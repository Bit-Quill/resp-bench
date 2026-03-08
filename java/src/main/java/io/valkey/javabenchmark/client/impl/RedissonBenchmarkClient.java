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

import io.valkey.javabenchmark.client.AsyncHelper;
import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.client.TimedResult;
import io.valkey.javabenchmark.config.DriverConfig;
import io.valkey.javabenchmark.util.VersionHelper;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redisson implementation of BenchmarkClient.
 * 
 * <p>Uses Redisson's low-level command executor for optimal performance,
 * bypassing the high-level distributed object APIs which have significant overhead.</p>
 *
 * @author Ilia Kolominsky
 */
public class RedissonBenchmarkClient implements BenchmarkClient {

    private static final Logger logger = LoggerFactory.getLogger(RedissonBenchmarkClient.class);

    private RedissonClient redisson;
    private CommandAsyncExecutor commandExecutor;
    private boolean connected;

    @Override
    public String getDriverId() {
        return "redisson";
    }

    @Override
    public String getDescription() {
        return "Redisson async Redis/Valkey client";
    }

    @Override
    public String getDriverVersion() {
        return VersionHelper.getVersion(Redisson.class, "org.redisson", "redisson");
    }

    @Override
    public void connect(String host, int port, DriverConfig driverConfig) throws ClientException {
        logger.info("Connecting Redisson to {}:{} (cluster={})", host, port, driverConfig.isClusterMode());
        
        try {
            Config config = new Config();
            // Use StringCodec for simpler key handling
            config.setCodec(StringCodec.INSTANCE);
            // Reduce Netty threads to avoid overhead
            config.setNettyThreads(0); // Use Netty defaults based on CPU
            config.setThreads(0); // Use defaults
            
            String scheme = driverConfig.isTlsEnabled() ? "rediss" : "redis";
            String address = scheme + "://" + host + ":" + port;
            
            if (driverConfig.isClusterMode()) {
                var clusterConfig = config.useClusterServers()
                        .addNodeAddress(address)
                        .setTimeout(10000)
                        .setConnectTimeout(10000)
                        .setRetryAttempts(1)
                        .setRetryInterval(100);
                
                if (driverConfig.isTlsEnabled()) {
                    clusterConfig.setSslEnableEndpointIdentification(false);
                }
                
                if (driverConfig.hasAuth()) {
                    DriverConfig.AuthConfig auth = driverConfig.getAuth();
                    if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                        clusterConfig.setUsername(auth.getUsername());
                    }
                    clusterConfig.setPassword(auth.getPassword());
                }
            } else {
                var singleConfig = config.useSingleServer()
                        .setAddress(address)
                        .setTimeout(10000)
                        .setConnectTimeout(10000)
                        .setRetryAttempts(1)
                        .setRetryInterval(100)
                        .setConnectionMinimumIdleSize(1)
                        .setConnectionPoolSize(64);
                
                if (driverConfig.isTlsEnabled()) {
                    singleConfig.setSslEnableEndpointIdentification(false);
                }
                
                if (driverConfig.hasAuth()) {
                    DriverConfig.AuthConfig auth = driverConfig.getAuth();
                    if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                        singleConfig.setUsername(auth.getUsername());
                    }
                    singleConfig.setPassword(auth.getPassword());
                }
            }
            
            redisson = Redisson.create(config);
            
            // Get the internal command executor for low-level operations
            commandExecutor = ((Redisson) redisson).getCommandExecutor();
            
            // Test connection with PING
            String pingResponse = pingSync();
            if (!"PONG".equals(pingResponse)) {
                throw new ClientException("Unexpected ping response: " + pingResponse);
            }
            
            connected = true;
            logger.info("Redisson connected successfully");
            
        } catch (Exception e) {
            throw new ClientException("Failed to connect Redisson to " + host + ":" + port, e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public CompletableFuture<TimedResult<Void>> set(byte[] key, byte[] value) {
        String keyStr = new String(key, StandardCharsets.UTF_8);
        String valueStr = new String(value, StandardCharsets.UTF_8);
        return AsyncHelper.timedVoid(() ->
                commandExecutor.writeAsync(keyStr, StringCodec.INSTANCE, RedisCommands.SET, keyStr, valueStr).get()
        );
    }

    @Override
    public CompletableFuture<TimedResult<byte[]>> get(byte[] key) {
        String keyStr = new String(key, StandardCharsets.UTF_8);
        return AsyncHelper.timed(() -> {
            Object result = commandExecutor.readAsync(keyStr, StringCodec.INSTANCE, RedisCommands.GET, keyStr).get();
            return result != null ? result.toString().getBytes(StandardCharsets.UTF_8) : null;
        });
    }

    /**
     * Synchronous PING for connection testing.
     */
    private String pingSync() {
        try {
            RFuture<String> future = commandExecutor.readAsync(
                    (String) null, 
                    StringCodec.INSTANCE, 
                    RedisCommands.PING
            );
            return future.toCompletableFuture().get();
        } catch (Exception e) {
            logger.warn("PING failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping() {
        return AsyncHelper.timed(() ->
                (String) commandExecutor.readAsync((String) null, StringCodec.INSTANCE, RedisCommands.PING).get()
        );
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping(byte[] message) {
        String messageStr = new String(message, StandardCharsets.UTF_8);
        return AsyncHelper.timed(() ->
                (String) commandExecutor.readAsync((String) null, StringCodec.INSTANCE, RedisCommands.PING, messageStr).get()
        );
    }

    @Override
    public CompletableFuture<TimedResult<Long>> del(byte[]... keys) {
        String[] stringKeys = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            stringKeys[i] = new String(keys[i], StandardCharsets.UTF_8);
        }
        return AsyncHelper.timed(() ->
                (Long) commandExecutor.writeAsync(stringKeys[0], StringCodec.INSTANCE, RedisCommands.DEL, (Object[]) stringKeys).get()
        );
    }

    @Override
    public CompletableFuture<TimedResult<Void>> flushDb() {
        return AsyncHelper.timedVoid(() -> redisson.getKeys().flushdbAsync().get());
    }

    @Override
    public void close() {
        logger.info("Closing Redisson connection");
        connected = false;
        
        if (redisson != null) {
            try {
                // Use shutdown with 0 quiet period to avoid 2-second delays per connection
                // Default shutdown() waits 2s quiet period + 15s timeout per instance
                redisson.shutdown(0, 2, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Error shutting down Redisson: {}", e.getMessage());
            }
            redisson = null;
            commandExecutor = null;
        }
    }
}