// Command resp-bench is the Go benchmark engine for resp-bench.
//
// Usage:
//
//	resp-bench --server host:port --driver driver.json --workload workload.json --metrics out.ndjson
//	resp-bench --info
package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"

	"github.com/resp-bench/go/internal/client"
	"github.com/resp-bench/go/internal/config"
	"github.com/resp-bench/go/internal/engine"
)

// version is the engine version (kept in step with the other engines).
const version = "1.0.0"

func main() {
	os.Exit(run())
}

func run() int {
	var (
		server   = flag.String("server", "localhost:6379", "server address host:port")
		driver   = flag.String("driver", "", "path to driver config JSON")
		workload = flag.String("workload", "", "path to workload config JSON")
		metrics  = flag.String("metrics", "", "path to NDJSON metrics output")
		commitID = flag.String("commit-id", "", "commit id recorded in metadata")
		info     = flag.Bool("info", false, "print supported drivers and exit")
		ver      = flag.Bool("version", false, "print version and exit")
	)
	flag.Parse()

	logger := log.New(os.Stderr, "", log.LstdFlags)

	if *ver {
		fmt.Println(version)
		return 0
	}
	if *info {
		fmt.Printf("resp-bench Go engine v%s\n", version)
		fmt.Printf("Supported drivers: %s\n", strings.Join(client.SupportedDrivers, ", "))
		fmt.Println("Supported commands: get, set, ping")
		return 0
	}

	if *driver == "" || *workload == "" || *metrics == "" {
		fmt.Fprintln(os.Stderr, "error: --driver, --workload and --metrics are required")
		flag.Usage()
		return 2
	}

	host, port, err := parseServer(*server)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		return 2
	}

	driverCfg, err := config.LoadDriverConfig(*driver)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		return 2
	}
	workloadCfg, err := config.LoadWorkloadConfig(*workload)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		return 2
	}

	b := engine.New(host, port, driverCfg, workloadCfg, *metrics, *commitID, logger)

	// Translate SIGINT/SIGTERM into a graceful "interrupt current phase" signal
	// so the in-flight phase is recorded as INTERRUPTED rather than the process
	// being killed with no row written.
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sigCh
		logger.Println("interrupt received; stopping current phase...")
		b.SetInterrupted()
	}()

	exitCode, err := b.Run()
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
	}
	return exitCode
}

// parseServer splits a "host:port" address, taking the first if comma-separated.
func parseServer(server string) (string, int, error) {
	first := server
	if i := strings.IndexByte(server, ','); i >= 0 {
		first = server[:i]
	}
	host, portStr, found := strings.Cut(first, ":")
	if !found {
		return "", 0, fmt.Errorf("invalid --server %q, expected host:port", server)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return "", 0, fmt.Errorf("invalid port in --server %q: %w", server, err)
	}
	return host, port, nil
}
