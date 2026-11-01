import assert from 'node:assert/strict'
import test from 'node:test'
import { StageController } from '../src/stage/controller.ts'
import type { AudioPort, Transport } from '../src/stage/controller.ts'
import { taskView, envelope } from '../src/stage/protocol.ts'

class FakeAudio implements AudioPort {
  starts = 0; stops = 0; enabled = false
  callbacks: Parameters<AudioPort['play']>[1] | null = null
  async enable() { this.enabled = true }
  play(_task: unknown, callbacks: Parameters<AudioPort['play']>[1]) { this.starts++; this.callbacks = callbacks }
  stop() { this.stops++ }
  dispose() {}
}
class FakeTransport implements Transport {
  sent: any[] = []
  handlers: Parameters<Transport['connect']>[0] | null = null
  closed = false
  connect(handlers: Parameters<Transport['connect']>[0]) { this.handlers = handlers; handlers.open() }
  send(raw: string) { this.sent.push(JSON.parse(raw)) }
  close() { this.closed = true }
}
function setup() {
  let time = Date.parse('2026-10-29T00:00:00Z')
  const audio = new FakeAudio(), wire = new FakeTransport()
  const controller = new StageController(audio, 'http://localhost', () => {}, () => time, 'player-a')
  controller.connect(wire)
  const receive = (type: string, payload: Record<string, unknown>, sessionId = 'session-a') => controller.receive(JSON.stringify({ schemaVersion: 1, messageId: crypto.randomUUID(), type, sessionId, payload }))
  const lease = (generation = 3) => receive('PLAYER_LEASE', { playerId: 'player-a', leaseGeneration: generation, expiresAt: new Date(time + 15000).toISOString(), heartbeatIntervalMs: 5000 })
  const task = (changes: Record<string, unknown> = {}) => ({ taskId: 'task-a', sessionId: 'session-a', configVersion: 1, type: 'COMMENT', text: 'hello', audioUrl: '/api/v1/audio/audio-a', priority: 50, expiresAt: new Date(time + 60000).toISOString(), ...changes })
  const ack = () => wire.sent.filter(x => x.type === 'PLAYBACK_ACK').map(x => x.payload)
  return { audio, wire, controller, receive, lease, task, ack, advance: (ms: number) => { time += ms; controller.tick() } }
}
test('handshake is per page; READY needs lease plus explicit audio enable', async () => {
  const s = setup()
  assert.equal(s.wire.sent[0].type, 'PLAYER_HELLO'); assert.equal(s.wire.sent[0].payload.playerId, 'player-a')
  await s.controller.enable(); assert.equal(s.audio.enabled, false)
  s.lease(); assert.equal(s.wire.sent.some(x => x.type === 'PLAYER_READY'), false)
  await s.controller.enable(); assert.equal(s.wire.sent.at(-1).type, 'PLAYER_READY')
})
test('no running session closes the handshake immediately with an actionable hint', async () => {
  const s = setup()
  s.receive('SESSION_STOPPED', { reason: 'NO_SESSION' }, '')
  assert.equal(s.controller.view.connection, 'closed')
  assert.equal(s.controller.view.history[0].code, 'NO_SESSION')
  assert.equal(s.wire.closed, true)
  await s.controller.enable(); assert.equal(s.audio.enabled, false)
})
test('STARTED and COMPLETED only follow actual audio callbacks; subtitles and volume stop with audio', async () => {
  const s = setup(); s.lease(); await s.controller.enable(); s.receive('PLAY_TASK', s.task())
  assert.equal(s.audio.starts, 1); assert.equal(s.ack().length, 0); assert.equal(s.controller.view.subtitle, '')
  s.audio.callbacks!.started(); s.audio.callbacks!.level(0.7)
  assert.equal(s.ack()[0].state, 'STARTED'); assert.equal(s.controller.view.subtitle, 'hello'); assert.equal(s.controller.view.level, 0.7)
  s.audio.callbacks!.ended()
  assert.deepEqual(s.ack().map(x => x.state), ['STARTED', 'COMPLETED']); assert.equal(s.controller.view.level, 0); assert.equal(s.controller.view.subtitle, '')
  assert.equal(s.ack()[1].leaseGeneration, 3); assert.equal(s.ack()[1].errorCode, null)
})
test('duplicate during playback, after completion and after reconnect is never replayed', async () => {
  const s = setup(); s.lease(); await s.controller.enable(); const task = s.task()
  s.receive('PLAY_TASK', task); s.audio.callbacks!.started(); s.receive('PLAY_TASK', task); s.audio.callbacks!.ended(); s.receive('PLAY_TASK', task)
  s.controller.disconnect(); s.controller.connect(s.wire); s.lease(4); await s.controller.enable(); s.receive('PLAY_TASK', task)
  assert.equal(s.audio.starts, 1); assert.equal(s.ack().length, 2)
})
test('expired and old-session tasks are discarded before decoding', async () => {
  const s = setup(); s.lease(); await s.controller.enable()
  s.receive('PLAY_TASK', s.task({ expiresAt: '2020-01-01T00:00:00Z' }))
  s.receive('PLAY_TASK', s.task({ taskId: 'old', sessionId: 'old-session' }))
  s.receive('PLAY_TASK', s.task({ taskId: 'old-envelope' }), 'old-session')
  assert.equal(s.audio.starts, 0); assert.equal(s.ack().length, 1); assert.equal(s.ack()[0].errorCode, 'TASK_EXPIRED')
})
for (const action of ['disconnect', 'stop', 'cancel', 'expiry'] as const) {
  test(`${action} immediately silences, clears subtitles and ignores late audio completion`, async () => {
    const s = setup(); s.lease(); await s.controller.enable(); s.receive('PLAY_TASK', s.task())
    const callbacks = s.audio.callbacks!; callbacks.started(); callbacks.level(0.8)
    if (action === 'disconnect') s.wire.handlers!.close()
    if (action === 'stop') s.receive('SESSION_STOPPED', { reason: 'STOP' })
    if (action === 'cancel') s.receive('CANCEL_TASK', { taskId: 'task-a', reason: 'STOP' })
    if (action === 'expiry') s.advance(16000)
    callbacks.started(); callbacks.level(1); callbacks.ended(); callbacks.failed('AUDIO_DECODE_FAILED')
    assert.equal(s.controller.view.playing, false); assert.equal(s.controller.view.level, 0); assert.equal(s.controller.view.subtitle, '')
    assert.equal(s.ack().filter(x => x.state === 'COMPLETED').length, 0); assert.ok(s.audio.stops > 0)
  })
}
test('second player rejection stays muted even after clicking enable and receiving a task', async () => {
  const s = setup(); s.receive('PLAYER_REJECTED', { reason: 'PLAYER_ALREADY_ACTIVE' })
  await s.controller.enable(); s.receive('PLAY_TASK', s.task())
  assert.equal(s.audio.enabled, false); assert.equal(s.audio.starts, 0); assert.equal(s.controller.view.ready, false)
})
test('old generation cannot renew; expired lease cannot be revived', async () => {
  const s = setup(); s.lease(); const initial = s.controller.view.leaseExpiresAt
  s.advance(5000); assert.equal(s.wire.sent.at(-1).type, 'PLAYER_HEARTBEAT')
  s.receive('HEARTBEAT_ACK', { playerId: 'player-a', leaseGeneration: 2, expiresAt: '2099-01-01T00:00:00Z' })
  assert.equal(s.controller.view.leaseExpiresAt, initial)
  s.advance(11000); s.receive('HEARTBEAT_ACK', { playerId: 'player-a', leaseGeneration: 3 })
  assert.equal(s.controller.view.connection, 'closed')
})
test('current heartbeat renews; stopped session cannot be re-leased', () => {
  const s = setup(); s.lease(); s.advance(5000)
  s.receive('HEARTBEAT_ACK', { playerId: 'player-a', leaseGeneration: 3 })
  s.advance(11000); assert.equal(s.controller.view.connection, 'leased')
  s.receive('SESSION_STOPPED', {}); s.controller.connect(s.wire); s.lease(4)
  assert.equal(s.controller.view.connection, 'closed')
})
test('audio failure is FAILED, never STARTED/COMPLETED; task received before ready is rejected', async () => {
  const s = setup(); s.lease(); s.receive('PLAY_TASK', s.task()); assert.equal(s.ack()[0].errorCode, 'NOT_READY')
  await s.controller.enable(); s.receive('PLAY_TASK', s.task({ taskId: 'failure' })); s.audio.callbacks!.failed('AUDIO_DECODE_FAILED')
  assert.equal(s.ack().at(-1).errorCode, 'AUDIO_DECODE_FAILED'); assert.equal(s.ack().some(x => x.state === 'STARTED'), false)
})
test('server cannot queue a second task in the browser', async () => {
  const s = setup(); s.lease(); await s.controller.enable(); s.receive('PLAY_TASK', s.task()); s.receive('PLAY_TASK', s.task({ taskId: 'extra' }))
  assert.equal(s.audio.starts, 1); assert.equal(s.ack().at(-1).errorCode, 'PLAYER_BUSY')
})
test('task expiry during loading ignores late STARTED', async () => {
  const s = setup(); s.lease(); await s.controller.enable(); s.receive('PLAY_TASK', s.task({ expiresAt: new Date(Date.parse('2026-10-29T00:00:00Z') + 1000).toISOString() }))
  s.advance(1500); s.audio.callbacks!.started(); assert.equal(s.ack().at(-1).errorCode, 'TASK_EXPIRED'); assert.equal(s.controller.view.playing, false)
})
test('connect timeout and invalid messages fail closed', () => {
  const s = setup(); s.advance(8001); assert.equal(s.controller.view.history[0].code, 'CONNECT_TIMEOUT')
  s.controller.connect(s.wire); s.lease(); s.controller.receive('not json'); assert.equal(s.controller.view.connection, 'closed')
})
test('audio urls are limited to controlled same-origin asset paths', () => {
  const s = setup()
  for (const url of ['https://evil.test/api/v1/audio/a', 'file:///tmp/audio', '//evil.test/x', '/api/v1/audio/../secret', '/api/v1/audio/%2e%2e', '/api/v1/audio/a?token=x', '/api/v1/audio/a#x', 'http://user@localhost/api/v1/audio/a']) assert.equal(taskView(s.task({ audioUrl: url }), 'http://localhost'), null, url)
  assert.ok(taskView(s.task(), 'http://localhost')); assert.ok(taskView(s.task({ audioUrl: 'http://localhost/api/v1/audio/a.wav' }), 'http://localhost'))
  assert.equal(envelope('x'.repeat(33000)), null)
})
test('dedupe cache fails closed at capacity rather than evicting replay protection', async () => {
  const s = setup(); s.lease(); await s.controller.enable()
  for (let i = 0; i < 2049; i++) s.receive('PLAY_TASK', s.task({ taskId: `task-${i}`, expiresAt: '2020-01-01T00:00:00Z' }))
  assert.equal(s.controller.view.connection, 'closed'); assert.equal(s.controller.view.history[0].code, 'CACHE_FULL')
})

test('stopped-session protection stays bounded even when sessions stop without tasks', () => {
  const s = setup()
  for (let i = 0; i < 128; i++) {
    const sessionId = `session-${i}`
    s.receive('PLAYER_LEASE', { playerId: 'player-a', leaseGeneration: i, expiresAt: '2026-10-29T00:00:15Z', heartbeatIntervalMs: 5000 }, sessionId)
    s.receive('SESSION_STOPPED', {}, sessionId)
    if (i < 127) s.controller.connect(new FakeTransport())
  }
  const next = new FakeTransport(); s.controller.connect(next)
  assert.equal(next.handlers, null); assert.equal(s.controller.view.history[0].code, 'SESSION_CACHE_FULL')
})
test('stale transport callbacks cannot interrupt a replacement connection', async () => {
  const s = setup(); s.lease(); const stale = s.wire.handlers!
  const replacement = new FakeTransport(); s.controller.connect(replacement); s.lease(4); await s.controller.enable()
  stale.close(); stale.message('malformed old frame')
  assert.equal(s.controller.view.connection, 'leased'); assert.equal(s.controller.view.ready, true)
})


test('pre-unlock does not send READY until expected lease; late unlock after stop cannot arm a replacement', async () => {
  const s = setup(); s.controller.disconnect()
  let finish!: () => void
  s.audio.enable = () => new Promise(resolve => { finish = resolve })
  const sound = s.controller.unlock()
  s.controller.connect(s.wire, 'session-a'); s.lease()
  assert.equal(s.wire.sent.some(x => x.type === 'PLAYER_READY'), false)
  s.controller.disconnect()
  const replacement = new FakeTransport(); s.controller.connect(replacement, 'session-a'); s.lease(4)
  finish(); await sound
  assert.equal(s.controller.view.ready, false)
  assert.equal(replacement.sent.some(x => x.type === 'PLAYER_READY'), false)
  s.controller.dispose()
})
test('an unlock completing after lease expiry cannot declare READY', async () => {
  const s = setup(); s.lease()
  let finish!: () => void
  s.audio.enable = () => new Promise(resolve => { finish = resolve })
  const sound = s.controller.enable(); s.advance(16000); finish(); await sound
  assert.equal(s.controller.view.ready, false); assert.equal(s.wire.sent.some(x => x.type === 'PLAYER_READY'), false)
  s.controller.dispose()
})
