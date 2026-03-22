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

/**
 * Synchronous timing helper for all benchmark clients.
 *
 * <p>Provides a unified timing pattern for driver operations. Each operation is
 * executed <b>inline on the calling thread</b> (which is already a long-lived
 * virtual thread owned by {@link io.valkey.javabenchmark.engine.BenchmarkEngine}),
 * timed with {@code System.nanoTime()}, and wrapped in an already-completed
 * {@link CompletableFuture} for interface compatibility.</p>
 *
 * <h3>Why synchronous?</h3>
 * <p>The engine's VT-per-client architecture already provides one virtual thread
 * per client connection. Spawning an additional per-request VT (as the previous
 * implementation did via {@code CompletableFuture.supplyAsync(..., VIRTUAL_EXECUTOR)})
 * was redundant and created ~500K+ unnecessary VT creations/sec, adding significant
 * ForkJoinPool scheduling overhead without any benefit.</p>
 *
 * @author Ilia Kolominsky
 */
public class AsyncHelper {

    /**
     * Execute a blocking operation synchronously with timing.
     *
     * <p>The operation runs on the calling thread (a long-lived virtual thread)
     * and returns an already-completed future with the timed result.</p>
     *
     * @param operation the operation to execute (may block, e.g., {@code client.set().get()})
     * @param <T>       the result type
     * @return a completed future with the timed result
     */
    public static <T> CompletableFuture<TimedResult<T>> timed(Callable<T> operation) {
        long start = System.nanoTime();
        try {
            T result = operation.call();
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return CompletableFuture.completedFuture(TimedResult.of(result, latencyMicros));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new RuntimeException(e));
        }
    }

    /**
     * Execute a void blocking operation synchronously with timing.
     *
     * <p>The operation runs on the calling thread (a long-lived virtual thread)
     * and returns an already-completed future with the timed result.</p>
     *
     * @param operation the void operation to execute
     * @return a completed future with the timed result (no value)
     */
    public static CompletableFuture<TimedResult<Void>> timedVoid(ThrowingRunnable operation) {
        long start = System.nanoTime();
        try {
            operation.run();
            long latencyMicros = (System.nanoTime() - start) / 1000;
            return CompletableFuture.completedFuture(TimedResult.ofVoid(latencyMicros));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new RuntimeException(e));
        }
    }

    /**
     * A runnable that can throw checked exceptions.
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
