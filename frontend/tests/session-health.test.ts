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

function running() {
  const { r, d } = failed()
  r.state = d.state = 'RUNNING'; r.collector.state = 'CONNECTED'; r.collector.lastError = ''; r.lastError = ''
  r.player.ready = r.player.connected = true
  return { r, d }
}
const earlier = '2026-11-01T09:59:58Z', later = '2026-11-01T09:59:59Z'
function taskFailure(reason: string, stage?: string) {
  const { r, d } = running()
  const row = { sequence: 2, taskId: 'task-a', eventType: 'COMMENT', configVersion: 1, stage: 'FAILED', reason, at: earlier, previousStageMs: 10, taskElapsedMs: 20 }
  d.lastFailure = row
  d.recent = stage ? [{ ...row, sequence: 1, stage, reason: 'NONE' }, row] : [row]
  return { r, d, row }
}
test('model, synthesis and playback failures remain visible while the session continues', () => {
  for (const [reason, word] of [['MODEL_FAILED', '模型生成'], ['SPEECH_FAILED', '语音合成'], ['PLAYER_FAILED', '声音播放']]) {
    const { r, d } = taskFailure(reason)
    const h = sessionHealth(r, d)
    assert.equal(h.tone, 'warning'); assert.equal(h.blocking, false)
    assert.match(h.title, new RegExp(`${word}失败，互动仍在运行`)); assert.doesNotMatch(h.title, /已就绪|停止/)
    assert.equal(h.steps.filter(s => s.failed).length, 1)
  }
})
test('shared timeout requires same-task stage evidence or a correlated runtime code', () => {
  for (const [stage, word] of [['MODEL_STARTED', '模型生成'], ['SPEECH_STARTED', '语音合成']]) {
    const { r, d } = taskFailure('OVERALL_TIMEOUT', stage)
    assert.match(sessionHealth(r, d).title, new RegExp(`${word}超时`))
  }
  const { r, d } = taskFailure('READ_TIMEOUT')
  d.recent.unshift({ ...d.lastFailure!, sequence: 1, taskId: 'unrelated', stage: 'MODEL_STARTED' })
  assert.match(sessionHealth(r, d).title, /任务处理超时/)
  r.lastError = 'NLS_READ_TIMEOUT'; r.lastErrorAt = earlier
  assert.match(sessionHealth(r, d).title, /语音合成超时/)
})
test('later success in the failed phase moves the error to history; older or unrelated success does not', () => {
  const { r, d } = taskFailure('MODEL_FAILED')
  d.lastModelSuccessAt = '2026-11-01T09:59:57Z'; d.lastSpeechSuccessAt = later; d.lastPlaybackSuccessAt = later
  assert.match(sessionHealth(r, d).title, /模型生成失败/)
  d.lastModelSuccessAt = later
  const h = sessionHealth(r, d)
  assert.match(h.title, /等待消息/); assert.match(h.history.join(), /模型生成此前失败/)
  assert.equal(h.steps[1]?.failed, false); assert.match(h.steps[1]!.value, /失败后已有成功/)
})
test('same timestamp only proves recovery with a later sequence of the same successful stage', () => {
  const { r, d, row } = taskFailure('SPEECH_FAILED')
  d.lastSpeechSuccessAt = earlier
  assert.match(sessionHealth(r, d).title, /语音合成失败/)
  d.recent.push({ ...row, sequence: 3, taskId: 'task-b', stage: 'SPEECH_COMPLETED', reason: 'NONE' })
  assert.match(sessionHealth(r, d).title, /等待消息/)
})
test('a recovered newer synthesis error does not conceal an unresolved model error', () => {
  const { r, d, row } = taskFailure('MODEL_FAILED')
  d.lastFailure = { ...row, sequence: 4, taskId: 'task-b', reason: 'SPEECH_FAILED', at: later }
  d.lastSpeechSuccessAt = d.capturedAt
  assert.match(sessionHealth(r, d).title, /模型生成失败/)
})
test('old-session and different-state diagnostics cannot supply failure or recovery evidence', () => {
  const { r, d } = taskFailure('SPEECH_FAILED')
  for (const stale of [{ ...d, sessionId: 'old' }, { ...d, state: 'STOPPED' }]) {
    const h = sessionHealth(r, stale)
    assert.match(h.title, /等待消息/); assert.equal(h.history.length, 0); assert.equal(h.steps[2]?.failed, false)
  }
})
test('runtime task failure remains actionable when detailed trace has been evicted', () => {
  const { r, d } = running(); r.lastError = 'NLS_TOKEN_EXPIRED'; r.lastErrorAt = earlier
  assert.match(sessionHealth(r, d).title, /语音合成失败/)
  d.lastSpeechSuccessAt = later
  assert.match(sessionHealth(r, d).title, /等待消息/)
})
test('a gift template is normal and cannot prove model recovery or specific gift collection verification', () => {
  const { r, d, row } = taskFailure('MODEL_FAILED')
  d.recent = [{ ...row, sequence: 3, taskId: 'gift', eventType: 'GIFT', stage: 'SPEECH_COMPLETED', reason: 'NONE', at: later }]
  d.lastSpeechSuccessAt = later; r.collector.emitted = 20
  assert.match(sessionHealth(r, d).title, /模型生成失败/)
  d.lastFailure = null
  const h = sessionHealth(r, d)
  assert.equal(h.steps[1]?.value, '模板处理（无需模型）'); assert.equal(h.steps[1]?.failed, false)
  assert.doesNotMatch(JSON.stringify(h), /礼物已验证|已收到真实评论/)
})
test('startup rejection, uncertain result and failed stop have safe actionable summaries', () => {
  const { r, d } = running(); r.state = d.state = 'STOPPED'
  for (const kind of ['http', 'timeout', 'network']) {
    const h = sessionHealth(r, d, undefined, '', { action: 'start', kind, code: 'https://example.test/?token=secret', sessionId: '' })
    assert.match(h.title, kind === 'http' ? /未能启动/ : /尚未确认/); assert.equal(h.blocking, true)
    assert.doesNotMatch(JSON.stringify(h), /example.test|secret/); assert.ok(h.action)
  }
  r.state = d.state = 'RUNNING'
  const h = sessionHealth(r, d, undefined, '', { action: 'stop', kind: 'timeout', code: '', sessionId: r.sessionId })
  assert.match(h.title, /停止结果尚未确认/); assert.doesNotMatch(h.title, /已停止/)
})
test('collector failure outranks operation errors and never claims RUNNING already stopped', () => {
  const { r, d } = failed(); r.state = d.state = 'RUNNING'
  const h = sessionHealth(r, d, { connection: 'closed', ready: false, playing: false }, '', { action: 'start', kind: 'http', code: '', sessionId: r.sessionId })
  assert.match(h.title, /采集失败/); assert.doesNotMatch(h.title, /已停止|播放/); assert.match(h.detail, /不能确认已经停止/)
})
test('connection, reconnection and end are explicit; connected alone does not prove message reception', () => {
  const { r, d } = running()
  for (const [state, title] of [['CONNECTING', '正在连接'], ['RECONNECTING', '正在恢复'], ['ENDED', '直播已结束']] as const) {
    r.collector.state = state; assert.match(sessionHealth(r, d).title, new RegExp(title))
  }
  r.state = d.state = 'STOPPED'; assert.match(sessionHealth(r, d).title, /直播已结束/)
  r.state = d.state = 'RUNNING'; r.collector.state = 'CONNECTED'
  assert.equal(sessionHealth(r, d).steps[0]?.value, '已连接，等待消息')
})
test('blocked browser, other player and disconnected page explain impact and next action', () => {
  const { r, d } = running()
  const local = { connection: 'leased', ready: false, playing: false, sessionId: r.sessionId, history: [] as { at: number; code: string; taskId: string; sessionId: string }[] }
  assert.equal(sessionHealth(r, d, local).action, 'sound')
  local.connection = 'closed'; local.history = [{ at: Date.now(), code: 'PLAYER_ALREADY_ACTIVE', taskId: '', sessionId: r.sessionId }]
  assert.match(sessionHealth(r, d, local).title, /另一舞台/)
  local.history[0]!.sessionId = 'old-session'
  assert.doesNotMatch(sessionHealth(r, d, local).title, /另一舞台/)
  assert.equal(sessionHealth(r, d, local).action, 'playback')
})
test('local playback failures stay visible until completion of this session, ignoring old history', () => {
  const { r, d } = running()
  const local = { connection: 'leased', ready: true, playing: false, sessionId: r.sessionId,
    history: [{ at: Date.now(), code: 'AUDIO_DECODE_FAILED', taskId: 'task', sessionId: 'old-session' }] }
  assert.match(sessionHealth(r, d, local).title, /等待消息/)
  local.history[0]!.sessionId = r.sessionId
  assert.match(sessionHealth(r, d, local).title, /声音播放失败/)
  local.history.unshift({ ...local.history[0]!, code: 'COMPLETED', taskId: 'new-task' })
  assert.match(sessionHealth(r, d, local).title, /等待消息/)
})
test('read failure hides stale success; pending playback and worker activity are not green idle', () => {
  const { r, d } = running()
  const h = sessionHealth(r, d, undefined, 'https://example.test/?token=secret')
  assert.equal(h.action, 'refresh'); assert.equal(h.steps.length, 0); assert.doesNotMatch(JSON.stringify(h), /secret|已就绪/)
  r.queues.playback.depth = 1
  assert.match(sessionHealth(r, d).title, /等待播放/); assert.equal(sessionHealth(r, d).tone, 'neutral')
  r.queues.playback.depth = 0; d.waiting = ['WORKER_BUSY']
  assert.match(sessionHealth(r, d).title, /等待处理/)
})

test('first attach, lease expiry, audio interruption and changed-session recovery have matching next actions', () => {
  const { r, d } = running()
  const local = { connection: 'idle', ready: false, playing: false, sessionId: r.sessionId,
    history: [] as { at: number; code: string; taskId: string; sessionId: string }[] }
  assert.match(sessionHealth(r, d, local).next, /开始互动.*接入已有会话/)
  local.connection = 'closed'
  for (const [code, title] of [['LEASE_EXPIRED', '租约已到期'], ['AUDIO_INTERRUPTED', '音频中断'], ['CONNECT_TIMEOUT', '连接超时']]) {
    local.history = [{ code, at: Date.now(), taskId: '', sessionId: r.sessionId }]
    assert.match(sessionHealth(r, d, local).title, new RegExp(title))
    assert.match(sessionHealth(r, d, local).next, /恢复原会话/)
  }
  const h = sessionHealth(r, d, local, '', { action: 'recover', kind: 'http', code: 'SESSION_MISMATCH', sessionId: 'old-session' })
  assert.match(h.title, /未能恢复原会话/); assert.match(h.next, /开始互动.*接入当前会话/)
})

test('unknown error keys cannot access object prototypes or break safe summary rendering', () => {
  for (const code of ['constructor', '__proto__', 'toString']) {
    const { r, d } = failed(); r.collector.lastError = code
    const h = sessionHealth(r, d)
    assert.match(h.title, /采集失败/); assert.doesNotMatch(JSON.stringify(h), /constructor|__proto__|toString/)
  }
})
