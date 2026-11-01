import assert from 'node:assert/strict'
import test from 'node:test'
import { createInteraction } from '../src/stage/interaction.ts'
import { StageController } from '../src/stage/controller.ts'
import type { AudioPort, Transport } from '../src/stage/controller.ts'
import { RequestError } from '../src/api.ts'
import { defaultSelection } from '../src/launch-options.ts'
import { runtime } from './session-fixtures.ts'
import type { RuntimeView, SessionSelection } from '../src/contracts.ts'
const active = (): RuntimeView => ({ ...structuredClone(runtime), ...defaultSelection(), sessionId: 'session-a', state: 'RUNNING' })
const flush = () => new Promise(resolve => setImmediate(resolve))
function fixture() {
  const calls: string[] = [], choices: { selection: SessionSelection; target?: string; signal?: AbortSignal }[] = []
  let block = false
  const audio: AudioPort = { enable() { calls.push('unlock'); return block ? Promise.reject(new Error()) : Promise.resolve() },
    play() { calls.push('play') }, stop() { calls.push('silence') }, dispose() {} }
  const wires: { wire: Transport; sent: any[]; handlers?: Parameters<Transport['connect']>[0] }[] = []
  const transport = () => {
    const item: typeof wires[number] = { sent: [], wire: { connect(handlers) { calls.push('connect'); item.handlers = handlers; handlers.open() },
      send(raw) { item.sent.push(JSON.parse(raw)) }, close() { calls.push('close') } } }
    wires.push(item); return item.wire
  }
  const sessions = {
    async prepareSession(selection: SessionSelection, signal?: AbortSignal, target?: string) { calls.push('prepare'); choices.push({ selection, signal, target }); return active() },
    async stopSession(_target?: string) { calls.push('stop'); return { ...active(), state: 'STOPPED' } as RuntimeView },
    async verifySession(_target: string) { calls.push('verify'); return true },
  }
  const stage = new StageController(audio, 'http://localhost', () => {}, Date.now, 'player-a')
  const interaction = createInteraction(stage, sessions, defaultSelection, transport)
  function receive(type: string, payload: unknown, sessionId = 'session-a') {
    wires.at(-1)!.handlers!.message(JSON.stringify({ schemaVersion: 1, messageId: 'message', sessionId, type, payload }))
  }
  const lease = (session = 'session-a') => receive('PLAYER_LEASE', { playerId: 'player-a', leaseGeneration: 1, expiresAt: new Date(Date.now() + 15000).toISOString(), heartbeatIntervalMs: 5000 }, session)
  return { calls, choices, sessions, wires, stage, interaction, receive, lease, blocked: () => { block = true }, allow: () => { block = false },
    dispose: () => { interaction.dispose(); stage.dispose() } }
}
test('one click calls audio resume before HTTP; no READY until the matching lease', async t => {
  const f = fixture(); t.after(f.dispose)
  const start = f.interaction.start()
  assert.deepEqual(f.calls.slice(0, 3), ['silence', 'unlock', 'prepare'])
  await start
  assert.equal(f.wires[0].sent.some(v => v.type === 'PLAYER_READY'), false)
  f.lease(); assert.equal(f.stage.view.ready, true)
  assert.equal(f.wires[0].sent.at(-1).type, 'PLAYER_READY')
  assert.equal(f.wires[0].sent.at(-1).sessionId, 'session-a')
})
test('existing different backend options are shown, not overwritten by browser preferences', async t => {
  const f = fixture(); t.after(f.dispose)
  f.sessions.prepareSession = async () => ({ ...active(), mode: 'DOUYIN', speechMode: 'ALIYUN_NLS' })
  await f.interaction.start(); f.lease()
  assert.match(f.interaction.notice.value, /实际选项/); assert.equal(f.stage.view.sessionId, 'session-a')
})
test('blocked audio preserves the session; a second sound gesture sends READY without starting again', async t => {
  const f = fixture(); t.after(f.dispose); f.blocked()
  await f.interaction.start(); f.lease()
  assert.equal(f.stage.view.ready, false); assert.equal(f.interaction.target.value, 'session-a')
  f.allow(); await f.stage.enable()
  assert.equal(f.stage.view.ready, true); assert.equal(f.calls.filter(x => x === 'prepare').length, 1)
})
test('double click is single flight; stop silences immediately and prevents late start reconnecting', async t => {
  const f = fixture(); t.after(f.dispose)
  let resolve!: (value: RuntimeView) => void
  f.sessions.prepareSession = async (_options, signal) => { f.choices.push({ selection: defaultSelection(), signal }); return new Promise(r => { resolve = r }) }
  const start = f.interaction.start(); await f.interaction.start()
  assert.equal(f.choices.length, 1)
  const stop = f.interaction.stop()
  assert.equal(f.choices[0].signal!.aborted, true); assert.equal(f.calls.at(-2), 'silence'); assert.equal(f.calls.at(-1), 'stop')
  await stop; resolve(active()); await start
  assert.equal(f.wires.length, 0); assert.equal(f.stage.view.ready, false)
  assert.match(f.interaction.notice.value, /后端已确认/)
})
test('stop failure never claims backend stopped; recovery is fixed to the same session', async t => {
  const f = fixture(); t.after(f.dispose); await f.interaction.start(); f.lease()
  f.wires[0].handlers!.close()
  assert.equal(f.stage.view.ready, false)
  await f.interaction.start(true); assert.equal(f.choices.at(-1)!.target, 'session-a'); f.lease()
  f.sessions.stopSession = async () => { throw new RequestError('timeout', '请求超时') }
  await f.interaction.stop(); assert.match(f.interaction.error.value, /后端停止未确认/)
  assert.equal(f.stage.view.ready, false); assert.doesNotMatch(f.interaction.notice.value, /已确认互动停止/)
})
test('wrong session lease or another player occupation never sends READY or steals a lease', async t => {
  for (const wrongSession of [true, false]) {
    const f = fixture(); t.after(f.dispose); await f.interaction.start()
    if (wrongSession) f.lease('session-b')
    else f.receive('PLAYER_REJECTED', { reason: 'PLAYER_ALREADY_ACTIVE' })
    assert.equal(f.stage.view.connection, 'closed'); assert.equal(f.stage.view.ready, false)
    assert.equal(f.wires[0].sent.some(v => v.type === 'PLAYER_READY'), false)
    assert.equal(f.calls.includes('play'), false)
  }
})
test('page restoration verifies the session, and freezing cancels pending operations', async t => {
  const f = fixture(); t.after(f.dispose); await f.interaction.start(); f.lease()
  await f.interaction.resumePage(); assert.equal(f.stage.view.ready, true)
  f.sessions.verifySession = async () => false
  await f.interaction.resumePage(); assert.equal(f.stage.view.ready, false); assert.match(f.interaction.error.value, /无法确认原会话/)
  f.interaction.freeze(); await flush(); assert.equal(f.stage.view.connection, 'closed')
})
