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

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Common async execution helper for all benchmark clients.
 *
 * <p>Provides a unified execution pattern using virtual threads (Java 21+).
 * All driver operations are wrapped in virtual threads with consistent timing,
 * ensuring fair comparison across synchronous and asynchronous drivers.</p>
 *
 * <p>For async drivers (glide, lettuce, redisson), the underlying async call
 * is made and then {@code .get()} blocks the virtual thread (NOT an OS thread)
 * until the response arrives. This approach:</p>
 * <ul>
 *   <li>Avoids pinning completion work to library-internal thread pools</li>
 *   <li>Gives the JVM full control over scheduling via virtual thread scheduler</li>
 *   <li>Matches the pattern a real Java 21 application would use</li>
 *   <li>Ensures all drivers are measured with the same execution model</li>
 * </ul>
 *
 * @author Ilia Kolominsky
 */
public class AsyncHelper {

    private static final ExecutorService VIRTUAL_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Execute a blocking operation on a virtual thread with timing.
     *
     * @param operation the operation to execute (may block, e.g., {@code client.set().get()})
     * @param <T>       the result type
     * @return a future that completes with the timed result
     */
    public static <T> CompletableFuture<TimedResult<T>> timed(Callable<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            try {
                T result = operation.call();
                long latencyMicros = (System.nanoTime() - start) / 1000;
                return TimedResult.of(result, latencyMicros);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, VIRTUAL_EXECUTOR);
    }

    /**
     * Execute a void blocking operation on a virtual thread with timing.
     *
     * @param operation the void operation to execute
     * @return a future that completes with the timed result (no value)
     */
    public static CompletableFuture<TimedResult<Void>> timedVoid(ThrowingRunnable operation) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            try {
                operation.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return TimedResult.ofVoid(latencyMicros);
        }, VIRTUAL_EXECUTOR);
    }

    /**
     * Get the shared virtual thread executor for use in clients that need it directly.
     *
     * @return the shared virtual thread executor
     */
    public static ExecutorService executor() {
        return VIRTUAL_EXECUTOR;
    }

    /**
     * A runnable that can throw checked exceptions.
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
