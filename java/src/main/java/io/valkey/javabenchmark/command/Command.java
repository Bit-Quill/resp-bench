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
package io.valkey.javabenchmark.command;

import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.config.CommandConfig;
import io.valkey.javabenchmark.engine.KeyGenerator;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for benchmark commands.
 * Implementations provide specific Redis/Valkey command execution.
 * 
 * <p>To add a new command:</p>
 * <ol>
 *   <li>Implement this interface</li>
 *   <li>Register it in {@link CommandFactory}</li>
 * </ol>
 */
public interface Command {

    /**
     * Get the command name (e.g., "SET", "GET", "PING").
     * 
     * @return the command name
     */
    String getName();

    /**
     * Get description of the command.
     * 
     * @return description
     */
    String getDescription();

    /**
     * Configure the command from config.
     * 
     * @param config the command configuration
     */
    void configure(CommandConfig config);

    /**
     * Execute the command asynchronously.
     * 
     * @param client the benchmark client
     * @param keyGenerator the key generator
     * @return CompletableFuture with the command result
     */
    CompletableFuture<CommandResult> execute(BenchmarkClient client, KeyGenerator keyGenerator);

    /**
     * Get the weight of this command (for weighted selection).
     * 
     * @return the weight
     */
    double getWeight();

    /**
     * Command result containing timing and status.
     */
    record CommandResult(
            String commandName,
            boolean success,
            long latencyMicros,
            String errorMessage
    ) {
        public static CommandResult success(String commandName, long latencyMicros) {
            return new CommandResult(commandName, true, latencyMicros, null);
        }

        public static CommandResult failure(String commandName, long latencyMicros, String errorMessage) {
            return new CommandResult(commandName, false, latencyMicros, errorMessage);
        }

        public static CommandResult failure(String commandName, String errorMessage) {
            return new CommandResult(commandName, false, 0, errorMessage);
        }
    }
}