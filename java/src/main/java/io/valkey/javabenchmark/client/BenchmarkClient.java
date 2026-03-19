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
package io.valkey.javabenchmark.client;

import io.valkey.javabenchmark.config.DriverConfig;

import java.util.concurrent.CompletableFuture;

/**
 * Abstraction interface for benchmark clients.
 * Implementations provide access to different Redis/Valkey client libraries.
 * 
 * <p>This interface is designed to be easily extensible - adding support for
 * a new client library only requires implementing this interface and registering
 * it with the {@link BenchmarkClientFactory}.</p>
 * 
 * <p>All latency-sensitive operations return CompletableFuture&lt;TimedResult&gt; for async execution.
 * The timing is measured at the point of actual execution, not at submission time,
 * ensuring accurate latency measurements even under high concurrency.</p>
 *
 * @author Ilia Kolominsky
 */
public interface BenchmarkClient extends AutoCloseable {

    /**
     * Get the driver ID (e.g., "jedis", "lettuce", "valkey-glide").
     * 
     * @return the driver identifier
     */
    String getDriverId();

    /**
     * Get a description of this client.
     * 
     * @return the client description
     */
    String getDescription();

    /**
     * Get the driver library version.
     * 
     * @return the version string (e.g., "5.2.0")
     */
    String getDriverVersion();

    /**
     * Get the secondary driver library version (for composite drivers like Spring Data).
     * 
     * <p>For drivers that wrap other drivers (e.g., spring-data-valkey using jedis/lettuce/valkey-glide),
     * this returns the version of the underlying driver.</p>
     * 
     * @return the secondary driver version string, or null if not applicable
     */
    default String getSecondaryDriverVersion() {
        return null;
    }

    /**
     * Initialize and connect to the server.
     * 
     * @param host server host
     * @param port server port
     * @param driverConfig driver-specific configuration
     * @throws ClientException if connection fails
     */
    void connect(String host, int port, DriverConfig driverConfig) throws ClientException;

    /**
     * Check if the client is currently connected.
     * 
     * @return true if connected
     */
    boolean isConnected();

    /**
     * Execute a SET command with timing measurement.
     * 
     * @param key the key
     * @param value the value
     * @return CompletableFuture containing TimedResult with execution latency
     */
    CompletableFuture<TimedResult<Void>> set(byte[] key, byte[] value);

    /**
     * Execute a GET command with timing measurement.
     * 
     * @param key the key
     * @return CompletableFuture containing TimedResult with the value (or null if not found) and latency
     */
    CompletableFuture<TimedResult<byte[]>> get(byte[] key);

    /**
     * Execute a PING command with timing measurement.
     * 
     * @return CompletableFuture containing TimedResult with the PONG response and latency
     */
    CompletableFuture<TimedResult<String>> ping();

    /**
     * Execute a PING command with a message and timing measurement.
     * 
     * @param message the message to send
     * @return CompletableFuture containing TimedResult with the echoed message and latency
     */
    CompletableFuture<TimedResult<String>> ping(byte[] message);

    /**
     * Execute a DEL command with timing measurement.
     * 
     * @param keys the keys to delete
     * @return CompletableFuture containing TimedResult with the number of keys deleted and latency
     */
    CompletableFuture<TimedResult<Long>> del(byte[]... keys);

    /**
     * Flush all keys from the current database (use with caution).
     * 
     * @return CompletableFuture containing TimedResult with execution latency
     */
    CompletableFuture<TimedResult<Void>> flushDb();

    /**
     * Notify the client that the engine is entering/leaving warmup mode.
     * 
     * <p>This is primarily used by {@link io.valkey.javabenchmark.client.impl.RecordingBenchmarkClient}
     * to suppress error simulation during warmup PINGs, allowing connectivity
     * checks to pass. Real driver implementations should ignore this
     * (the default is a no-op).</p>
     *
     * @param warmup true when entering warmup, false when leaving
     */
    default void setWarmupMode(boolean warmup) { }

    /**
     * Close the client connection and release resources.
     */
    @Override
    void close();

    /**
     * Exception thrown when client operations fail.
     */
    class ClientException extends Exception {
        public ClientException(String message) {
            super(message);
        }

        public ClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}