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
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.valkey.javabenchmark.client.AsyncHelper;
import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.client.TimedResult;
import io.valkey.javabenchmark.config.DriverConfig;
import io.valkey.javabenchmark.util.VersionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lettuce implementation of BenchmarkClient.
 *
 * <p>All instances share a single {@link RedisClient} (or {@link RedisClusterClient}) via a
 * static holder with reference counting. Each instance opens its own
 * {@link StatefulRedisConnection} — a dedicated TCP connection — from the shared client.
 * This matches how a real application uses Lettuce: one client infrastructure (Netty event
 * loop group, timer) shared across many connections.</p>
 *
 * <p>Without sharing, 512 instances would create 512 separate {@code RedisClient} objects,
 * each with its own Netty event loop thread and HashedWheelTimer — 1024 extra OS threads
 * competing for CPU on a 96-vCPU machine.</p>
 *
 * @author Ilia Kolominsky
 */
public class LettuceBenchmarkClient implements BenchmarkClient {

    private static final Logger logger = LoggerFactory.getLogger(LettuceBenchmarkClient.class);

    /** Shared RedisClient/ClusterClient state across all instances. */
    private static final AtomicReference<SharedClient> sharedClientRef = new AtomicReference<>();
    private static final AtomicInteger refCount = new AtomicInteger(0);
    private static final Object lock = new Object();

    // Per-instance state: each instance owns one TCP connection
    private StatefulRedisConnection<byte[], byte[]> connection;
    private StatefulRedisClusterConnection<byte[], byte[]> clusterConnection;
    private RedisAsyncCommands<byte[], byte[]> asyncCommands;
    private RedisAdvancedClusterAsyncCommands<byte[], byte[]> clusterAsyncCommands;
    private boolean isClusterMode;
    private boolean connected;

    /**
     * Shared Lettuce client infrastructure (Netty event loops, timer, DNS).
     * Created by the first instance, destroyed by the last.
     */
    private static class SharedClient {
        final RedisClient redisClient;          // non-null for standalone
        final RedisClusterClient clusterClient; // non-null for cluster
        final RedisURI redisUri;
        final boolean isCluster;

        SharedClient(RedisClient redisClient, RedisURI redisUri) {
            this.redisClient = redisClient;
            this.clusterClient = null;
            this.redisUri = redisUri;
            this.isCluster = false;
        }

        SharedClient(RedisClusterClient clusterClient, RedisURI redisUri) {
            this.redisClient = null;
            this.clusterClient = clusterClient;
            this.redisUri = redisUri;
            this.isCluster = true;
        }
    }

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
        return VersionHelper.getVersion(RedisClient.class, "io.lettuce", "lettuce-core");
    }

    @Override
    public void connect(String host, int port, DriverConfig driverConfig) throws ClientException {
        logger.info("Connecting Lettuce to {}:{} (cluster={})", host, port, driverConfig.isClusterMode());
        
        try {
            this.isClusterMode = driverConfig.isClusterMode();
            
            synchronized (lock) {
                int refs = refCount.incrementAndGet();
                
                if (sharedClientRef.get() == null) {
                    // First instance — create shared client infrastructure
                    RedisURI redisUri = buildRedisUri(host, port, driverConfig);
                    ClientResources resources = buildClientResources(driverConfig);
                    
                    if (isClusterMode) {
                        RedisClusterClient clusterClient = (resources != null)
                                ? RedisClusterClient.create(resources, redisUri)
                                : RedisClusterClient.create(redisUri);
                        sharedClientRef.set(new SharedClient(clusterClient, redisUri));
                    } else {
                        RedisClient redisClient = (resources != null)
                                ? RedisClient.create(resources, redisUri)
                                : RedisClient.create(redisUri);
                        sharedClientRef.set(new SharedClient(redisClient, redisUri));
                    }
                    
                    logger.info("Created shared Lettuce client infrastructure (ref count: {})", refs);
                } else {
                    logger.debug("Reusing shared Lettuce client (ref count: {})", refs);
                }
            }
            
            // Open a new TCP connection from the shared client (outside the lock)
            SharedClient shared = sharedClientRef.get();
            if (shared.isCluster) {
                clusterConnection = shared.clusterClient.connect(ByteArrayCodec.INSTANCE);
                clusterAsyncCommands = clusterConnection.async();
            } else {
                connection = shared.redisClient.connect(ByteArrayCodec.INSTANCE);
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
            refCount.decrementAndGet();
            throw new ClientException("Failed to connect Lettuce to " + host + ":" + port, e);
        }
    }

    /**
     * Build a RedisURI from the driver configuration.
     */
    private static RedisURI buildRedisUri(String host, int port, DriverConfig driverConfig) {
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(host)
                .withPort(port);
        
        if (driverConfig.getCommandTimeoutMs() != null) {
            uriBuilder.withTimeout(Duration.ofMillis(driverConfig.getCommandTimeoutMs()));
        }
        
        if (driverConfig.isTlsEnabled()) {
            uriBuilder.withSsl(true);
        }
        
        if (driverConfig.hasAuth()) {
            DriverConfig.AuthConfig auth = driverConfig.getAuth();
            if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                uriBuilder.withAuthentication(auth.getUsername(), auth.getPassword().toCharArray());
            } else {
                uriBuilder.withPassword(auth.getPassword().toCharArray());
            }
        }
        
        return uriBuilder.build();
    }

    /**
     * Build custom ClientResources from specific_driver_config if thread pool sizes are specified.
     *
     * <p>Supported config keys in {@code specific_driver_config}:</p>
     * <ul>
     *   <li>{@code io_thread_pool_size} — number of Netty NIO event loop threads (default: availableProcessors)</li>
     *   <li>{@code computation_thread_pool_size} — number of computation threads (default: availableProcessors)</li>
     * </ul>
     *
     * @return custom ClientResources, or null to use Lettuce defaults
     */
    private static ClientResources buildClientResources(DriverConfig driverConfig) {
        Map<String, Object> config = driverConfig.getSpecificDriverConfig();
        if (config == null) return null;
        
        Integer ioThreads = getIntConfig(config, "io_thread_pool_size");
        Integer compThreads = getIntConfig(config, "computation_thread_pool_size");
        
        if (ioThreads == null && compThreads == null) return null;
        
        DefaultClientResources.Builder builder = DefaultClientResources.builder();
        
        if (ioThreads != null) {
            builder.ioThreadPoolSize(ioThreads);
            logger.info("Lettuce ioThreadPoolSize={}", ioThreads);
        }
        if (compThreads != null) {
            builder.computationThreadPoolSize(compThreads);
            logger.info("Lettuce computationThreadPoolSize={}", compThreads);
        }
        
        return builder.build();
    }

    private static Integer getIntConfig(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null) return null;
        return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(value.toString());
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public CompletableFuture<TimedResult<Void>> set(byte[] key, byte[] value) {
        return AsyncHelper.timedVoid(() -> {
            if (isClusterMode) {
                clusterAsyncCommands.set(key, value).get();
            } else {
                asyncCommands.set(key, value).get();
            }
        });
    }

    @Override
    public CompletableFuture<TimedResult<byte[]>> get(byte[] key) {
        return AsyncHelper.timed(() -> isClusterMode
                ? clusterAsyncCommands.get(key).get()
                : asyncCommands.get(key).get());
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping() {
        return AsyncHelper.timed(() -> isClusterMode
                ? clusterAsyncCommands.ping().get()
                : asyncCommands.ping().get());
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping(byte[] message) {
        // Lettuce doesn't have a direct ping with message for byte arrays
        // Use echo as a workaround
        return AsyncHelper.timed(() -> {
            byte[] b = isClusterMode
                    ? clusterAsyncCommands.echo(message).get()
                    : asyncCommands.echo(message).get();
            return new String(b);
        });
    }

    @Override
    public CompletableFuture<TimedResult<Long>> del(byte[]... keys) {
        return AsyncHelper.timed(() -> isClusterMode
                ? clusterAsyncCommands.del(keys).get()
                : asyncCommands.del(keys).get());
    }

    @Override
    public CompletableFuture<TimedResult<Void>> flushDb() {
        return AsyncHelper.timedVoid(() -> {
            if (isClusterMode) {
                clusterAsyncCommands.flushdb().get();
            } else {
                asyncCommands.flushdb().get();
            }
        });
    }

    @Override
    public void close() {
        logger.info("Closing Lettuce connection");
        connected = false;
        
        // Close this instance's TCP connection
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                logger.warn("Error closing Lettuce connection: {}", e.getMessage());
            }
            connection = null;
            asyncCommands = null;
        }
        
        if (clusterConnection != null) {
            try {
                clusterConnection.close();
            } catch (Exception e) {
                logger.warn("Error closing Lettuce cluster connection: {}", e.getMessage());
            }
            clusterConnection = null;
            clusterAsyncCommands = null;
        }
        
        // Decrement ref count; last one shuts down the shared client
        synchronized (lock) {
            int remaining = refCount.decrementAndGet();
            
            if (remaining > 0) {
                logger.debug("Lettuce connection closed (remaining refs: {})", remaining);
                return;
            }
            
            SharedClient shared = sharedClientRef.getAndSet(null);
            if (shared == null) return;
            
            logger.info("Shutting down shared Lettuce client infrastructure");
            
            if (shared.redisClient != null) {
                try {
                    shared.redisClient.shutdown();
                } catch (Exception e) {
                    logger.warn("Error shutting down Lettuce client: {}", e.getMessage());
                }
            }
            
            if (shared.clusterClient != null) {
                try {
                    shared.clusterClient.shutdown();
                } catch (Exception e) {
                    logger.warn("Error shutting down Lettuce cluster client: {}", e.getMessage());
                }
            }
        }
    }
}
