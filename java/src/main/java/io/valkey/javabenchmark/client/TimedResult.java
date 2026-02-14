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

/**
 * Wraps a result with its execution latency in microseconds.
 * 
 * <p>This class is used to accurately measure command execution time
 * by capturing timing at the point of actual execution rather than
 * at submission time.</p>
 *
 * @param <T> the type of the result value
 * @author Ilia Kolominsky
 */
public class TimedResult<T> {
    private final T value;
    private final long latencyMicros;

    public TimedResult(T value, long latencyMicros) {
        this.value = value;
        this.latencyMicros = latencyMicros;
    }

    /**
     * Get the result value.
     * @return the result value (may be null for void operations)
     */
    public T getValue() {
        return value;
    }

    /**
     * Get the execution latency in microseconds.
     * @return latency in microseconds
     */
    public long getLatencyMicros() {
        return latencyMicros;
    }

    /**
     * Create a TimedResult for a void operation.
     * @param latencyMicros the execution latency in microseconds
     * @return a TimedResult with null value
     */
    public static TimedResult<Void> ofVoid(long latencyMicros) {
        return new TimedResult<>(null, latencyMicros);
    }

    /**
     * Create a TimedResult with a value.
     * @param value the result value
     * @param latencyMicros the execution latency in microseconds
     * @param <T> the type of the result value
     * @return a TimedResult with the value
     */
    public static <T> TimedResult<T> of(T value, long latencyMicros) {
        return new TimedResult<>(value, latencyMicros);
    }
}