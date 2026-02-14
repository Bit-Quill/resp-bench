import { useState } from 'react'

interface DriverConfig {
  schema_version: string
  description: string
  driver_id: string
  mode: string
  tls?: { cert_path?: string; key_path?: string; ca_cert_path?: string; verify_peer?: boolean }
  auth?: { username?: string; password?: string }
  specific_driver_config?: Record<string, unknown>
}

interface WorkloadConfig {
  schema_version: string
  benchmark_profile: { name: string; description: string; version: string }
  phases: Phase[]
}

interface Phase {
  id: string
  description: string
  connections: number
  cps_limit: number
  rps_limit: number
  pipeline_depth: number
  warmup_requests: number
  completion: { type: string; seconds?: number; requests?: number }
  keyspace: { keys_count: number; key_size_bytes: number; key_prefix: string; generation_alg: string; seed?: number }
  commands: CommandConfig[]
}

interface CommandConfig {
  command: string
  weight: number
  data_size_bytes?: number
}

const DRIVERS = ['jedis', 'lettuce', 'valkey-glide', 'redisson', 'spring-data-valkey', 'spring-data-redis']
const COMMANDS = ['set', 'get', 'ping']
const ALGORITHMS = ['sequential_int', 'uniform_rand']

const isSpringDriver = (driverId: string) => driverId.startsWith('spring-data-')

const getSecondaryDrivers = (driverId: string): string[] => {
  if (driverId === 'spring-data-redis') {
    return ['jedis', 'lettuce']
  }
  if (driverId === 'spring-data-valkey') {
    return ['jedis', 'lettuce', 'valkey-glide']
  }
  return []
}

const getDefaultSecondaryDriver = (driverId: string): string => {
  if (driverId === 'spring-data-redis') {
    return 'lettuce'
  }
  return 'valkey-glide'
}

const DEFAULT_PHASE: Phase = {
  id: 'PHASE1',
  description: 'Test phase',
  connections: 50,
  cps_limit: -1,
  rps_limit: -1,
  pipeline_depth: 1,
  warmup_requests: 1,
  completion: { type: 'duration', seconds: 60 },
  keyspace: { keys_count: 100000, key_size_bytes: 16, key_prefix: 'bench:', generation_alg: 'sequential_int' },
  commands: [{ command: 'set', weight: 1.0, data_size_bytes: 256 }]
}

const inputStyle = { padding: '4px 8px', marginLeft: '8px', width: '100px' }
const labelStyle = { display: 'inline-block', marginBottom: '8px' }
const sectionStyle = { border: '1px solid #ccc', padding: '15px', marginBottom: '15px', borderRadius: '4px' }

export default function App() {
  const [tab, setTab] = useState<'driver' | 'workload'>('driver')
  const [driverConfig, setDriverConfig] = useState<DriverConfig>({
    schema_version: '1.0',
    description: 'Driver configuration',
    driver_id: 'jedis',
    mode: 'standalone'
  })
  const [workloadConfig, setWorkloadConfig] = useState<WorkloadConfig>({
    schema_version: '1.0',
    benchmark_profile: { name: 'Test', description: 'Test workload', version: '1.0.0' },
    phases: [{ ...DEFAULT_PHASE }]
  })

  const updatePhase = (index: number, updates: Partial<Phase>) => {
    const phases = [...workloadConfig.phases]
    phases[index] = { ...phases[index], ...updates }
    setWorkloadConfig({ ...workloadConfig, phases })
  }

  const updateCommand = (phaseIndex: number, cmdIndex: number, updates: Partial<CommandConfig>) => {
    const phases = [...workloadConfig.phases]
    const commands = [...phases[phaseIndex].commands]
    commands[cmdIndex] = { ...commands[cmdIndex], ...updates }
    phases[phaseIndex] = { ...phases[phaseIndex], commands }
    setWorkloadConfig({ ...workloadConfig, phases })
  }

  const addCommand = (phaseIndex: number) => {
    const phases = [...workloadConfig.phases]
    const newCmd: CommandConfig = { command: 'get', weight: 0.0 }
    phases[phaseIndex] = { ...phases[phaseIndex], commands: [...phases[phaseIndex].commands, newCmd] }
    setWorkloadConfig({ ...workloadConfig, phases })
  }

  const removeCommand = (phaseIndex: number, cmdIndex: number) => {
    const phases = [...workloadConfig.phases]
    const commands = phases[phaseIndex].commands.filter((_, i) => i !== cmdIndex)
    phases[phaseIndex] = { ...phases[phaseIndex], commands }
    setWorkloadConfig({ ...workloadConfig, phases })
  }

  const getWeightSum = (commands: CommandConfig[]) => {
    return commands.reduce((sum, cmd) => sum + cmd.weight, 0)
  }

  const exportJson = () => {
    const config = tab === 'driver' ? driverConfig : workloadConfig
    const blob = new Blob([JSON.stringify(config, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = tab === 'driver' ? 'driver-config.json' : 'workload-config.json'
    a.click()
  }

  const importJson = () => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.json'
    input.onchange = async (e) => {
      const file = (e.target as HTMLInputElement).files?.[0]
      if (!file) return
      const text = await file.text()
      try {
        const json = JSON.parse(text)
        if (tab === 'driver') {
          setDriverConfig(json)
        } else {
          setWorkloadConfig(json)
        }
      } catch (err) {
        alert('Invalid JSON file')
      }
    }
    input.click()
  }

  return (
    <div style={{ fontFamily: 'system-ui', padding: '20px', maxWidth: '1000px', margin: '0 auto' }}>
      <h1>Benchmark Config Editor</h1>
      <div style={{ marginBottom: '20px' }}>
        <button onClick={() => setTab('driver')} style={{ marginRight: '10px', fontWeight: tab === 'driver' ? 'bold' : 'normal', padding: '8px 16px' }}>
          Driver Config
        </button>
        <button onClick={() => setTab('workload')} style={{ fontWeight: tab === 'workload' ? 'bold' : 'normal', padding: '8px 16px' }}>
          Workload Config
        </button>
      </div>

      {tab === 'driver' && (
        <div>
          <h2>Driver Configuration</h2>
          <div style={sectionStyle}>
            <div style={labelStyle}>
              <label>Driver:
                <select value={driverConfig.driver_id} onChange={e => setDriverConfig({ ...driverConfig, driver_id: e.target.value })} style={inputStyle}>
                  {DRIVERS.map(d => <option key={d} value={d}>{d}</option>)}
                </select>
              </label>
            </div>
            <br />
            <div style={labelStyle}>
              <label>Mode:
                <select value={driverConfig.mode} onChange={e => setDriverConfig({ ...driverConfig, mode: e.target.value })} style={inputStyle}>
                  <option value="standalone">standalone</option>
                  <option value="cluster">cluster</option>
                </select>
              </label>
            </div>
            <br />
            <div style={labelStyle}>
              <label>Description:
                <input
                  value={driverConfig.description}
                  onChange={e => setDriverConfig({ ...driverConfig, description: e.target.value })}
                  style={{ ...inputStyle, width: '300px' }}
                />
              </label>
            </div>
          </div>

          {isSpringDriver(driverConfig.driver_id) && (
            <div style={sectionStyle}>
              <h3 style={{ marginTop: 0 }}>Specific Driver Configuration</h3>
              <p style={{ fontSize: '12px', color: '#666', marginTop: 0 }}>
                Spring Data drivers require a secondary driver (the actual client library to use).
              </p>
              <div style={labelStyle}>
                <label>Secondary Driver:
                  <select
                    value={(driverConfig.specific_driver_config?.secondary_driver_id as string) || getDefaultSecondaryDriver(driverConfig.driver_id)}
                    onChange={e => setDriverConfig({
                      ...driverConfig,
                      specific_driver_config: {
                        ...driverConfig.specific_driver_config,
                        secondary_driver_id: e.target.value
                      }
                    })}
                    style={inputStyle}
                  >
                    {getSecondaryDrivers(driverConfig.driver_id).map(d => <option key={d} value={d}>{d}</option>)}
                  </select>
                </label>
              </div>
            </div>
          )}

          <div style={{ ...sectionStyle, opacity: 0.6, background: '#f8f8f8' }}>
            <h3 style={{ marginTop: 0, display: 'flex', alignItems: 'center', gap: '10px' }}>
              Security
              <span style={{ fontSize: '12px', fontWeight: 'normal', color: '#e67e22', background: '#fef5e7', padding: '2px 8px', borderRadius: '4px' }}>
                Not currently supported
              </span>
            </h3>
            <p style={{ fontSize: '12px', color: '#666', marginTop: 0, marginBottom: '15px' }}>
              TLS and Authentication features are planned for a future release.
            </p>
            <div style={labelStyle}>
              <label style={{ color: '#999', cursor: 'not-allowed' }}>
                <input type="checkbox" disabled checked={false} />
                Enable TLS
              </label>
            </div>
            <br />
            <div style={labelStyle}>
              <label style={{ color: '#999', cursor: 'not-allowed' }}>
                <input type="checkbox" disabled checked={false} />
                Enable Auth
              </label>
            </div>
          </div>
        </div>
      )}

      {tab === 'workload' && (
        <div>
          <h2>Workload Configuration</h2>
          <div style={sectionStyle}>
            <h3 style={{ marginTop: 0 }}>Benchmark Profile</h3>
            <div style={labelStyle}>
              <label>Name:
                <input
                  value={workloadConfig.benchmark_profile.name}
                  onChange={e => setWorkloadConfig({ ...workloadConfig, benchmark_profile: { ...workloadConfig.benchmark_profile, name: e.target.value } })}
                  style={inputStyle}
                />
              </label>
            </div>
            <br />
            <div style={labelStyle}>
              <label>Description:
                <input
                  value={workloadConfig.benchmark_profile.description}
                  onChange={e => setWorkloadConfig({ ...workloadConfig, benchmark_profile: { ...workloadConfig.benchmark_profile, description: e.target.value } })}
                  style={{ ...inputStyle, width: '300px' }}
                />
              </label>
            </div>
            <br />
            <div style={labelStyle}>
              <label>Version:
                <input
                  value={workloadConfig.benchmark_profile.version}
                  onChange={e => setWorkloadConfig({ ...workloadConfig, benchmark_profile: { ...workloadConfig.benchmark_profile, version: e.target.value } })}
                  style={inputStyle}
                />
              </label>
            </div>
          </div>

          <h3>Phases</h3>
          {workloadConfig.phases.map((phase, i) => (
            <div key={i} style={{ ...sectionStyle, background: '#fafafa' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
                <b>Phase {i + 1}</b>
                <button onClick={() => setWorkloadConfig({ ...workloadConfig, phases: workloadConfig.phases.filter((_, j) => j !== i) })} style={{ color: 'red' }}>
                  Remove Phase
                </button>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' }}>
                <div style={labelStyle}>
                  <label>ID:
                    <input value={phase.id} onChange={e => updatePhase(i, { id: e.target.value })} style={inputStyle} />
                  </label>
                </div>
                <div style={labelStyle}>
                  <label>Description:
                    <input value={phase.description} onChange={e => updatePhase(i, { description: e.target.value })} style={{ ...inputStyle, width: '200px' }} />
                  </label>
                </div>
                <div style={labelStyle}>
                  <label>Connections:
                    <input type="number" value={phase.connections} onChange={e => updatePhase(i, { connections: +e.target.value })} style={inputStyle} />
                  </label>
                </div>
                <div style={labelStyle}>
                  <label>Pipeline Depth:
                    <input type="number" value={phase.pipeline_depth} onChange={e => updatePhase(i, { pipeline_depth: +e.target.value })} style={inputStyle} min={1} />
                  </label>
                </div>
                <div style={labelStyle}>
                  <label>RPS Limit:
                    <input type="number" value={phase.rps_limit} onChange={e => updatePhase(i, { rps_limit: +e.target.value })} style={inputStyle} />
                  </label>
                  <span style={{ fontSize: '12px', color: '#666' }}> (-1 = unlimited)</span>
                </div>
                <div style={labelStyle}>
                  <label>CPS Limit:
                    <input type="number" value={phase.cps_limit} onChange={e => updatePhase(i, { cps_limit: +e.target.value })} style={inputStyle} />
                  </label>
                  <span style={{ fontSize: '12px', color: '#666' }}> (-1 = unlimited)</span>
                </div>
                <div style={labelStyle}>
                  <label>Warmup Requests:
                    <input type="number" value={phase.warmup_requests} onChange={e => updatePhase(i, { warmup_requests: +e.target.value })} style={inputStyle} min={0} />
                  </label>
                </div>
              </div>

              <div style={{ marginTop: '15px', padding: '10px', background: '#f0f0f0', borderRadius: '4px' }}>
                <b>Completion</b>
                <div style={{ marginTop: '10px' }}>
                  <label>Type:
                    <select
                      value={phase.completion.type}
                      onChange={e => updatePhase(i, { completion: { type: e.target.value, ...(e.target.value === 'duration' ? { seconds: 60 } : { requests: 100000 }) } })}
                      style={inputStyle}
                    >
                      <option value="duration">duration</option>
                      <option value="requests">requests</option>
                    </select>
                  </label>
                  {phase.completion.type === 'duration' && (
                    <label style={{ marginLeft: '20px' }}>Seconds:
                      <input
                        type="number"
                        value={phase.completion.seconds}
                        onChange={e => updatePhase(i, { completion: { ...phase.completion, seconds: +e.target.value } })}
                        style={inputStyle}
                      />
                    </label>
                  )}
                  {phase.completion.type === 'requests' && (
                    <label style={{ marginLeft: '20px' }}>Requests:
                      <input
                        type="number"
                        value={phase.completion.requests}
                        onChange={e => updatePhase(i, { completion: { ...phase.completion, requests: +e.target.value } })}
                        style={inputStyle}
                      />
                    </label>
                  )}
                </div>
              </div>

              <div style={{ marginTop: '15px', padding: '10px', background: '#f0f0f0', borderRadius: '4px' }}>
                <b>Keyspace</b>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px', marginTop: '10px' }}>
                  <div style={labelStyle}>
                    <label>Keys Count:
                      <input
                        type="number"
                        value={phase.keyspace.keys_count}
                        onChange={e => updatePhase(i, { keyspace: { ...phase.keyspace, keys_count: +e.target.value } })}
                        style={inputStyle}
                      />
                    </label>
                  </div>
                  <div style={labelStyle}>
                    <label>Key Size (bytes):
                      <input
                        type="number"
                        value={phase.keyspace.key_size_bytes}
                        onChange={e => updatePhase(i, { keyspace: { ...phase.keyspace, key_size_bytes: +e.target.value } })}
                        style={inputStyle}
                      />
                    </label>
                  </div>
                  <div style={labelStyle}>
                    <label>Key Prefix:
                      <input
                        value={phase.keyspace.key_prefix}
                        onChange={e => updatePhase(i, { keyspace: { ...phase.keyspace, key_prefix: e.target.value } })}
                        style={inputStyle}
                      />
                    </label>
                  </div>
                  <div style={labelStyle}>
                    <label>Algorithm:
                      <select
                        value={phase.keyspace.generation_alg}
                        onChange={e => updatePhase(i, { keyspace: { ...phase.keyspace, generation_alg: e.target.value } })}
                        style={inputStyle}
                      >
                        {ALGORITHMS.map(a => <option key={a} value={a}>{a}</option>)}
                      </select>
                    </label>
                  </div>
                </div>
              </div>

              <div style={{ marginTop: '15px', padding: '10px', background: '#f0f0f0', borderRadius: '4px' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <b>Commands</b>
                  <span style={{ fontSize: '12px', color: getWeightSum(phase.commands) === 1 ? 'green' : 'red' }}>
                    Weight sum: {getWeightSum(phase.commands).toFixed(2)} {getWeightSum(phase.commands) !== 1 && '(must be 1.0)'}
                  </span>
                </div>
                {phase.commands.map((cmd, j) => (
                  <div key={j} style={{ display: 'flex', alignItems: 'center', gap: '10px', marginTop: '10px' }}>
                    <select value={cmd.command} onChange={e => updateCommand(i, j, { command: e.target.value })} style={{ padding: '4px' }}>
                      {COMMANDS.map(c => <option key={c} value={c}>{c}</option>)}
                    </select>
                    <label>Weight:
                      <input
                        type="number"
                        value={cmd.weight}
                        onChange={e => updateCommand(i, j, { weight: +e.target.value })}
                        style={{ ...inputStyle, width: '70px' }}
                        step={0.1}
                        min={0}
                        max={1}
                      />
                    </label>
                    {(cmd.command === 'set') && (
                      <label>Data Size:
                        <input
                          type="number"
                          value={cmd.data_size_bytes || 256}
                          onChange={e => updateCommand(i, j, { data_size_bytes: +e.target.value })}
                          style={{ ...inputStyle, width: '80px' }}
                        />
                      </label>
                    )}
                    <button onClick={() => removeCommand(i, j)} style={{ color: 'red', padding: '2px 8px' }}>×</button>
                  </div>
                ))}
                <button onClick={() => addCommand(i)} style={{ marginTop: '10px', padding: '4px 12px' }}>+ Add Command</button>
              </div>
            </div>
          ))}
          <button
            onClick={() => setWorkloadConfig({ ...workloadConfig, phases: [...workloadConfig.phases, { ...DEFAULT_PHASE, id: 'PHASE' + (workloadConfig.phases.length + 1) }] })}
            style={{ padding: '8px 16px' }}
          >
            + Add Phase
          </button>
        </div>
      )}

      <hr style={{ margin: '30px 0' }} />
      <h3>JSON Preview</h3>
      <pre style={{ background: '#f5f5f5', padding: '15px', overflow: 'auto', maxHeight: '300px', borderRadius: '4px', fontSize: '12px' }}>
        {JSON.stringify(tab === 'driver' ? driverConfig : workloadConfig, null, 2)}
      </pre>
      <div style={{ marginTop: '15px', display: 'flex', gap: '10px' }}>
        <button onClick={exportJson} style={{ padding: '8px 16px' }}>Export JSON</button>
        <button onClick={importJson} style={{ padding: '8px 16px' }}>Import JSON</button>
      </div>
    </div>
  )
}