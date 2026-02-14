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

import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.client.TimedResult;
import io.valkey.javabenchmark.config.DriverConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Jedis implementation of BenchmarkClient.
 *
 * @author Ilia Kolominsky
 */
public class JedisBenchmarkClient implements BenchmarkClient {

    private static final Logger logger = LoggerFactory.getLogger(JedisBenchmarkClient.class);

    private JedisPooled jedis;
    private JedisCluster jedisCluster;
    private boolean isClusterMode;
    private boolean connected;
    private ExecutorService executor;

    @Override
    public String getDriverId() {
        return "jedis";
    }

    @Override
    public String getDescription() {
        return "Jedis Redis/Valkey client";
    }

    @Override
    public String getDriverVersion() {
        // Get Jedis version from package implementation version
        Package pkg = Jedis.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version != null ? version : "unknown";
    }

    @Override
    public void connect(String host, int port, DriverConfig driverConfig) throws ClientException {
        logger.info("Connecting Jedis to {}:{} (cluster={})", host, port, driverConfig.isClusterMode());
        
        try {
            this.isClusterMode = driverConfig.isClusterMode();
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            
            DefaultJedisClientConfig.Builder configBuilder = DefaultJedisClientConfig.builder();
            
            // Configure TLS if enabled
            if (driverConfig.isTlsEnabled()) {
                configBuilder.ssl(true);
            }
            
            // Configure authentication if provided
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    configBuilder.user(auth.getUsername());
                }
                configBuilder.password(auth.getPassword());
            }
            
            JedisClientConfig clientConfig = configBuilder.build();
            
            if (isClusterMode) {
                HostAndPort hostAndPort = new HostAndPort(host, port);
                jedisCluster = new JedisCluster(Collections.singleton(hostAndPort), clientConfig);
            } else {
                HostAndPort hostAndPort = new HostAndPort(host, port);
                jedis = new JedisPooled(hostAndPort, clientConfig);
            }
            
            // Test connection
            String pingResponse = isClusterMode ? jedisCluster.ping() : jedis.ping();
            if (!"PONG".equals(pingResponse)) {
                throw new ClientException("Unexpected ping response: " + pingResponse);
            }
            
            connected = true;
            logger.info("Jedis connected successfully");
            
        } catch (Exception e) {
            throw new ClientException("Failed to connect Jedis to " + host + ":" + port, e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public CompletableFuture<TimedResult<Void>> set(byte[] key, byte[] value) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            if (isClusterMode) {
                jedisCluster.set(key, value);
            } else {
                jedis.set(key, value);
            }
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.ofVoid(latencyMicros);
        }, executor);
    }

    @Override
    public CompletableFuture<TimedResult<byte[]>> get(byte[] key) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            byte[] result;
            if (isClusterMode) {
                result = jedisCluster.get(key);
            } else {
                result = jedis.get(key);
            }
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.of(result, latencyMicros);
        }, executor);
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping() {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            String result;
            if (isClusterMode) {
                result = jedisCluster.ping();
            } else {
                result = jedis.ping();
            }
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.of(result, latencyMicros);
        }, executor);
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping(byte[] message) {
        // Jedis 5.x does not support ping with message, so we just do regular ping
        // and return the expected message as the response for compatibility
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            if (isClusterMode) {
                jedisCluster.ping();
            } else {
                jedis.ping();
            }
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.of(new String(message), latencyMicros);
        }, executor);
    }

    @Override
    public CompletableFuture<TimedResult<Long>> del(byte[]... keys) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            Long result;
            if (isClusterMode) {
                result = jedisCluster.del(keys);
            } else {
                result = jedis.del(keys);
            }
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.of(result, latencyMicros);
        }, executor);
    }

    @Override
    public CompletableFuture<TimedResult<Void>> flushDb() {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            if (isClusterMode) {
                jedisCluster.flushDB();
            } else {
                jedis.flushDB();
            }
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.ofVoid(latencyMicros);
        }, executor);
    }

    @Override
    public void close() {
        logger.info("Closing Jedis connection");
        connected = false;
        
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception e) {
                logger.warn("Error closing Jedis: {}", e.getMessage());
            }
            jedis = null;
        }
        
        if (jedisCluster != null) {
            try {
                jedisCluster.close();
            } catch (Exception e) {
                logger.warn("Error closing JedisCluster: {}", e.getMessage());
            }
            jedisCluster = null;
        }
        
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }
}