import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { RecordingClient } from '../../src/client/impl/recordingClient.js';
import { ConfigLoader } from '../../src/config/loader.js';

function config(specific: Record<string, unknown> = {}) {
  return ConfigLoader.parseDriverConfig({
    driver_id: 'recording',
    mode: 'standalone',
    specific_driver_config: specific,
  });
}

async function connected(specific: Record<string, unknown> = {}): Promise<RecordingClient> {
  const client = new RecordingClient();
  await client.connect('localhost', 6379, config(specific));
  return client;
}

describe('RecordingClient', () => {
  it('records the operations it was asked to perform', async () => {
    const client = await connected();
    await client.ping();
    await client.set('bench:0000000001', Buffer.from('abc', 'latin1'));
    await client.get('bench:0000000001');
    await client.close();

    assert.deepEqual(
      client.operations.map((op) => op.command),
      ['CONNECT', 'PING', 'SET', 'GET', 'CLOSE'],
    );
    // The keys the engine passed through are observable, so a key-generation
    // regression shows up as a wrong key here rather than as silent drift.
    assert.deepEqual(
      client.operations.filter((op) => op.key !== null).map((op) => op.key),
      ['bench:0000000001', 'bench:0000000001'],
    );
    assert.ok(client.operations.every((op) => op.success));
  });

  it('behaves like a key-value store for GET after SET', async () => {
    const client = await connected();
    assert.equal((await client.get('missing')).value, null);
    await client.set('k', Buffer.from('hello', 'latin1'));
    assert.equal((await client.get('k')).value, 'hello');
  });

  it('returns the cross-engine success values', async () => {
    const client = await connected();
    assert.equal((await client.ping()).value, 'PONG');
    assert.equal((await client.set('k', Buffer.alloc(4))).value, 'OK');
  });

  it('injects errors at error_rate 1.0 with the configured message', async () => {
    const client = await connected({ error_rate: 1.0, error_message: 'boom' });
    for (const result of [
      await client.ping(),
      await client.get('k'),
      await client.set('k', Buffer.alloc(1)),
    ]) {
      assert.equal(result.error?.message, 'boom');
      assert.equal(result.value, null);
    }
    assert.ok(client.operations.slice(1).every((op) => !op.success));
  });

  it('never injects errors while in warmup mode', async () => {
    // Mirrors Java's setWarmupMode: the engine's warmup fail-fast must not be
    // tripped by errors the workload deliberately injects.
    const client = await connected({ error_rate: 1.0 });
    client.setWarmupMode(true);
    assert.equal((await client.ping()).error, undefined);
    client.setWarmupMode(false);
    assert.notEqual((await client.ping()).error, undefined);
  });

  it('does not fail a SET when its own error is injected mid-store', async () => {
    // A failed SET must not store, or a later GET would report data the server
    // never accepted.
    const client = await connected({ error_rate: 1.0 });
    await client.set('k', Buffer.from('nope', 'latin1'));
    client.setWarmupMode(true);
    assert.equal((await client.get('k')).value, null);
  });

  it('applies a configured operation delay', async () => {
    const client = await connected({ operation_delay_micros: 5000 });
    const result = await client.ping();
    assert.ok(result.latencyMicros >= 3000, `latency ${result.latencyMicros}us too low for a 5ms delay`);
  });

  it('reports zero-ish latency with no configured delay', async () => {
    const client = await connected();
    assert.ok((await client.ping()).latencyMicros < 5000);
  });

  it('reports a fixed driver version', async () => {
    assert.equal(new RecordingClient().driverVersion(), '1.0.0');
  });
});
