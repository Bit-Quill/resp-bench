import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { CommandFactory } from '../../src/command/factory.js';
import { CommandConfig } from '../../src/config/commandConfig.js';
import { CommandSelector } from '../../src/engine/commandSelector.js';

function selectorFor(weights: Array<[string, number]>): CommandSelector {
  return new CommandSelector(
    CommandFactory.createAll(
      weights.map(([command, weight]) => new CommandConfig({ command, weight })),
    ),
  );
}

function distribution(selector: CommandSelector, draws: number): Map<string, number> {
  const counts = new Map<string, number>();
  for (let i = 0; i < draws; i++) {
    const name = selector.select().name;
    counts.set(name, (counts.get(name) ?? 0) + 1);
  }
  return counts;
}

describe('CommandSelector', () => {
  it('respects an 80/20 weighting within tolerance', () => {
    const draws = 20_000;
    const counts = distribution(selectorFor([['get', 0.8], ['set', 0.2]]), draws);
    const getShare = (counts.get('GET') ?? 0) / draws;
    assert.ok(Math.abs(getShare - 0.8) < 0.02, `GET share ${getShare.toFixed(3)} not near 0.8`);
  });

  it('normalizes weights that do not sum to 1', () => {
    const draws = 20_000;
    const counts = distribution(selectorFor([['get', 0.25], ['set', 0.25]]), draws);
    const getShare = (counts.get('GET') ?? 0) / draws;
    assert.ok(Math.abs(getShare - 0.5) < 0.02, `GET share ${getShare.toFixed(3)} not near 0.5`);
  });

  it('always returns the sole command', () => {
    const selector = selectorFor([['ping', 1.0]]);
    for (let i = 0; i < 100; i++) assert.equal(selector.select().name, 'PING');
  });

  it('never returns a zero-weight command', () => {
    const counts = distribution(selectorFor([['get', 1.0], ['set', 0]]), 5000);
    assert.equal(counts.get('SET') ?? 0, 0);
  });

  it('falls back to a command when every weight is zero', () => {
    // Guards against dividing by a zero total and returning undefined.
    const selector = selectorFor([['get', 0], ['set', 0]]);
    for (let i = 0; i < 100; i++) assert.ok(['GET', 'SET'].includes(selector.select().name));
  });

  it('rejects an empty command list', () => {
    assert.throws(() => new CommandSelector([]), /at least one command/);
  });
});
