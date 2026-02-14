# Copyright 2024 resp-bench contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# ============================================================================
# resp-bench - Multi-language RESP Protocol Benchmark Suite
# ============================================================================

# Server configuration
SERVER_VERSION?=8.1.1
SERVER_PROJECT?=valkey
SERVER_GH_ORG?=valkey-io

# Build configuration
SHELL=/bin/bash -euo pipefail
WORK_DIR=$(shell pwd)/work

.PHONY: help all clean clobber \
	server-standalone-start server-standalone-stop \
	server-cluster-start server-cluster-stop server-cluster-init \
	server-sentinel-start server-sentinel-stop \
	server-start server-stop \
	java-build java-test java-run java-clean \
	python-build python-test python-run python-clean \
	config-editor-build config-editor-dev

# ============================================================================
# Help
# ============================================================================

help:
	@echo "resp-bench - Multi-language RESP Protocol Benchmark Suite"
	@echo ""
	@echo "Server Management:"
	@echo "  make server-start           Start all servers (standalone + cluster + sentinel)"
	@echo "  make server-stop            Stop all servers"
	@echo "  make server-standalone-start Start standalone server (port 6379)"
	@echo "  make server-cluster-start   Start cluster (ports 7379-7382)"
	@echo "  make server-sentinel-start  Start sentinel (ports 26379-26382)"
	@echo ""
	@echo "Java Engine:"
	@echo "  make java-build             Build Java benchmark engine"
	@echo "  make java-test              Run Java tests"
	@echo "  make java-run               Run Java benchmark (requires DRIVER and WORKLOAD)"
	@echo "  make java-clean             Clean Java build artifacts"
	@echo ""
	@echo "Python Engine (placeholder):"
	@echo "  make python-build           Build Python benchmark engine"
	@echo "  make python-test            Run Python tests"
	@echo "  make python-run             Run Python benchmark"
	@echo ""
	@echo "Config Editor:"
	@echo "  make config-editor-build    Build config editor UI"
	@echo "  make config-editor-dev      Run config editor in development mode"
	@echo ""
	@echo "Global:"
	@echo "  make clean                  Clean server work files"
	@echo "  make clobber                Remove all work directories"
	@echo ""
	@echo "Examples:"
	@echo "  make server-standalone-start java-run DRIVER=configs/drivers/example-jedis-standalone.json WORKLOAD=configs/workloads/example-workload.json"

# ============================================================================
# Server Binary Installation
# ============================================================================

$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli $(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-server:
	@mkdir -p $(WORK_DIR)/$(SERVER_PROJECT)
	curl -sSL https://github.com/$(SERVER_GH_ORG)/$(SERVER_PROJECT)/archive/refs/tags/$(SERVER_VERSION).tar.gz | tar xzf - -C $(WORK_DIR)
	$(MAKE) -C $(WORK_DIR)/$(SERVER_PROJECT)-$(SERVER_VERSION) -j
	$(MAKE) -C $(WORK_DIR)/$(SERVER_PROJECT)-$(SERVER_VERSION) PREFIX=$(WORK_DIR)/$(SERVER_PROJECT) install
	rm -rf $(WORK_DIR)/$(SERVER_PROJECT)-$(SERVER_VERSION)

# ============================================================================
# Standalone Server Configuration
# ============================================================================

.PRECIOUS: $(WORK_DIR)/$(SERVER_PROJECT)-%.conf

# Master node (port 6379)
$(WORK_DIR)/$(SERVER_PROJECT)-6379.conf:
	@mkdir -p $(@D)
	echo port 6379 >> $@
	echo daemonize yes >> $@
	echo protected-mode no >> $@
	echo bind 0.0.0.0 >> $@
	echo notify-keyspace-events Ex >> $@
	echo pidfile $(WORK_DIR)/$(SERVER_PROJECT)-6379.pid >> $@
	echo logfile $(WORK_DIR)/$(SERVER_PROJECT)-6379.log >> $@
	echo unixsocket $(WORK_DIR)/$(SERVER_PROJECT)-6379.sock >> $@
	echo unixsocketperm 755 >> $@
	echo save \"\" >> $@

# Replica nodes (ports 6380, 6381)
$(WORK_DIR)/$(SERVER_PROJECT)-%.conf:
	@mkdir -p $(@D)
	echo port $* >> $@
	echo daemonize yes >> $@
	echo protected-mode no >> $@
	echo bind 0.0.0.0 >> $@
	echo notify-keyspace-events Ex >> $@
	echo pidfile $(WORK_DIR)/$(SERVER_PROJECT)-$*.pid >> $@
	echo logfile $(WORK_DIR)/$(SERVER_PROJECT)-$*.log >> $@
	echo unixsocket $(WORK_DIR)/$(SERVER_PROJECT)-$*.sock >> $@
	echo unixsocketperm 755 >> $@
	echo save \"\" >> $@
	echo slaveof 127.0.0.1 6379 >> $@

# Password-protected node (port 6382)
$(WORK_DIR)/$(SERVER_PROJECT)-6382.conf:
	@mkdir -p $(@D)
	echo port 6382 >> $@
	echo daemonize yes >> $@
	echo protected-mode no >> $@
	echo bind 0.0.0.0 >> $@
	echo notify-keyspace-events Ex >> $@
	echo pidfile $(WORK_DIR)/$(SERVER_PROJECT)-6382.pid >> $@
	echo logfile $(WORK_DIR)/$(SERVER_PROJECT)-6382.log >> $@
	echo unixsocket $(WORK_DIR)/$(SERVER_PROJECT)-6382.sock >> $@
	echo unixsocketperm 755 >> $@
	echo "requirepass foobared" >> $@
	echo "user default on #1b58ee375b42e41f0e48ef2ff27d10a5b1f6924a9acdcdba7cae868e7adce6bf ~* +@all" >> $@
	echo "user spring on #3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7 +@all" >> $@
	echo save \"\" >> $@

$(WORK_DIR)/$(SERVER_PROJECT)-%.pid: $(WORK_DIR)/$(SERVER_PROJECT)-%.conf $(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-server
	$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-server $<

server-standalone-start: $(WORK_DIR)/$(SERVER_PROJECT)-6379.pid $(WORK_DIR)/$(SERVER_PROJECT)-6380.pid $(WORK_DIR)/$(SERVER_PROJECT)-6381.pid $(WORK_DIR)/$(SERVER_PROJECT)-6382.pid

server-standalone-stop: stop-6379 stop-6380 stop-6381 stop-6382

# ============================================================================
# Sentinel Configuration
# ============================================================================

.PRECIOUS: $(WORK_DIR)/sentinel-%.conf

$(WORK_DIR)/sentinel-%.conf:
	@mkdir -p $(@D)
	echo port $* >> $@
	echo daemonize yes >> $@
	echo protected-mode no >> $@
	echo bind 0.0.0.0 >> $@
	echo pidfile $(WORK_DIR)/sentinel-$*.pid >> $@
	echo logfile $(WORK_DIR)/sentinel-$*.log >> $@
	echo save \"\" >> $@
	echo sentinel monitor mymaster 127.0.0.1 6379 2 >> $@

# Password-protected Sentinel
$(WORK_DIR)/sentinel-26382.conf:
	@mkdir -p $(@D)
	echo port 26382 >> $@
	echo daemonize yes >> $@
	echo protected-mode no >> $@
	echo bind 0.0.0.0 >> $@
	echo pidfile $(WORK_DIR)/sentinel-26382.pid >> $@
	echo logfile $(WORK_DIR)/sentinel-26382.log >> $@
	echo save \"\" >> $@
	echo "requirepass foobared" >> $@
	echo "user default on #1b58ee375b42e41f0e48ef2ff27d10a5b1f6924a9acdcdba7cae868e7adce6bf ~* +@all" >> $@
	echo "user spring on #3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7 +@all" >> $@
	echo sentinel monitor mymaster 127.0.0.1 6382 2 >> $@
	echo sentinel auth-pass mymaster foobared >> $@

$(WORK_DIR)/sentinel-%.pid: $(WORK_DIR)/sentinel-%.conf $(WORK_DIR)/$(SERVER_PROJECT)-6379.pid $(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-server
	$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-server $< --sentinel

server-sentinel-start: $(WORK_DIR)/sentinel-26379.pid $(WORK_DIR)/sentinel-26380.pid $(WORK_DIR)/sentinel-26381.pid $(WORK_DIR)/sentinel-26382.pid

server-sentinel-stop: stop-26379 stop-26380 stop-26381 stop-26382

# ============================================================================
# Cluster Configuration
# ============================================================================

.PRECIOUS: $(WORK_DIR)/cluster-%.conf

$(WORK_DIR)/cluster-%.conf:
	@mkdir -p $(@D)
	echo port $* >> $@
	echo protected-mode no >> $@
	echo bind 0.0.0.0 >> $@
	echo cluster-enabled yes >> $@
	echo cluster-config-file $(WORK_DIR)/nodes-$*.conf >> $@
	echo cluster-node-timeout 5 >> $@
	echo pidfile $(WORK_DIR)/cluster-$*.pid >> $@
	echo logfile $(WORK_DIR)/cluster-$*.log >> $@
	echo save \"\" >> $@

$(WORK_DIR)/cluster-%.pid: $(WORK_DIR)/cluster-%.conf $(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-server
	$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-server $< &

server-cluster-start: $(WORK_DIR)/cluster-7379.pid $(WORK_DIR)/cluster-7380.pid $(WORK_DIR)/cluster-7381.pid $(WORK_DIR)/cluster-7382.pid
	sleep 1

$(WORK_DIR)/meet-%:
	-$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli -p $* cluster meet 127.0.0.1 7379

# Replica node for cluster
$(WORK_DIR)/meet-7382:
	-$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli -p 7382 cluster meet 127.0.0.1 7379
	sleep 2
	-$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli -p 7382 cluster replicate $$($(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli -p 7379 cluster myid)

cluster-meet: $(WORK_DIR)/meet-7380 $(WORK_DIR)/meet-7381 $(WORK_DIR)/meet-7382
	sleep 1

cluster-slots:
	-$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli -p 7379 cluster addslots $$(seq 0 5460)
	-$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli -p 7380 cluster addslots $$(seq 5461 10922)
	-$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli -p 7381 cluster addslots $$(seq 10923 16383)

server-cluster-init: server-cluster-start cluster-meet cluster-slots

server-cluster-stop: stop-7379 stop-7380 stop-7381 stop-7382

# ============================================================================
# Server Management - Global
# ============================================================================

stop-%: $(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli
	-$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli -p $* shutdown

stop-6382: $(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli
	-$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli -a foobared -p 6382 shutdown

stop-26382: $(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli
	-$(WORK_DIR)/$(SERVER_PROJECT)/bin/$(SERVER_PROJECT)-cli -a foobared -p 26382 shutdown

server-start: server-standalone-start server-sentinel-start server-cluster-init

server-stop: server-standalone-stop server-sentinel-stop server-cluster-stop

clean:
	rm -rf $(WORK_DIR)/*.conf $(WORK_DIR)/*.pid $(WORK_DIR)/*.log dump.rdb

clobber:
	rm -rf $(WORK_DIR)

# ============================================================================
# Java Engine
# ============================================================================

JAVA_JAR=java/target/resp-bench-java-1.0.0-SNAPSHOT-jar-with-dependencies.jar
DRIVER?=configs/drivers/example-jedis-standalone.json
WORKLOAD?=configs/workloads/example-workload.json
SERVER?=localhost:6379
METRICS_OUTPUT?=output/metrics.ndjson

java-build:
	cd java && mvn clean package -DskipTests

java-test:
	cd java && mvn test

java-integration-test: server-standalone-start
	sleep 1
	cd java && VALKEY_HOST=localhost VALKEY_PORT=6379 mvn test -DincludeIntegrationTests
	$(MAKE) server-standalone-stop

java-run: java-build
	java -jar $(JAVA_JAR) \
		--server $(SERVER) \
		--driver $(DRIVER) \
		--workload $(WORKLOAD) \
		--metrics $(METRICS_OUTPUT)

java-run-cluster: java-build
	java -jar $(JAVA_JAR) \
		--server localhost:7379,localhost:7380,localhost:7381 \
		--driver $(DRIVER) \
		--workload $(WORKLOAD) \
		--metrics $(METRICS_OUTPUT)

java-clean:
	cd java && mvn clean

java-info: java-build
	java -jar $(JAVA_JAR) --info

# ============================================================================
# Python Engine (Placeholder)
# ============================================================================

python-build:
	@echo "Python engine not yet implemented"
	@echo "Placeholder for: cd python && pip install -e ."

python-test:
	@echo "Python engine not yet implemented"
	@echo "Placeholder for: cd python && pytest"

python-run:
	@echo "Python engine not yet implemented"
	@echo "Placeholder for: python -m resp_bench --server $(SERVER) --driver $(DRIVER) --workload $(WORKLOAD)"

python-clean:
	@echo "Python engine not yet implemented"
	@echo "Placeholder for: cd python && rm -rf __pycache__ *.egg-info dist build"

# ============================================================================
# Config Editor
# ============================================================================

config-editor-build:
	cd config-editor && npm install && npm run build

config-editor-dev:
	cd config-editor && npm install && npm run dev

# ============================================================================
# All Languages
# ============================================================================

build-all: java-build python-build

test-all: java-test python-test

clean-all: java-clean python-clean clean
