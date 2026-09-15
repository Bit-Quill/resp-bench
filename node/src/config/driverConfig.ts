/**
 * Driver (client library) configuration.
 *
 * Maps to configs/schemas/driver-config.schema.json. Field names and defaults
 * mirror the Java/Ruby/Python engines so the same JSON files work unchanged
 * across every engine.
 */

export interface TlsConfig {
  enabled?: boolean;
  cert_path?: string;
  key_path?: string;
  ca_path?: string;
  verify_hostname?: boolean;
}

export interface AuthConfig {
  username?: string;
  password?: string;
}

export const DEFAULT_MODE = 'standalone';

export class DriverConfig {
  readonly schemaVersion: string;
  readonly description: string | null;
  readonly driverId: string;
  readonly mode: string;
  readonly commandTimeoutMs: number | null;
  readonly tls: TlsConfig | null;
  readonly auth: AuthConfig | null;
  readonly specificDriverConfig: Record<string, unknown>;

  constructor(init: {
    schemaVersion?: string;
    description?: string | null;
    driverId: string;
    mode?: string | null;
    commandTimeoutMs?: number | null;
    tls?: TlsConfig | null;
    auth?: AuthConfig | null;
    specificDriverConfig?: Record<string, unknown> | null;
  }) {
    this.schemaVersion = init.schemaVersion ?? '1.0';
    this.description = init.description ?? null;
    this.driverId = init.driverId;
    this.mode = init.mode ?? DEFAULT_MODE;
    this.commandTimeoutMs = init.commandTimeoutMs ?? null;
    this.tls = init.tls ?? null;
    this.auth = init.auth ?? null;
    this.specificDriverConfig = init.specificDriverConfig ?? {};
  }

  /** Secondary driver id for composite drivers (e.g. Java's spring-data-*). */
  secondaryDriverId(): string | null {
    const value = this.specificDriverConfig['secondary_driver_id'];
    return typeof value === 'string' ? value : null;
  }

  isStandalone(): boolean {
    return this.mode === 'standalone';
  }

  isCluster(): boolean {
    return this.mode === 'cluster';
  }

  isSentinel(): boolean {
    return this.mode === 'sentinel';
  }

  tlsEnabled(): boolean {
    return this.tls?.enabled === true;
  }

  /** Username/password only count as auth when at least one is non-empty. */
  hasAuth(): boolean {
    return Boolean(this.auth && (this.auth.password || this.auth.username));
  }
}
