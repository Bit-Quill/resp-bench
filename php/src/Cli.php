<?php

declare(strict_types=1);

namespace RespBench;

use RespBench\Client\Factory as ClientFactory;
use RespBench\Command\Factory as CommandFactory;
use RespBench\Config\Loader;
use RespBench\Engine\Benchmark;

/**
 * Command-line interface for the resp-bench PHP engine.
 */
final class Cli
{
    /** @var array<string,mixed> */
    private array $options = [
        'host' => 'localhost',
        'port' => 6379,
    ];

    /**
     * @param list<string> $argv
     */
    public function run(array $argv): int
    {
        try {
            $this->parseOptions($argv);

            if (!empty($this->options['info'])) {
                $this->printInfo();

                return 0;
            }

            $this->validateOptions();
            $this->executeBenchmark();

            return 0;
        } catch (\Throwable $e) {
            fwrite(STDERR, 'Error: ' . $e->getMessage() . "\n");
            if (getenv('DEBUG')) {
                fwrite(STDERR, $e->getTraceAsString() . "\n");
            }

            return 1;
        }
    }

    /**
     * @param list<string> $argv
     */
    private function parseOptions(array $argv): void
    {
        $args = array_slice($argv, 1);
        $count = count($args);

        for ($i = 0; $i < $count; $i++) {
            $arg = $args[$i];
            $next = static fn (): string => $args[++$i] ?? '';

            switch ($arg) {
                case '--server':
                    $server = $next();
                    $parts = explode(':', $server);
                    $this->options['host'] = $parts[0];
                    if (isset($parts[1])) {
                        $this->options['port'] = (int) $parts[1];
                    }
                    break;
                case '--driver':
                    $this->options['driver'] = $next();
                    break;
                case '--workload':
                    $this->options['workload'] = $next();
                    break;
                case '--metrics':
                    $this->options['metrics'] = $next();
                    break;
                case '--commit-id':
                    $this->options['commit_id'] = $next();
                    break;
                case '--concurrency':
                    $this->options['concurrency_mode'] = $next();
                    break;
                case '--info':
                    $this->options['info'] = true;
                    break;
                case '-h':
                case '--help':
                    $this->printHelp();
                    exit(0);
                case '-v':
                case '--version':
                    echo 'resp-bench PHP Engine v' . Version::VERSION . "\n";
                    exit(0);
                default:
                    // ignore unknown args for forward compatibility
                    break;
            }
        }
    }

    private function validateOptions(): void
    {
        $missing = [];
        foreach (['driver', 'workload', 'metrics'] as $required) {
            if (empty($this->options[$required])) {
                $missing[] = "--{$required}";
            }
        }
        if ($missing !== []) {
            throw new \InvalidArgumentException('Missing required options: ' . implode(', ', $missing));
        }

        if (!is_file((string) $this->options['driver'])) {
            throw new \InvalidArgumentException('Driver config not found: ' . $this->options['driver']);
        }
        if (!is_file((string) $this->options['workload'])) {
            throw new \InvalidArgumentException('Workload config not found: ' . $this->options['workload']);
        }
    }

    private function executeBenchmark(): void
    {
        $driverConfig = Loader::loadDriverConfig((string) $this->options['driver']);
        $workloadConfig = Loader::loadWorkloadConfig((string) $this->options['workload']);

        $engine = new Benchmark(
            host: (string) $this->options['host'],
            port: (int) $this->options['port'],
            driverConfig: $driverConfig,
            workloadConfig: $workloadConfig,
            metricsPath: (string) $this->options['metrics'],
            commitId: isset($this->options['commit_id']) ? (string) $this->options['commit_id'] : null,
            concurrencyMode: isset($this->options['concurrency_mode'])
                ? (string) $this->options['concurrency_mode']
                : null,
        );

        $engine->run();
    }

    private function printInfo(): void
    {
        echo 'resp-bench PHP Engine v' . Version::VERSION . "\n\n";
        echo "Supported Drivers:\n";
        foreach (ClientFactory::supportedDrivers() as $driver) {
            echo "  - {$driver}\n";
        }
        echo "\nSupported Commands:\n";
        foreach (CommandFactory::supportedCommands() as $cmd) {
            echo "  - {$cmd}\n";
        }
        echo "\nConcurrency: process-per-connection (max 256), inline fallback\n";
        echo 'valkey_glide extension: ' . (extension_loaded('valkey_glide') ? 'loaded' : 'NOT loaded') . "\n";
        echo 'pcntl extension: ' . (function_exists('pcntl_fork') ? 'available' : 'NOT available') . "\n";
    }

    private function printHelp(): void
    {
        echo <<<HELP
            Usage: resp-bench [options]

              --server HOST:PORT   Server address (default: localhost:6379)
              --driver PATH        Driver configuration file (required)
              --workload PATH      Workload configuration file (required)
              --metrics PATH       Metrics output file (required)
              --commit-id ID       Git commit ID for metadata
              --concurrency MODE   'process' (default) or 'inline'
              --info               Show supported drivers and commands
              -h, --help           Show this help
              -v, --version        Show version

            HELP;
    }
}
