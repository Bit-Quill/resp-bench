/** Phase completion criteria: run for a duration, or until a request count. */

export class CompletionConfig {
  readonly type: string;
  readonly seconds: number | null;
  readonly requests: number | null;

  constructor(init: { type: string; seconds?: number | null; requests?: number | null }) {
    this.type = init.type;
    this.seconds = init.seconds ?? null;
    this.requests = init.requests ?? null;
  }

  isDurationBased(): boolean {
    return this.type === 'duration';
  }

  isRequestBased(): boolean {
    return this.type === 'requests';
  }

  durationSeconds(): number {
    return this.seconds ?? 0;
  }

  totalRequests(): number {
    return this.requests ?? 0;
  }
}
