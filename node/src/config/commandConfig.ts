/** A single weighted command entry within a phase. */

/**
 * Default SET payload size. 256 is the cross-engine default: Java
 * (SetCommand.java `getDataSizeBytesOrDefault(256)`), C# (SetCommand.cs),
 * Ruby and Python all use it. Do not change without changing them too.
 */
export const DEFAULT_DATA_SIZE_BYTES = 256;

export class CommandConfig {
  readonly command: string;
  readonly weight: number;
  readonly dataSizeBytes: number;

  constructor(init: { command: string; weight?: number | null; dataSizeBytes?: number | null }) {
    this.command = init.command.toLowerCase();
    // A missing weight defaults to 1.0 (matching Java), rather than NaN.
    this.weight = init.weight ?? 1.0;
    this.dataSizeBytes = init.dataSizeBytes ?? DEFAULT_DATA_SIZE_BYTES;
  }
}
