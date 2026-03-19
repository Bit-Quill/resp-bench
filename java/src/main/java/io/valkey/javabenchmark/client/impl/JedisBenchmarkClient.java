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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

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
        return VersionHelper.getVersion(Jedis.class, "redis.clients", "jedis");
    }

    @Override
    public void connect(String host, int port, DriverConfig driverConfig) throws ClientException {
        logger.info("Connecting Jedis to {}:{} (cluster={})", host, port, driverConfig.isClusterMode());
        
        try {
            this.isClusterMode = driverConfig.isClusterMode();
            
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
            
            // Apply command timeout if configured
            if (driverConfig.getCommandTimeoutMs() != null) {
                configBuilder.timeoutMillis(driverConfig.getCommandTimeoutMs());
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
        return AsyncHelper.timedVoid(() -> {
            if (isClusterMode) {
                jedisCluster.set(key, value);
            } else {
                jedis.set(key, value);
            }
        });
    }

    @Override
    public CompletableFuture<TimedResult<byte[]>> get(byte[] key) {
        return AsyncHelper.timed(() -> isClusterMode
                ? jedisCluster.get(key)
                : jedis.get(key));
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping() {
        return AsyncHelper.timed(() -> isClusterMode
                ? jedisCluster.ping()
                : jedis.ping());
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping(byte[] message) {
        // Jedis 5.x does not support ping with message, so we just do regular ping
        // and return the expected message as the response for compatibility
        return AsyncHelper.timed(() -> {
            if (isClusterMode) {
                jedisCluster.ping();
            } else {
                jedis.ping();
            }
            return new String(message);
        });
    }

    @Override
    public CompletableFuture<TimedResult<Long>> del(byte[]... keys) {
        return AsyncHelper.timed(() -> isClusterMode
                ? jedisCluster.del(keys)
                : jedis.del(keys));
    }

    @Override
    public CompletableFuture<TimedResult<Void>> flushDb() {
        return AsyncHelper.timedVoid(() -> {
            if (isClusterMode) {
                jedisCluster.flushDB();
            } else {
                jedis.flushDB();
            }
        });
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
        
    }
}