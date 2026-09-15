/** A whole workload: a benchmark profile plus an ordered list of phases. */

import type { PhaseConfig } from './phaseConfig.js';

export interface BenchmarkProfile {
  name?: string;
  description?: string;
  version?: string;
}

export class WorkloadConfig {
  readonly schemaVersion: string;
  readonly benchmarkProfile: BenchmarkProfile;
  readonly phases: PhaseConfig[];

  constructor(init: {
    schemaVersion?: string;
    benchmarkProfile?: BenchmarkProfile | null;
    phases: PhaseConfig[];
  }) {
    this.schemaVersion = init.schemaVersion ?? '1.0';
    this.benchmarkProfile = init.benchmarkProfile ?? {};
    this.phases = init.phases;
  }

  name(): string {
    return this.benchmarkProfile.name ?? 'unnamed';
  }
}
