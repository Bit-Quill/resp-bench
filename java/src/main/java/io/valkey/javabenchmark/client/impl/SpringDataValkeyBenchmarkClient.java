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
import io.lettuce.core.RedisClient;
import io.valkey.javabenchmark.client.AsyncHelper;
import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.client.TimedResult;
import io.valkey.javabenchmark.config.DriverConfig;
import io.valkey.javabenchmark.util.VersionHelper;
import redis.clients.jedis.Jedis;
import io.valkey.springframework.data.valkey.connection.*;
import io.valkey.springframework.data.valkey.connection.jedis.JedisClientConfiguration;
import io.valkey.springframework.data.valkey.connection.jedis.JedisConnectionFactory;
import io.valkey.springframework.data.valkey.connection.lettuce.LettuceClientConfiguration;
import io.valkey.springframework.data.valkey.connection.lettuce.LettuceConnectionFactory;
import io.valkey.springframework.data.valkey.connection.valkeyglide.ValkeyGlideClientConfiguration;
import io.valkey.springframework.data.valkey.connection.valkeyglide.ValkeyGlideConnectionFactory;
import io.valkey.springframework.data.valkey.core.ValkeyCallback;
import io.valkey.springframework.data.valkey.core.ValkeyTemplate;
import io.valkey.springframework.data.valkey.serializer.ValkeySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring Data Valkey implementation of BenchmarkClient.
 * Uses ValkeyTemplate for all operations, which is the standard API surface
 * that real Spring applications use. This includes the full overhead of
 * connection pool management, serialization, and template callback execution.
 * 
 * <p>All instances share a single ConnectionFactory and ValkeyTemplate (via a static holder),
 * modeling how a real Spring application uses one singleton ValkeyTemplate shared across
 * all concurrent request threads. The first instance to connect creates the shared state;
 * the last instance to close destroys it.</p>
 * 
 * Supports Jedis, Lettuce, and ValkeyGlide as underlying drivers via secondary_driver_id configuration.
 * Default: ValkeyGlide if secondary_driver_id is not specified.
 *
 * @author Ilia Kolominsky
 */
public class SpringDataValkeyBenchmarkClient implements BenchmarkClient {

    private static final Logger logger = LoggerFactory.getLogger(SpringDataValkeyBenchmarkClient.class);

    /** Shared state across all instances — models a Spring singleton ValkeyTemplate. */
    private static final AtomicReference<SharedState> sharedStateRef = new AtomicReference<>();
    private static final AtomicInteger refCount = new AtomicInteger(0);
    private static final Object lock = new Object();

    private boolean connected;
    private String secondaryDriverId;

    /**
     * Holds the shared ConnectionFactory, ValkeyTemplate, and ExecutorService
     * that are shared across all SpringDataValkeyBenchmarkClient instances.
     */
    private static class SharedState {
        final ValkeyConnectionFactory connectionFactory;
        final ValkeyTemplate<byte[], byte[]> template;
        final ExecutorService executor;
        final String secondaryDriverId;

        SharedState(ValkeyConnectionFactory connectionFactory, ValkeyTemplate<byte[], byte[]> template,
                    ExecutorService executor, String secondaryDriverId) {
            this.connectionFactory = connectionFactory;
            this.template = template;
            this.executor = executor;
            this.secondaryDriverId = secondaryDriverId;
        }
    }

    @Override
    public String getDriverId() {
        return "spring-data-valkey";
    }

    @Override
    public String getDescription() {
        return "Spring Data Valkey (requires secondary_driver_id: jedis, lettuce, or valkey-glide)";
    }

    @Override
    public String getDriverVersion() {
        return VersionHelper.getVersion(ValkeyConnectionFactory.class, 
                "io.valkey.springframework.data", "spring-data-valkey");
    }

    @Override
    public String getSecondaryDriverVersion() {
        if (secondaryDriverId == null) {
            return null;
        }
        return switch (secondaryDriverId.toLowerCase()) {
            case "jedis" -> VersionHelper.getVersion(Jedis.class, "redis.clients", "jedis");
            case "lettuce" -> VersionHelper.getVersion(RedisClient.class, "io.lettuce", "lettuce-core");
            case "valkey-glide" -> VersionHelper.getVersion(GlideClient.class, "io.valkey", "valkey-glide");
            default -> null;
        };
    }

    @Override
    public void connect(String host, int port, DriverConfig driverConfig) throws ClientException {
        this.secondaryDriverId = driverConfig.getSecondaryDriverId();
        if (secondaryDriverId == null || secondaryDriverId.isEmpty()) {
            secondaryDriverId = "valkey-glide";
        }
        
        synchronized (lock) {
            int refs = refCount.incrementAndGet();
            
            if (sharedStateRef.get() != null) {
                // Reuse existing shared state
                connected = true;
                logger.debug("Reusing shared ValkeyTemplate (ref count: {})", refs);
                return;
            }
            
            // First instance — create shared state
            logger.info("Creating shared Spring Data Valkey infrastructure for {}:{} (cluster={}, secondary_driver={})", 
                    host, port, driverConfig.isClusterMode(), secondaryDriverId);
            
            try {
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                
                ValkeyConnectionFactory connectionFactory = switch (secondaryDriverId.toLowerCase()) {
                    case "jedis" -> createJedisConnectionFactory(host, port, driverConfig);
                    case "lettuce" -> createLettuceConnectionFactory(host, port, driverConfig);
                    case "valkey-glide" -> createValkeyGlideConnectionFactory(host, port, driverConfig);
                    default -> throw new ClientException("Unsupported secondary_driver_id for spring-data-valkey: " 
                            + secondaryDriverId + ". Supported values: jedis, lettuce, valkey-glide");
                };
                
                // Create and configure ValkeyTemplate with byte[] serializers
                ValkeyTemplate<byte[], byte[]> template = new ValkeyTemplate<>();
                template.setConnectionFactory(connectionFactory);
                template.setKeySerializer(ValkeySerializer.byteArray());
                template.setValueSerializer(ValkeySerializer.byteArray());
                template.setHashKeySerializer(ValkeySerializer.byteArray());
                template.setHashValueSerializer(ValkeySerializer.byteArray());
                template.afterPropertiesSet();
                
                // Test connection via template
                String pingResponse = template.execute((ValkeyCallback<String>) ValkeyConnection::ping);
                if (!"PONG".equals(pingResponse)) {
                    throw new ClientException("Unexpected ping response: " + pingResponse);
                }
                
                sharedStateRef.set(new SharedState(connectionFactory, template, executor, secondaryDriverId));
                connected = true;
                logger.info("Spring Data Valkey shared infrastructure created using {} (via ValkeyTemplate)", secondaryDriverId);
                
            } catch (ClientException e) {
                refCount.decrementAndGet();
                throw e;
            } catch (Exception e) {
                refCount.decrementAndGet();
                throw new ClientException("Failed to connect Spring Data Valkey to " + host + ":" + port, e);
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
            ValkeyClusterConfiguration clusterConfig = new ValkeyClusterConfiguration();
            clusterConfig.addClusterNode(new ValkeyNode(host, port));
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    clusterConfig.setUsername(auth.getUsername());
                }
                clusterConfig.setPassword(ValkeyPassword.of(auth.getPassword()));
            }
            jedisFactory = new JedisConnectionFactory(clusterConfig, clientConfig);
        } else {
            ValkeyStandaloneConfiguration standaloneConfig = new ValkeyStandaloneConfiguration(host, port);
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    standaloneConfig.setUsername(auth.getUsername());
                }
                standaloneConfig.setPassword(ValkeyPassword.of(auth.getPassword()));
            }
            jedisFactory = new JedisConnectionFactory(standaloneConfig, clientConfig);
        }
        
        jedisFactory.afterPropertiesSet();
        jedisFactory.start();
        return jedisFactory;
    }

    private LettuceConnectionFactory createLettuceConnectionFactory(String host, int port, DriverConfig driverConfig) {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder configBuilder =
                LettuceClientConfiguration.builder();
        
        if (driverConfig.isTlsEnabled()) {
            configBuilder.useSsl();
        }
        
        LettuceClientConfiguration clientConfig = configBuilder.build();
        
        LettuceConnectionFactory lettuceFactory;
        if (driverConfig.isClusterMode()) {
            ValkeyClusterConfiguration clusterConfig = new ValkeyClusterConfiguration();
            clusterConfig.addClusterNode(new ValkeyNode(host, port));
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    clusterConfig.setUsername(auth.getUsername());
                }
                clusterConfig.setPassword(ValkeyPassword.of(auth.getPassword()));
            }
            lettuceFactory = new LettuceConnectionFactory(clusterConfig, clientConfig);
        } else {
            ValkeyStandaloneConfiguration standaloneConfig = new ValkeyStandaloneConfiguration(host, port);
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    standaloneConfig.setUsername(auth.getUsername());
                }
                standaloneConfig.setPassword(ValkeyPassword.of(auth.getPassword()));
            }
            lettuceFactory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        }
        
        lettuceFactory.afterPropertiesSet();
        lettuceFactory.start();
        return lettuceFactory;
    }

    private ValkeyGlideConnectionFactory createValkeyGlideConnectionFactory(String host, int port, DriverConfig driverConfig) {
        ValkeyGlideClientConfiguration.ValkeyGlideClientConfigurationBuilder configBuilder =
                ValkeyGlideClientConfiguration.builder();
        
        if (driverConfig.isTlsEnabled()) {
            configBuilder.useSsl();
        }
        
        // Configure adapter pool size (default 8, can be set via pool_size in driver config)
        configBuilder.maxPoolSize(driverConfig.getPoolSize());
        logger.info("ValkeyGlide adapter pool maxPoolSize={}", driverConfig.getPoolSize());
        
        ValkeyGlideClientConfiguration clientConfig = configBuilder.build();
        
        ValkeyGlideConnectionFactory glideFactory;
        if (driverConfig.isClusterMode()) {
            ValkeyClusterConfiguration clusterConfig = new ValkeyClusterConfiguration();
            clusterConfig.addClusterNode(new ValkeyNode(host, port));
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    clusterConfig.setUsername(auth.getUsername());
                }
                clusterConfig.setPassword(ValkeyPassword.of(auth.getPassword()));
            }
            glideFactory = new ValkeyGlideConnectionFactory(clusterConfig, clientConfig);
        } else {
            ValkeyStandaloneConfiguration standaloneConfig = new ValkeyStandaloneConfiguration(host, port);
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    standaloneConfig.setUsername(auth.getUsername());
                }
                standaloneConfig.setPassword(ValkeyPassword.of(auth.getPassword()));
            }
            glideFactory = new ValkeyGlideConnectionFactory(standaloneConfig, clientConfig);
        }
        
        glideFactory.afterPropertiesSet();
        return glideFactory;
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
        return AsyncHelper.timed(() -> shared.template.execute((ValkeyCallback<String>) ValkeyConnection::ping));
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping(byte[] message) {
        SharedState shared = getShared();
        return AsyncHelper.timed(() -> shared.template.execute((ValkeyCallback<String>) connection -> {
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
        return AsyncHelper.timedVoid(() -> shared.template.execute((ValkeyCallback<Void>) connection -> {
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
                logger.debug("Spring Data Valkey client closed (remaining refs: {})", remaining);
                return;
            }
            
            // Last instance — destroy shared state
            SharedState shared = sharedStateRef.getAndSet(null);
            if (shared == null) {
                return;
            }
            
            logger.info("Closing shared Spring Data Valkey infrastructure ({})", shared.secondaryDriverId);
            
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
            } else if (shared.connectionFactory instanceof ValkeyGlideConnectionFactory glideFactory) {
                try {
                    glideFactory.destroy();
                } catch (Exception e) {
                    logger.warn("Error destroying ValkeyGlide connection factory: {}", e.getMessage());
                }
            }
            
            shared.executor.shutdown();
        }
    }
}
