import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { BenchmarkClientFactory } from '../../src/client/factory.js';
import { packageVersion } from '../../src/client/driverVersion.js';
import { CommandFactory } from '../../src/command/factory.js';
import { CommandConfig } from '../../src/config/commandConfig.js';
import { SetCommand } from '../../src/command/impl/setCommand.js';

describe('BenchmarkClientFactory', () => {
  it('registers the node driver ids', () => {
    assert.deepEqual(BenchmarkClientFactory.supportedDrivers(), [
      'valkey-glide-node',
      'ioredis',
      'iovalkey',
      'recording',
    ]);
  });

  it('does not register a bare valkey-glide', () => {
    // scripts/run_benchmark_matrix.py's DRIVER_ENGINE_MAP is global and already
    // maps "valkey-glide" to the Java engine. Claiming it here would silently
    // reroute Java's glide runs to Node.
    assert.equal(BenchmarkClientFactory.supportedDrivers().includes('valkey-glide'), false);
  });

  it('describes every driver for --info', () => {
    for (const { driverId, description } of BenchmarkClientFactory.describe()) {
      assert.ok(driverId.length > 0);
      assert.ok(description.length > 0, `${driverId} has no description`);
    }
  });

  it('rejects an unknown driver with the supported list', async () => {
    await assert.rejects(
      () => BenchmarkClientFactory.create('memcached'),
      /Unknown driver: memcached\. Supported: valkey-glide-node, ioredis, iovalkey, recording/,
    );
  });

  it('is case-insensitive on driver_id', async () => {
    const client = await BenchmarkClientFactory.create('IoRedis');
    assert.ok(client);
  });

  it('loads the recording driver without a server', async () => {
    const client = await BenchmarkClientFactory.create('recording');
    assert.equal(client.driverVersion(), '1.0.0');
  });
});

describe('CommandFactory', () => {
  it('supports the cross-engine command set', () => {
    assert.deepEqual(CommandFactory.supportedCommands(), ['get', 'set', 'ping']);
  });

  it('builds each command with its weight and name', () => {
    const get = CommandFactory.create(new CommandConfig({ command: 'get', weight: 0.8 }));
    assert.equal(get.name, 'GET');
    assert.equal(get.weight, 0.8);
    assert.equal(get.usesKey, true);

    const ping = CommandFactory.create(new CommandConfig({ command: 'ping' }));
    assert.equal(ping.name, 'PING');
    // PING must not consume a generated key: Java's PingCommand ignores the key
    // generator, so advancing it here would shift the shared key sequence.
    assert.equal(ping.usesKey, false);
  });

  it('accepts an upper-case command name', () => {
    assert.equal(CommandFactory.create(new CommandConfig({ command: 'SET' })).name, 'SET');
  });

  it('rejects an unknown command', () => {
    assert.throws(
      () => CommandFactory.create(new CommandConfig({ command: 'incr' })),
      /Unknown command: incr\. Supported: get, set, ping/,
    );
  });

  it('createAll preserves order', () => {
    const commands = CommandFactory.createAll([
      new CommandConfig({ command: 'get', weight: 0.8 }),
      new CommandConfig({ command: 'set', weight: 0.2 }),
    ]);
    assert.deepEqual(
      commands.map((c) => c.name),
      ['GET', 'SET'],
    );
  });
});

describe('SetCommand payload', () => {
  it('generates exactly data_size_bytes using the cross-engine pattern', () => {
    // Ruby and Python use the same repeated "0123456789ABCDEF" filler.
    for (const size of [1, 16, 32, 256, 512, 1000]) {
      assert.equal(SetCommand.generateValue(size).length, size);
    }
    assert.equal(SetCommand.generateValue(20).toString('latin1'), '0123456789ABCDEF0123');
  });
});

describe('packageVersion', () => {
  it('reads a version despite a restricted exports map', () => {
    // @valkey/valkey-glide does not export ./package.json, so a plain
    // require('<pkg>/package.json') throws ERR_PACKAGE_PATH_NOT_EXPORTED.
    assert.match(packageVersion('@valkey/valkey-glide'), /^\d+\.\d+\.\d+/);
    assert.match(packageVersion('ioredis'), /^\d+\.\d+\.\d+/);
    assert.match(packageVersion('iovalkey'), /^\d+\.\d+\.\d+/);
  });

  it('returns "unknown" for a package that is not installed', () => {
    assert.equal(packageVersion('definitely-not-installed-xyz'), 'unknown');
  });
});
