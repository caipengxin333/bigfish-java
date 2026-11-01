import assert from 'node:assert/strict'
import test from 'node:test'
import { sessionHealth } from '../src/session-health.ts'
import { runtime, diagnostics } from './session-fixtures.ts'
import type { RuntimeView } from '../src/contracts.ts'

function failed() {
  const r: RuntimeView = structuredClone(runtime)
  r.sessionId = 'failed-session'; r.state = 'FAILED'; r.mode = 'DOUYIN'
  r.conversationMode = 'DEEPSEEK'; r.speechMode = 'ALIYUN_NLS'; r.lastError = 'COLLECTOR_FAILED'
  r.collector = { ...r.collector, mode: 'DOUYIN', state: 'FAILED', lastError: 'PAGE_STRUCTURE_CHANGED' }
  const d = { ...structuredClone(diagnostics), sessionId: r.sessionId, state: r.state }
  return { r, d }
}
test('collector root cause takes precedence over consequent playback disconnection', () => {
  const { r, d } = failed()
  const h = sessionHealth(r, d, { connection: 'closed', ready: false, playing: false })
  assert.equal(h.tone, 'error'); assert.equal(h.blocking, true)
  assert.match(h.title, /抖音采集失败/); assert.match(h.detail, /返回的页面/)
  assert.match(h.next, /采集页面解析/); assert.match(h.next, /模拟采集/)
  assert.deepEqual(h.steps.map(x => x.value), ['失败', '未处理', '未处理', '已停止'])
})
test('missing or stale diagnostic evidence does not claim model and speech were never invoked', () => {
  const { r, d } = failed()
  for (const trace of [null, { ...d, sessionId: 'older-session' }, { ...d, state: 'RUNNING' }]) {
    const h = sessionHealth(r, trace)
    assert.equal(h.steps[1]?.value, '尚无成功记录')
    assert.equal(h.steps[2]?.value, '尚无成功记录')
  }
})
test('failure after prior successful processing keeps that history visible', () => {
  const { r, d } = failed(); r.collector.received = 10
  d.lastModelSuccessAt = d.lastSpeechSuccessAt = d.capturedAt
  const h = sessionHealth(r, d)
  assert.equal(h.tone, 'error')
  assert.equal(h.steps[1]?.value, '有成功记录'); assert.equal(h.steps[2]?.value, '有成功记录')
})
test('a stopped session with historical collector errors is not presented as a new failure', () => {
  const { r, d } = failed(); r.state = 'STOPPED'; d.state = 'STOPPED'
  const h = sessionHealth(r, d)
  assert.equal(h.tone, 'neutral'); assert.equal(h.blocking, false); assert.equal(h.title, '互动已停止')
})
test('reconnecting source and historical errors show recovery rather than permanent failure', () => {
  const { r, d } = failed(); r.state = 'RUNNING'; d.state = 'RUNNING'; r.collector.state = 'RECONNECTING'
  const h = sessionHealth(r, d)
  assert.equal(h.tone, 'warning'); assert.equal(h.blocking, false); assert.match(h.title, /恢复/)
})
test('ready playback does not prove cloud model or speech succeeded', () => {
  const { r, d } = failed(); r.state = 'RUNNING'; d.state = 'RUNNING'; r.collector.state = 'CONNECTED'
  r.player.ready = r.player.connected = true
  const h = sessionHealth(r, d)
  assert.match(h.title, /等待消息/)
  assert.equal(h.steps[1]?.value, '尚无成功记录'); assert.equal(h.steps[2]?.value, '尚无成功记录')
})
test('unknown upstream text is never reflected in the summary', () => {
  const { r, d } = failed(); r.collector.lastError = 'https://upstream.test/?token=private-value'
  const h = sessionHealth(r, d)
  assert.equal(h.tone, 'error'); assert.doesNotMatch(JSON.stringify(h), /private-value|upstream\.test/)
})
test('status read failure overrides stale data and does not claim ready or a current root cause', () => {
  const { r, d } = failed()
  const h = sessionHealth(r, d, undefined, 'unavailable')
  assert.equal(h.tone, 'warning'); assert.equal(h.steps.length, 0); assert.match(h.title, /无法确认/)
})
