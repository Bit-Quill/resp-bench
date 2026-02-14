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

import io.valkey.javabenchmark.command.impl.*;
import io.valkey.javabenchmark.config.CommandConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

/**
 * Factory for creating Command instances.
 * Supports registration of custom command implementations.
 */
public class CommandFactory {

    private static final Logger logger = LoggerFactory.getLogger(CommandFactory.class);

    private static final Map<String, CommandInfo> commandRegistry = new LinkedHashMap<>();

    static {
        // Register built-in commands
        registerCommand("set", "SET key value", SetCommand::new);
        registerCommand("get", "GET key", GetCommand::new);
        registerCommand("ping", "PING [message]", PingCommand::new);
    }

    /**
     * Register a custom command implementation.
     * 
     * @param commandName the command name (e.g., "mset", "hset")
     * @param description command description
     * @param supplier factory function to create command instances
     */
    public static void registerCommand(String commandName, String description, Supplier<Command> supplier) {
        commandRegistry.put(commandName.toLowerCase(), new CommandInfo(commandName, description, supplier));
        logger.debug("Registered command: {}", commandName);
    }

    /**
     * Check if a command is supported.
     * 
     * @param commandName the command name
     * @return true if supported
     */
    public static boolean isSupported(String commandName) {
        return commandRegistry.containsKey(commandName.toLowerCase());
    }

    /**
     * Create a command from configuration.
     * 
     * @param config the command configuration
     * @return a configured command instance
     * @throws IllegalArgumentException if command is not supported
     */
    public static Command create(CommandConfig config) {
        String commandName = config.getCommand().toLowerCase();
        
        CommandInfo info = commandRegistry.get(commandName);
        if (info == null) {
            throw new IllegalArgumentException("Unsupported command: " + config.getCommand() +
                    ". Supported: " + commandRegistry.keySet());
        }
        
        Command command = info.supplier().get();
        command.configure(config);
        return command;
    }

    /**
     * Create commands from a list of configurations.
     * 
     * @param configs the command configurations
     * @return list of configured commands
     */
    public static List<Command> createAll(List<CommandConfig> configs) {
        return configs.stream()
                .map(CommandFactory::create)
                .toList();
    }

    /**
     * Get all registered command names.
     * 
     * @return collection of command names
     */
    public static Collection<String> getRegisteredCommandNames() {
        return Collections.unmodifiableSet(commandRegistry.keySet());
    }

    /**
     * Get information about all registered commands.
     * 
     * @return list of command info
     */
    public static List<CommandInfo> getRegisteredCommands() {
        return new ArrayList<>(commandRegistry.values());
    }

    /**
     * Information about a registered command.
     */
    public record CommandInfo(String name, String description, Supplier<Command> supplier) {
    }
}