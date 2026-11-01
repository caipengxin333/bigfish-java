import { envelope, generation, id, reasons, taskView, timestamp } from './protocol.ts'
import type { StageTask } from './protocol.ts'
export interface Transport {
  connect(handlers: { open: () => void; message: (raw: string) => void; close: () => void }): void
  send(raw: string): void
  close(): void
}
export interface AudioPort {
  enable(): Promise<void>
  isEnabled?(): boolean
  play(task: StageTask, callbacks: { started: () => void; ended: () => void; failed: (code: string) => void; level: (value: number) => void }): void
  stop(): void
  dispose(): void
}
export interface StageView {
  connection: 'idle' | 'connecting' | 'leased' | 'closed'
  ready: boolean; playing: boolean; sessionId: string; taskId: string; subtitle: string; level: number
  leaseExpiresAt: number; notice: string; history: { at: number; code: string; taskId: string }[]
}
export class StageController {
  readonly view: StageView = { connection: 'idle', ready: false, playing: false, sessionId: '', taskId: '', subtitle: '', level: 0, leaseExpiresAt: 0, notice: '点击开始互动，将连接播放端并申请声音授权。', history: [] }
  private transport: Transport | null = null
  private audio: AudioPort
  private origin: string
  private notify: (view: StageView) => void
  private now: () => number
  readonly playerId: string
  private leaseGeneration: number | null = null
  private deadline = 0
  private nextHeartbeat = 0
  private current: { task: StageTask; started: boolean; startedAt: number } | null = null
  private seen = new Map<string, number>()
  private stopped = new Set<string>()
  private enabling: Promise<boolean> | null = null
  private unlockRevision = 0
  private unlocked = false
  private expectedSession = ''
  constructor(audio: AudioPort, origin: string, notify: (view: StageView) => void, now = Date.now, playerId = crypto.randomUUID()) {
    this.audio = audio; this.origin = origin; this.notify = notify; this.now = now; this.playerId = playerId
  }
  private emit() { this.notify({ ...this.view, history: [...this.view.history] }) }
  private note(code: string, taskId = '') {
    this.view.notice = reasons[code] ?? '播放端发生错误，已停止当前操作。'
    this.view.history.unshift({ at: this.now(), code, taskId }); this.view.history.length = Math.min(this.view.history.length, 40)
    this.emit()
  }
  connect(transport: Transport, expectedSessionId = '') {
    if (this.transport || this.current) this.disconnect('USER_DISCONNECTED')
    if (this.stopped.size >= 128) { this.note('SESSION_CACHE_FULL'); return }
    this.expectedSession = expectedSessionId
    this.transport = transport; this.view.connection = 'connecting'; this.view.sessionId = ''; this.deadline = this.now() + 8000
    this.view.notice = '正在连接并申请独占租约…'; this.emit()
    try { transport.connect({
      open: () => { if (this.transport === transport) this.send('PLAYER_HELLO', { playerId: this.playerId }) },
      message: raw => { if (this.transport === transport) this.receive(raw) },
      close: () => { if (this.transport === transport) this.disconnect('DISCONNECTED') },
    }) } catch { this.disconnect('DISCONNECTED') }
  }
  private send(type: string, payload: Record<string, unknown>) {
    if (!this.transport) return
    try { this.transport.send(JSON.stringify({ schemaVersion: 1, messageId: crypto.randomUUID(), type, sessionId: this.view.sessionId, payload })) }
    catch { this.disconnect('DISCONNECTED') }
  }
  // Kept for explicit sound remediation after a lease. unlock() may also be called
  // directly from the initial user gesture, before any HTTP or WebSocket work.
  async enable() { if (this.validLease()) await this.unlock() }
  unlock(): Promise<boolean> {
    if (this.enabling) return this.enabling
    const revision = this.unlockRevision
    let timer: ReturnType<typeof setTimeout>
    let enabled: Promise<void>
    try { enabled = this.audio.enable() } // Must remain before the first await/network call.
    catch { this.note('PLAYBACK_BLOCKED'); return Promise.resolve(false) }
    const promise = Promise.race([enabled, new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error('PLAYBACK_BLOCKED')), 5000)
    })]).then(() => {
      if (revision !== this.unlockRevision) return false
      this.unlocked = true; this.declareReady(); return true
    }, () => {
      if (revision === this.unlockRevision) { this.unlocked = false; this.note('PLAYBACK_BLOCKED') }
      return false
    }).finally(() => { clearTimeout(timer); if (this.enabling === promise) this.enabling = null })
    this.enabling = promise
    return promise
  }
  private declareReady() {
    if (!this.unlocked || !this.validLease() || this.view.ready) return
    if (this.audio.isEnabled && !this.audio.isEnabled()) { this.unlocked = false; this.note('PLAYBACK_BLOCKED'); return }
    this.view.ready = true; this.view.notice = '声音已启用，等待服务端任务。'
    this.send('PLAYER_READY', { playerId: this.playerId, leaseGeneration: this.leaseGeneration }); this.emit()
  }
  checkActivity() {
    this.tick()
    if (this.validLease() && this.view.ready && this.audio.isEnabled && !this.audio.isEnabled()) {
      this.unlocked = false; this.view.ready = false
      if (this.current) this.finish('FAILED', 'AUDIO_INTERRUPTED')
      else this.note('AUDIO_INTERRUPTED')
    }
  }
  private validLease() { return this.view.connection === 'leased' && this.leaseGeneration !== null && this.now() < this.view.leaseExpiresAt }
  receive(raw: string) {
    const msg = envelope(raw)
    if (!msg) { this.disconnect('INVALID_MESSAGE'); return }
    const p = msg.payload
    if (msg.type === 'SESSION_STOPPED' && this.view.connection === 'connecting' && msg.sessionId === '' && p.reason === 'NO_SESSION') {
      this.disconnect('NO_SESSION'); return
    }
    if (msg.type === 'PLAYER_REJECTED') { this.disconnect(p.reason === 'PLAYER_ALREADY_ACTIVE' ? p.reason : 'MOCK_UNAVAILABLE'); return }
    if (msg.type === 'PLAYER_LEASE') {
      if (this.expectedSession && msg.sessionId !== this.expectedSession) { this.disconnect('SESSION_CHANGED'); return }
      if (this.view.connection !== 'connecting' || p.playerId !== this.playerId || !generation(p.leaseGeneration) || !id(msg.sessionId) ||
          this.stopped.has(msg.sessionId) || p.heartbeatIntervalMs !== 5000 || !Number.isFinite(timestamp(p.expiresAt)) || timestamp(p.expiresAt) <= this.now()) { this.disconnect('INVALID_MESSAGE'); return }
      this.leaseGeneration = p.leaseGeneration; this.view.sessionId = msg.sessionId; this.view.leaseExpiresAt = timestamp(p.expiresAt)
      this.view.connection = 'leased'; this.nextHeartbeat = this.now() + 5000
      this.view.notice = '已获得播放租约，等待声音授权。'; this.declareReady(); this.emit(); return
    }
    if (!this.validLease()) { if (this.view.connection === 'leased') this.disconnect('LEASE_EXPIRED'); return }
    if (msg.sessionId !== this.view.sessionId) { this.note('OLD_SESSION'); return }
    if (msg.type === 'HEARTBEAT_ACK') {
      // Old generations cannot extend a lease. An expired lease is never revived.
      if (p.playerId !== this.playerId || p.leaseGeneration !== this.leaseGeneration) return
      const expires = p.expiresAt === undefined ? this.now() + 15000 : timestamp(p.expiresAt)
      if (!Number.isFinite(expires) || expires <= this.now()) { this.disconnect('LEASE_EXPIRED'); return }
      this.view.leaseExpiresAt = expires; this.emit(); return
    }
    if (msg.type === 'SESSION_STOPPED') {
      this.stopped.add(msg.sessionId); this.disconnect('SESSION_STOPPED'); return
    }
    if (msg.type === 'CANCEL_TASK') { if (p.taskId === this.current?.task.taskId) this.finish('CANCELLED', 'CANCELLED'); return }
    if (msg.type !== 'PLAY_TASK') { this.disconnect('INVALID_MESSAGE'); return }
    const task = taskView(p, this.origin)
    if (!task) { this.disconnect('INVALID_MESSAGE'); return }
    if (task.sessionId !== this.view.sessionId) { this.note('OLD_SESSION', task.taskId); return }
    this.prune()
    const key = `${task.sessionId}\n${task.taskId}`
    if (this.seen.has(key)) { this.note('DUPLICATE', task.taskId); return }
    if (this.seen.size >= 2048) { this.disconnect('CACHE_FULL'); return }
    this.seen.set(key, Math.max(task.expiresAt, this.now() + 15 * 60_000))
    if (task.expiresAt <= this.now()) { this.ack(task, 'CANCELLED', 'TASK_EXPIRED'); this.note('TASK_EXPIRED', task.taskId); return }
    if (!this.view.ready || this.current) {
      const code = this.current ? 'PLAYER_BUSY' : 'NOT_READY'
      this.ack(task, 'FAILED', code); this.note(code, task.taskId); return
    }
    const current = { task, started: false, startedAt: this.now() }
    this.current = current; this.view.taskId = task.taskId; this.view.notice = '正在载入音频，尚未开始播放。'; this.emit()
    const live = () => this.current === current && this.validLease()
    try { this.audio.play(task, {
      started: () => {
        if (!live()) { if (this.current === current) this.disconnect('LEASE_EXPIRED'); return }
        if (task.expiresAt <= this.now()) { this.finish('CANCELLED', 'TASK_EXPIRED'); return }
        if (current.started) return
        current.started = true; current.startedAt = this.now(); this.view.playing = true; this.view.subtitle = task.text
        this.view.notice = '正在播放'; this.ack(task, 'STARTED', null); this.emit()
      },
      ended: () => { if (live() && current.started) this.finish('COMPLETED', null) },
      failed: code => { if (this.current === current) { if (code === 'AUDIO_INTERRUPTED' || code === 'PLAYBACK_BLOCKED') { this.view.ready = false; this.unlocked = false } this.finish('FAILED', code) } },
      level: level => { if (live() && current.started) { this.view.level = Math.max(0, Math.min(1, level)); this.emit() } },
    }) } catch { this.finish('FAILED', 'PLAYBACK_BLOCKED') }
  }
  private ack(task: StageTask, state: string, errorCode: string | null) {
    if (!this.validLease()) return
    if (state === 'STARTED') {
      this.view.history.unshift({ at: this.now(), code: state, taskId: task.taskId })
      this.view.history.length = Math.min(this.view.history.length, 40)
    }
    this.send('PLAYBACK_ACK', { taskId: task.taskId, sessionId: task.sessionId, playerId: this.playerId,
      leaseGeneration: this.leaseGeneration, state, occurredAt: new Date(this.now()).toISOString(), errorCode })
  }
  private finish(state: string, code: string | null) {
    const current = this.current
    this.current = null; this.audio.stop(); this.view.playing = false; this.view.subtitle = ''; this.view.level = 0; this.view.taskId = ''
    if (current) { this.ack(current.task, state, code); this.note(code ?? state, current.task.taskId) }
    if (!code && this.view.connection === 'leased') this.view.notice = '播放已结束，等待下一任务。'
    this.emit()
  }
  private prune() { for (const [key, expiry] of this.seen) if (expiry <= this.now()) this.seen.delete(key) }
  tick() {
    if (this.view.connection === 'connecting' && this.now() >= this.deadline) { this.disconnect('CONNECT_TIMEOUT'); return }
    if (this.view.connection !== 'leased') return
    if (!this.validLease()) { this.disconnect('LEASE_EXPIRED'); return }
    if (this.current && this.current.task.expiresAt <= this.now()) this.finish('CANCELLED', 'TASK_EXPIRED')
    if (this.current && this.now() - this.current.startedAt > (this.current.started ? 125000 : 20000)) this.finish('FAILED', 'PLAYBACK_TIMEOUT')
    if (this.now() >= this.nextHeartbeat) { this.nextHeartbeat = this.now() + 5000; this.send('PLAYER_HEARTBEAT', { playerId: this.playerId, leaseGeneration: this.leaseGeneration }) }
  }
  disconnect(code = 'USER_DISCONNECTED') {
    ++this.unlockRevision; this.enabling = null; this.unlocked = false
    if (this.current) this.finish('CANCELLED', code)
    this.audio.stop(); const transport = this.transport; this.transport = null
    this.leaseGeneration = null; this.view.connection = 'closed'; this.view.ready = false; this.view.leaseExpiresAt = 0
    this.view.playing = false; this.view.level = 0; this.view.subtitle = ''; this.view.taskId = ''
    transport?.close(); this.note(code)
  }
  dispose() { this.disconnect(); this.audio.dispose() }
}
