import type { Transport } from '../stage/controller.ts'
import type { AudioLoader } from '../stage/audio.ts'
export type Scenario = 'normal' | 'duplicate' | 'expired' | 'old-session' | 'decode' | 'download' | 'timeout' | 'cancel' | 'stop' | 'disconnect'
export class MockStageTransport implements Transport {
  private handlers: Parameters<Transport['connect']>[0] | null = null
  private alive = false
  private release: (() => void) | null = null
  private playerId = ''
  private generation = Date.now()
  private sessionId = `mock-${crypto.randomUUID()}`
  private lastTask: Record<string, unknown> | null = null
  private ready = false
  private channel: BroadcastChannel | null = null
  heartbeatEnabled = true
  connect(handlers: Parameters<Transport['connect']>[0]) {
    this.handlers = handlers; this.alive = true
    this.channel = new BroadcastChannel('bigfish.mock.controls.v1')
    this.channel.onmessage = event => { if (event.data?.action === 'stop') this.scenario('stop') }
    handlers.open()
  }
  private emit(type: string, payload: Record<string, unknown>, sessionId = this.sessionId) {
    if (this.alive) this.handlers?.message(JSON.stringify({ schemaVersion: 1, messageId: crypto.randomUUID(), type, sessionId, payload }))
  }
  send(raw: string) {
    const msg = JSON.parse(raw)
    if (msg.type === 'PLAYER_HELLO') {
      this.playerId = msg.payload.playerId
      if (!navigator.locks) { this.emit('PLAYER_REJECTED', { reason: 'MOCK_UNAVAILABLE' }); return }
      void navigator.locks.request('bigfish.mock.stage.lease.v1', { ifAvailable: true }, async lock => {
        if (!this.alive) return
        if (!lock) { this.emit('PLAYER_REJECTED', { reason: 'PLAYER_ALREADY_ACTIVE' }); return }
        await new Promise<void>(resolve => {
          this.release = resolve
          this.emit('PLAYER_LEASE', { playerId: this.playerId, leaseGeneration: this.generation, expiresAt: new Date(Date.now() + 15000).toISOString(), heartbeatIntervalMs: 5000 })
        })
      }).catch(() => this.emit('PLAYER_REJECTED', { reason: 'MOCK_UNAVAILABLE' }))
    } else if (msg.type === 'PLAYER_READY') this.ready = true
    else if (msg.type === 'PLAYER_HEARTBEAT' && this.heartbeatEnabled) this.emit('HEARTBEAT_ACK', {
      playerId: this.playerId, leaseGeneration: this.generation, expiresAt: new Date(Date.now() + 15000).toISOString(),
    })
  }
  scenario(scenario: Scenario) {
    if (!this.alive) return
    if (scenario === 'stop') { this.emit('SESSION_STOPPED', { reason: 'MOCK_STOP' }); return }
    if (scenario === 'disconnect') { this.handlers?.close(); return }
    if (scenario === 'cancel') { if (this.lastTask) this.emit('CANCEL_TASK', { taskId: this.lastTask.taskId, reason: 'MOCK_CANCEL' }); return }
    if (scenario === 'duplicate') { if (this.lastTask) this.emit('PLAY_TASK', this.lastTask); return }
    if (!this.ready) return
    const task = { taskId: crypto.randomUUID(), sessionId: scenario === 'old-session' ? 'mock-old-session' : this.sessionId,
      configVersion: 1, type: 'COMMENT', text: '这是原创测试音，字幕和嘴型正在跟随实际播放；未调用语音合成。',
      audioUrl: `/api/v1/audio/mock-${scenario}`, priority: 50,
      expiresAt: new Date(Date.now() + (scenario === 'expired' ? -1000 : 60000)).toISOString() }
    if (!['expired', 'old-session'].includes(scenario)) this.lastTask = task
    this.emit('PLAY_TASK', task, task.sessionId)
  }
  close() {
    this.alive = false; this.ready = false; this.release?.(); this.release = null
    this.channel?.close(); this.channel = null; this.handlers = null
  }
}
// Original PCM tone generated in memory, not TTS, with audible pauses and varying amplitude.
export const mockAudio: AudioLoader = async (url, signal) => {
  if (url.endsWith('mock-download')) throw new Error('AUDIO_DOWNLOAD_FAILED')
  if (url.endsWith('mock-decode')) return new ArrayBuffer(12)
  if (url.endsWith('mock-timeout')) return new Promise((_, reject) => {
    if (signal.aborted) reject(new Error('aborted'))
    else signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true })
  })
  const rate = 24000, count = rate * 4, data = new ArrayBuffer(44 + count * 2), view = new DataView(data)
  const text = (offset: number, value: string) => { for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i)) }
  text(0, 'RIFF'); view.setUint32(4, 36 + count * 2, true); text(8, 'WAVE'); text(12, 'fmt ')
  view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true); view.setUint32(24, rate, true)
  view.setUint32(28, rate * 2, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true); text(36, 'data'); view.setUint32(40, count * 2, true)
  for (let i = 0; i < count; i++) {
    const time = i / rate, beat = time % 1
    const amplitude = beat < 0.7 ? Math.sin(Math.PI * beat / 0.7) * 0.22 : 0
    view.setInt16(44 + i * 2, Math.sin(time * Math.PI * 2 * (220 + Math.floor(time) * 55)) * amplitude * 32767, true)
  }
  return data
}
