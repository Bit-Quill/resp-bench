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

import io.lettuce.core.RedisClient;
import io.valkey.javabenchmark.client.AsyncHelper;
import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.client.TimedResult;
import io.valkey.javabenchmark.config.DriverConfig;
import io.valkey.javabenchmark.util.VersionHelper;
import redis.clients.jedis.Jedis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring Data Redis implementation of BenchmarkClient.
 * Uses RedisTemplate for all operations, which is the standard API surface
 * that real Spring applications use. This includes the full overhead of
 * connection pool management, serialization, and template callback execution.
 * 
 * <p>All instances share a single ConnectionFactory and RedisTemplate (via a static holder),
 * modeling how a real Spring application uses one singleton RedisTemplate shared across
 * all concurrent request threads. The first instance to connect creates the shared state;
 * the last instance to close destroys it.</p>
 * 
 * Supports Jedis and Lettuce as underlying drivers via secondary_driver_id configuration.
 * Default: Jedis if secondary_driver_id is not specified.
 *
 * @author Ilia Kolominsky
 */
public class SpringDataRedisBenchmarkClient implements BenchmarkClient {

    private static final Logger logger = LoggerFactory.getLogger(SpringDataRedisBenchmarkClient.class);

    /** Shared state across all instances — models a Spring singleton RedisTemplate. */
    private static final AtomicReference<SharedState> sharedStateRef = new AtomicReference<>();
    private static final AtomicInteger refCount = new AtomicInteger(0);
    private static final Object lock = new Object();

    private boolean connected;
    private String secondaryDriverId;

    /**
     * Holds the shared ConnectionFactory, RedisTemplate, and ExecutorService
     * that are shared across all SpringDataRedisBenchmarkClient instances.
     */
    private static class SharedState {
        final RedisConnectionFactory connectionFactory;
        final RedisTemplate<byte[], byte[]> template;
        final ExecutorService executor;
        final String secondaryDriverId;

        SharedState(RedisConnectionFactory connectionFactory, RedisTemplate<byte[], byte[]> template,
                    ExecutorService executor, String secondaryDriverId) {
            this.connectionFactory = connectionFactory;
            this.template = template;
            this.executor = executor;
            this.secondaryDriverId = secondaryDriverId;
        }
    }

    @Override
    public String getDriverId() {
        return "spring-data-redis";
    }

    @Override
    public String getDescription() {
        return "Spring Data Redis (requires secondary_driver_id: jedis or lettuce)";
    }

    @Override
    public String getDriverVersion() {
        return VersionHelper.getVersion(RedisConnectionFactory.class, 
                "org.springframework.data", "spring-data-redis");
    }

    @Override
    public String getSecondaryDriverVersion() {
        if (secondaryDriverId == null) {
            return null;
        }
        return switch (secondaryDriverId.toLowerCase()) {
            case "jedis" -> VersionHelper.getVersion(Jedis.class, "redis.clients", "jedis");
            case "lettuce" -> VersionHelper.getVersion(RedisClient.class, "io.lettuce", "lettuce-core");
            default -> null;
        };
    }

    @Override
    public void connect(String host, int port, DriverConfig driverConfig) throws ClientException {
        this.secondaryDriverId = driverConfig.getSecondaryDriverId();
        if (secondaryDriverId == null || secondaryDriverId.isEmpty()) {
            secondaryDriverId = "jedis";
        }
        
        synchronized (lock) {
            int refs = refCount.incrementAndGet();
            
            if (sharedStateRef.get() != null) {
                // Reuse existing shared state
                connected = true;
                logger.debug("Reusing shared RedisTemplate (ref count: {})", refs);
                return;
            }
            
            // First instance — create shared state
            logger.info("Creating shared Spring Data Redis infrastructure for {}:{} (cluster={}, secondary_driver={})", 
                    host, port, driverConfig.isClusterMode(), secondaryDriverId);
            
            try {
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                
                RedisConnectionFactory connectionFactory = switch (secondaryDriverId.toLowerCase()) {
                    case "jedis" -> createJedisConnectionFactory(host, port, driverConfig);
                    case "lettuce" -> createLettuceConnectionFactory(host, port, driverConfig);
                    default -> throw new ClientException("Unsupported secondary_driver_id for spring-data-redis: " 
                            + secondaryDriverId + ". Supported values: jedis, lettuce");
                };
                
                // Create and configure RedisTemplate with byte[] serializers
                RedisTemplate<byte[], byte[]> template = new RedisTemplate<>();
                template.setConnectionFactory(connectionFactory);
                template.setKeySerializer(RedisSerializer.byteArray());
                template.setValueSerializer(RedisSerializer.byteArray());
                template.setHashKeySerializer(RedisSerializer.byteArray());
                template.setHashValueSerializer(RedisSerializer.byteArray());
                template.afterPropertiesSet();
                
                // Test connection via template
                String pingResponse = template.execute((RedisCallback<String>) RedisConnection::ping);
                if (!"PONG".equals(pingResponse)) {
                    throw new ClientException("Unexpected ping response: " + pingResponse);
                }
                
                sharedStateRef.set(new SharedState(connectionFactory, template, executor, secondaryDriverId));
                connected = true;
                logger.info("Spring Data Redis shared infrastructure created using {} (via RedisTemplate)", secondaryDriverId);
                
            } catch (ClientException e) {
                refCount.decrementAndGet();
                throw e;
            } catch (Exception e) {
                refCount.decrementAndGet();
                throw new ClientException("Failed to connect Spring Data Redis to " + host + ":" + port, e);
            }
        }
    }

    private JedisConnectionFactory createJedisConnectionFactory(String host, int port, DriverConfig driverConfig) {
        JedisClientConfiguration.JedisClientConfigurationBuilder configBuilder =
                JedisClientConfiguration.builder();
        
        if (driverConfig.isTlsEnabled()) {
            configBuilder.useSsl();
        }
        
        // Enable connection pooling if configured (matches Spring Boot default behavior)
        if (driverConfig.isUsePooling()) {
            redis.clients.jedis.JedisPoolConfig poolConfig = new redis.clients.jedis.JedisPoolConfig();
            poolConfig.setMaxTotal(driverConfig.getPoolSize());
            poolConfig.setMaxIdle(driverConfig.getPoolSize());
            configBuilder.usePooling().poolConfig(poolConfig);
            logger.info("Jedis connection pooling enabled (maxTotal={}, maxIdle={})", 
                    driverConfig.getPoolSize(), driverConfig.getPoolSize());
        }
        
        JedisClientConfiguration clientConfig = configBuilder.build();
        
        JedisConnectionFactory jedisFactory;
        if (driverConfig.isClusterMode()) {
            RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration();
            clusterConfig.addClusterNode(new RedisNode(host, port));
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    clusterConfig.setUsername(auth.getUsername());
                }
                clusterConfig.setPassword(RedisPassword.of(auth.getPassword()));
            }
            jedisFactory = new JedisConnectionFactory(clusterConfig, clientConfig);
        } else {
            RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration(host, port);
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    standaloneConfig.setUsername(auth.getUsername());
                }
                standaloneConfig.setPassword(RedisPassword.of(auth.getPassword()));
            }
            jedisFactory = new JedisConnectionFactory(standaloneConfig, clientConfig);
        }
        
        jedisFactory.afterPropertiesSet();
        return jedisFactory;
    }

    private LettuceConnectionFactory createLettuceConnectionFactory(String host, int port, DriverConfig driverConfig) {
        LettuceClientConfiguration clientConfig;
        
        if (driverConfig.isUsePooling()) {
            // Use LettucePoolingClientConfiguration to pool StatefulRedisConnection instances
            // (matches Spring Boot behavior when spring.redis.lettuce.pool.enabled=true)
            @SuppressWarnings("rawtypes")
            org.apache.commons.pool2.impl.GenericObjectPoolConfig poolConfig = 
                    new org.apache.commons.pool2.impl.GenericObjectPoolConfig();
            poolConfig.setMaxTotal(driverConfig.getPoolSize());
            poolConfig.setMaxIdle(driverConfig.getPoolSize());
            poolConfig.setMinIdle(Math.min(4, driverConfig.getPoolSize()));
            
            @SuppressWarnings("unchecked")
            LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder poolBuilder =
                    LettucePoolingClientConfiguration.builder().poolConfig(poolConfig);
            if (driverConfig.isTlsEnabled()) {
                poolBuilder.useSsl();
            }
            clientConfig = poolBuilder.build();
            logger.info("Lettuce connection pooling enabled (maxTotal={}, maxIdle={})", 
                    driverConfig.getPoolSize(), driverConfig.getPoolSize());
        } else {
            LettuceClientConfiguration.LettuceClientConfigurationBuilder configBuilder =
                    LettuceClientConfiguration.builder();
            if (driverConfig.isTlsEnabled()) {
                configBuilder.useSsl();
            }
            clientConfig = configBuilder.build();
        }
        
        LettuceConnectionFactory lettuceFactory;
        if (driverConfig.isClusterMode()) {
            RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration();
            clusterConfig.addClusterNode(new RedisNode(host, port));
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    clusterConfig.setUsername(auth.getUsername());
                }
                clusterConfig.setPassword(RedisPassword.of(auth.getPassword()));
            }
            lettuceFactory = new LettuceConnectionFactory(clusterConfig, clientConfig);
        } else {
            RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration(host, port);
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    standaloneConfig.setUsername(auth.getUsername());
                }
                standaloneConfig.setPassword(RedisPassword.of(auth.getPassword()));
            }
            lettuceFactory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        }
        
        // Configure shareNativeConnection from driver config.
        // When true (default), all regular operations use a single shared connection,
        // bypassing the pool. Set to false to use pooled connections for all operations.
        boolean shareNative = driverConfig.isShareNativeConnection();
        lettuceFactory.setShareNativeConnection(shareNative);
        logger.info("Lettuce shareNativeConnection={} (pooling={})", shareNative, driverConfig.isUsePooling());

        lettuceFactory.afterPropertiesSet();
        return lettuceFactory;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    private SharedState getShared() {
        return sharedStateRef.get();
    }

    @Override
    public CompletableFuture<TimedResult<Void>> set(byte[] key, byte[] value) {
        SharedState shared = getShared();
        return AsyncHelper.timedVoid(() -> shared.template.opsForValue().set(key, value));
    }

    @Override
    public CompletableFuture<TimedResult<byte[]>> get(byte[] key) {
        SharedState shared = getShared();
        return AsyncHelper.timed(() -> shared.template.opsForValue().get(key));
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping() {
        SharedState shared = getShared();
        return AsyncHelper.timed(() -> shared.template.execute((RedisCallback<String>) RedisConnection::ping));
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping(byte[] message) {
        SharedState shared = getShared();
        return AsyncHelper.timed(() -> shared.template.execute((RedisCallback<String>) connection -> {
            byte[] echoResult = connection.echo(message);
            return echoResult != null ? new String(echoResult) : "PONG";
        }));
    }

    @Override
    public CompletableFuture<TimedResult<Long>> del(byte[]... keys) {
        SharedState shared = getShared();
        return AsyncHelper.timed(() -> shared.template.delete(Arrays.asList(keys)));
    }

    @Override
    public CompletableFuture<TimedResult<Void>> flushDb() {
        SharedState shared = getShared();
        return AsyncHelper.timedVoid(() -> shared.template.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        }));
    }

    @Override
    public void close() {
        connected = false;
        
        synchronized (lock) {
            int remaining = refCount.decrementAndGet();
            
            if (remaining > 0) {
                logger.debug("Spring Data Redis client closed (remaining refs: {})", remaining);
                return;
            }
            
            // Last instance — destroy shared state
            SharedState shared = sharedStateRef.getAndSet(null);
            if (shared == null) {
                return;
            }
            
            logger.info("Closing shared Spring Data Redis infrastructure ({})", shared.secondaryDriverId);
            
            if (shared.connectionFactory instanceof JedisConnectionFactory jedisFactory) {
                try {
                    jedisFactory.destroy();
                } catch (Exception e) {
                    logger.warn("Error destroying Jedis connection factory: {}", e.getMessage());
                }
            } else if (shared.connectionFactory instanceof LettuceConnectionFactory lettuceFactory) {
                try {
                    lettuceFactory.destroy();
                } catch (Exception e) {
                    logger.warn("Error destroying Lettuce connection factory: {}", e.getMessage());
                }
            }
            
            shared.executor.shutdown();
        }
    }
}
