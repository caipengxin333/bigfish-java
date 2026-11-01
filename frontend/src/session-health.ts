import type { RuntimeDiagnosticsView, RuntimeView } from './contracts.ts'

type LocalPlayer = { connection: string; ready: boolean; playing: boolean; sessionId?: string;
  history?: { code: string; at: number; taskId: string; sessionId: string }[] }
export type OperationIssue = { action: 'start' | 'stop' | 'recover'; kind: string; code: string; sessionId: string }
type Phase = 'model' | 'speech' | 'playback' | 'unknown'
const names = { model: '模型生成', speech: '语音合成', playback: '声音播放', unknown: '任务处理' }
type Tone = 'neutral' | 'warning' | 'error' | 'success'
export interface SessionHealth {
  tone: Tone; title: string; detail: string; next: string; blocking: boolean
  action: 'config' | 'refresh' | 'sound' | 'playback' | ''; history: string[]
  steps: { label: string; value: string; failed?: boolean }[]
}
const collectorHelp: Record<string, [string, string]> = {
  PAGE_STRUCTURE_CHANGED: ['采集器无法从抖音返回的页面中取得所需信息，已结束本次互动。', '需要适配采集页面解析。可先到管理页改为“模拟采集”，单独验证回复和声音。'],
  AUTH_REQUIRED: ['抖音返回了访问或登录要求，采集已停止。', '请先在自己的浏览器确认该直播间是否可以正常访问，再检查采集接入。'],
  CHALLENGE_REQUIRED: ['抖音返回了验证页面，采集已停止。', '请在自己的浏览器检查并完成平台要求的验证，程序不会自动绕过。'],
  REGION_RESTRICTED: ['抖音返回地区访问限制，采集已停止。', '请确认当前网络是否具备该直播间的访问权限。'],
  HTTP_TIMEOUT: ['获取直播页面超时，采集已停止。', '检查网络和直播间是否可访问，再手动重试。'],
  HTTP_UNAVAILABLE: ['未能取得直播页面，采集已停止。', '检查网络和直播间是否可访问，再手动重试。'],
  RATE_LIMITED: ['抖音限制了请求频率，采集已停止。', '请稍后再试，避免连续重复点击开始。'],
  HANDSHAKE_REJECTED: ['抖音未接受消息连接，采集已停止。', '需要检查采集连接参数与平台响应；不能仅凭此结果判断是房间号或登录问题。'],
}

// These are local presentation mappings of existing diagnostic fields, not new wire enums.
const phaseOfStage = (stage: string): Phase => stage.startsWith('MODEL_') ? 'model'
  : stage.startsWith('SPEECH_') ? 'speech' : ['PLAYBACK_WAIT', 'PLAYBACK_SENT', 'STARTED', 'COMPLETED'].includes(stage) ? 'playback' : 'unknown'
const phaseOfCode = (code: string): Phase => /^MODEL_[A-Z_]+$/.test(code) ? 'model'
  : /^(SPEECH|NLS)_[A-Z_]+$/.test(code) ? 'speech'
  : ['PLAYER_FAILED', 'START_ACK_TIMEOUT', 'COMPLETION_TIMEOUT', 'AUDIO_REJECTED'].includes(code) ? 'playback' : 'unknown'
const timeout = (code: string) => ['OVERALL_TIMEOUT', 'CONNECT_TIMEOUT', 'READ_TIMEOUT', 'START_ACK_TIMEOUT', 'COMPLETION_TIMEOUT',
  'AUDIO_DOWNLOAD_TIMEOUT', 'PLAYBACK_TIMEOUT'].includes(code) || /^(MODEL|SPEECH|NLS)_(OVERALL_TIMEOUT|DEADLINE_EXCEEDED|CONNECT_TIMEOUT|READ_TIMEOUT)$/.test(code)
const successStage = { model: 'MODEL_COMPLETED', speech: 'SPEECH_COMPLETED', playback: 'COMPLETED', unknown: '' }
const at = (value: string | null) => value ? Date.parse(value) : NaN
function taskEvidence(r: RuntimeView, d: RuntimeDiagnosticsView | null) {
  const traces = [...(d?.recent ?? [])]
  if (d?.lastFailure && !traces.some(t => t.sequence === d.lastFailure!.sequence)) traces.push(d.lastFailure)
  const failures = traces.filter(t => t.stage === 'FAILED').map(f => {
    const previous = traces.filter(t => t.taskId === f.taskId && t.sequence < f.sequence).sort((a, b) => b.sequence - a.sequence)[0]
    let phase = phaseOfCode(f.reason)
    if (phase === 'unknown' && previous) phase = phaseOfStage(previous.stage)
    // Generic provider reasons need correlated evidence; do not infer from event type or service selection.
    if (phase === 'unknown' && at(r.lastErrorAt) === at(f.at)) phase = phaseOfCode(r.lastError)
    return { phase, reason: f.reason, at: f.at, sequence: f.sequence }
  })
  const runtimePhase = phaseOfCode(r.lastError)
  if (runtimePhase !== 'unknown' && Number.isFinite(at(r.lastErrorAt)) && !failures.some(f => at(f.at) === at(r.lastErrorAt)))
    failures.push({ phase: runtimePhase, reason: r.lastError, at: r.lastErrorAt, sequence: -1 })
  const latest = new Map<Phase, typeof failures[number]>()
  for (const f of failures.sort((a, b) => at(a.at) - at(b.at) || a.sequence - b.sequence)) latest.set(f.phase, f)
  return [...latest.values()].map(f => {
    const success = f.phase === 'model' ? d?.lastModelSuccessAt : f.phase === 'speech' ? d?.lastSpeechSuccessAt : f.phase === 'playback' ? d?.lastPlaybackSuccessAt : null
    const recovered = !!d && f.phase !== 'unknown' && ((at(success ?? null) > at(f.at) && at(success ?? null) <= at(d.capturedAt)) ||
      traces.some(t => t.stage === successStage[f.phase] && at(t.at) <= at(d.capturedAt) &&
        (at(t.at) > at(f.at) || (at(t.at) === at(f.at) && f.sequence >= 0 && t.sequence > f.sequence))))
    return { ...f, recovered, timedOut: timeout(f.reason) }
  }).sort((a, b) => at(b.at) - at(a.at) || b.sequence - a.sequence)
}
const connectionHelp: Record<string, string> = {
  AUDIO_INTERRUPTED: '浏览器音频中断，本页已静音', LEASE_EXPIRED: '播放租约已到期，本页已静音',
  CONNECT_TIMEOUT: '本页播放连接超时', DISCONNECTED: '本页播放连接已断开',
  PAGE_FROZEN: '页面冻结后播放已断开', INVALID_MESSAGE: '播放消息校验失败，本页已静音',
}
const localTaskErrors = new Set(['AUDIO_DOWNLOAD_FAILED', 'AUDIO_DOWNLOAD_TIMEOUT', 'AUDIO_DECODE_FAILED', 'AUDIO_TOO_LARGE', 'AUDIO_TOO_LONG', 'PLAYBACK_TIMEOUT', 'PLAYBACK_BLOCKED', 'AUDIO_INTERRUPTED'])

/** Read-only presentation. Capability flags and historical errors are not current success/failure. */
export function sessionHealth(runtime: RuntimeView | null, trace: RuntimeDiagnosticsView | null,
  local?: LocalPlayer, loadError = '', operation?: OperationIssue | null): SessionHealth {
  const result = (tone: Tone, title: string, detail: string, next = '', blocking = false, action: SessionHealth['action'] = ''): SessionHealth =>
    ({ tone, title, detail, next, blocking, action, steps: [], history: [] })
  if (loadError || !runtime) return result('warning', '暂时无法确认运行状态', '尚未取得完整后端状态，无法确认互动或声音是否仍在运行。', '检查后端服务后刷新状态；不会自动重试启动。', true, 'refresh')
  const r = runtime, c = r.collector
  const d = trace?.sessionId === r.sessionId && trace.state === r.state ? trace : null
  const active = r.state === 'RUNNING'
  const failed = c.state === 'FAILED' && (active || r.state === 'FAILED')
  const idle = r.state === 'STOPPED' || r.state === 'FAILED'
  const evidence = taskEvidence(r, d), pending = evidence.filter(f => !f.recovered)
  // Browser history is tagged locally. Never use a prior session's error or completion.
  const events = local?.history?.filter(e => e.sessionId === r.sessionId && !!r.sessionId) ?? []
  const localTask = events.find(e => e.taskId && (localTaskErrors.has(e.code) || e.code === 'COMPLETED'))
  const localFailure = localTask && localTaskErrors.has(localTask.code) ? localTask : undefined
  const problem = pending[0]
  const connectionCode = events[0]?.code
  const ready = local ? local.ready && (!local.sessionId || local.sessionId === r.sessionId) : r.player.ready
  const playing = local ? local.playing && local.sessionId === r.sessionId : r.player.playing
  const op = operation && (!operation.sessionId || operation.sessionId === r.sessionId ||
    (operation.action === 'recover' && operation.code === 'SESSION_MISMATCH' && !ready)) ? operation : null
  let summary: SessionHealth
  if (failed) {
    const help = Object.hasOwn(collectorHelp, c.lastError) ? collectorHelp[c.lastError] : undefined
    const [detail, next] = help ?? ['直播消息采集遇到错误，无法继续接收新消息。', '展开详细诊断，将采集错误码交给采集器维护窗口排查。']
    summary = result('error', (r.mode === 'DOUYIN' ? '抖音采集失败' : '消息采集失败') + (active ? '，无法继续接收消息' : '，互动已停止'),
      active ? '采集已失败；后端仍报告会话运行中，不能据此认为互动正常，也不能确认已经停止。' : detail, next, true, 'config')
  } else if (r.state === 'FAILED') {
    summary = result('error', '互动运行失败', '后端已停止本次互动，播放端也随之断开。', '查看详细诊断并检查配置，再由你手动开始。', true, 'config')
  } else if (op?.action === 'stop') {
    summary = result('warning', '停止结果尚未确认', '本页已静音，但停止请求未确认；不能仅凭本页断开认定后端已经停止。', '刷新后端状态核对；不会自动重发停止或启动请求。', true, 'refresh')
  } else if (op?.action === 'start' && idle) {
    const uncertain = ['timeout', 'network', 'invalid'].includes(op.kind)
    summary = result('warning', uncertain ? '启动结果尚未确认' : '互动未能启动', uncertain
      ? '启动请求未取得可靠结果，暂未确认活动会话；不会重复发送启动请求。' : '启动请求未成功，当前没有运行中的互动。',
      uncertain ? '先刷新状态核对后端；不要连续点击开始。' : '先核对已保存房间、启动选项与后端服务可用性，再手动尝试。', true, uncertain ? 'refresh' : 'config')
  } else if (r.state === 'STOPPED') {
    summary = result('neutral', c.state === 'ENDED' ? '直播已结束，互动已停止' : r.sessionId ? '互动已停止' : '准备开始互动', '确认消息来源和服务选项后，在舞台点击“开始互动”。', c.state === 'ENDED' ? '确认直播间重新开播后，再手动开始。' : '')
  } else if (r.state === 'STOPPING') {
    summary = result('neutral', '正在停止互动', '正在清理采集与播放任务，请等待后端确认。')
  } else if (c.state === 'ENDED') {
    summary = result('neutral', '直播已结束', '无法继续接收本场直播的新消息，后端正在结束互动。', '确认直播间重新开播后，再手动开始。', true, 'refresh')
  } else if (r.state === 'STARTING' || c.state === 'CONNECTING') {
    summary = result('neutral', '正在连接消息来源', '尚未确认收到直播消息，请等待采集连接结果。', '可刷新状态查看进展，无需重复启动。', false, 'refresh')
  } else if (c.state === 'RECONNECTING') {
    summary = result('warning', '直播连接正在恢复', '采集器正在重连，新消息接收可能中断；不代表整个会话已停止。', '先观察连接状态，无需反复启动。', false, 'refresh')
  } else if (op?.action === 'recover') {
    summary = result('warning', '未能恢复原会话播放', '本页保持静音，原会话的状态或播放连接尚未确认。', '刷新状态；恢复只会接回原会话，不会新建互动。会话已变化时，核对后点击“开始互动”接入当前会话。', false, 'refresh')
  } else if (!ready) {
    if (local?.connection === 'closed' && connectionCode === 'PLAYER_ALREADY_ACTIVE')
      summary = result('warning', '另一舞台正在占用播放端', '后端互动仍在运行，本页保持静音，不会抢占其他页面的租约。', '回到原舞台继续使用；需要本页播放时先在原页断开，再手动恢复。', false, 'refresh')
    else if (local?.connection === 'leased')
      summary = result('warning', '浏览器声音尚未启用', '已取得播放租约，但本页尚未就绪，不能据此认为音频已经播放。', '点击“启用声音”授权；不会重新创建会话。', false, 'sound')
    else if (local?.connection === 'connecting')
      summary = result('neutral', '正在连接本页播放端', '后端会话仍在运行，正在等待播放租约确认。')
    else summary = result('warning', local?.connection === 'closed' ? (connectionCode && Object.hasOwn(connectionHelp, connectionCode) ? connectionHelp[connectionCode]! : '本页播放已断开，互动仍在运行') : '互动仍在运行，播放端未就绪',
      local ? '后端会话仍在运行，本页未就绪，当前不能播放回复。' : '后端尚无就绪播放端，新的回复处理会等待声音就绪。',
      local ? local.connection === 'idle' ? '点击“开始互动”接入已有会话，不会重复创建。'
        : '手动恢复原会话播放；音频中断或租约失效时需重新授权，不会自动重启互动。'
        : '打开舞台接入已有会话，或在原舞台恢复播放。', false, 'playback')
    if (problem) summary.detail += ` 最近一次${names[problem.phase]}${problem.timedOut ? '超时' : '失败'}，尚无该环节恢复证据。`
  } else if (problem || localFailure) {
    const phase = problem?.phase ?? 'playback', timedOut = problem?.timedOut ?? timeout(localFailure!.code)
    summary = result('warning', `最近一次${names[phase]}${timedOut ? '超时' : '失败'}，互动仍在运行`,
      phase === 'model' ? '该条消息未完成模型回复，不能确认会有对应音频；其他消息仍可继续处理。'
        : phase === 'speech' ? '该任务未完成语音合成，没有对应的成功播放证据；会话未因此停止。'
        : phase === 'playback' ? '该段声音未确认完整播出，其他任务仍可继续；不会自动重播失败音频。'
        : '有任务失败，但现有记录不足以确认失败环节；会话仍在继续。',
      '先检查对应配置和服务状态；后续同一环节出现成功记录后，才视为已有恢复证据。', false, phase === 'playback' || phase === 'unknown' ? 'refresh' : 'config')
  } else if (playing) {
    summary = result('success', '正在播放回复', local ? '字幕与嘴型跟随本页实际播放。' : '后端报告播放中；完整结束以播放完成记录为准。')
  } else if (r.queues.modelActive || r.queues.speechActive) {
    summary = result('neutral', r.queues.modelActive ? '正在生成回复' : '正在合成语音', '任务正在处理，尚不能确认本次成功；完成后会交给舞台播放。')
  } else if (r.queues.playback.depth > 0 || d?.waiting.includes('PLAYBACK_WAIT')) {
    summary = result('neutral', '回复正在等待播放', '任务仍在播放队列中，租约就绪不等于已播出。')
  } else if (r.queues.comments.depth || r.queues.templates.depth || r.queues.synthesis.depth || d?.waiting.includes('WORKER_BUSY')) {
    summary = result('neutral', '消息正在等待处理', '消息已排队或工作线程尚未释放，当前尚不能确认任务成功。', '先观察处理状态，无需重启互动。')
  } else {
    summary = result('success', '互动已就绪，等待消息', r.mode === 'MOCK' ? '可展开下方测试工具，发送模拟评论或礼物。' : '播放端已就绪；采集已连接不等于收到真实评论，接收证据见采集和任务记录。')
  }
  const source = failed ? '失败' : c.state === 'RECONNECTING' ? '重连中' : c.state === 'CONNECTING' ? '连接中'
    : c.state === 'ENDED' ? '直播结束' : idle ? '已停止' : c.emitted ? `已派发 ${c.emitted} 条` : c.state === 'CONNECTED' ? '已连接，等待消息' : '尚无接收记录'
  const latestWork = d?.recent.filter(t => ['SPEECH_STARTED', 'SPEECH_COMPLETED', 'PLAYBACK_SENT', 'COMPLETED'].includes(t.stage)).sort((a, b) => b.sequence - a.sequence)[0]
  const template = latestWork && ['GIFT', 'FOLLOW', 'ENTRANCE'].includes(latestWork.eventType)
  const step = (phase: 'model' | 'speech') => {
    const failure = pending.find(f => f.phase === phase)
    const processing = phase === 'model' ? r.queues.modelActive : r.queues.speechActive
    return { label: phase === 'model' ? 'AI 回复' : '语音合成', failed: !!failure,
      value: failure ? `${failure.timedOut ? '最近超时' : '最近失败'}${processing ? '；处理中' : '，未确认恢复'}` : processing ? '处理中'
        : evidence.some(f => f.phase === phase && f.recovered) ? '失败后已有成功记录'
        : (phase === 'model' ? d?.lastModelSuccessAt : d?.lastSpeechSuccessAt) ? '有成功记录'
        : phase === 'model' && template ? '模板处理（无需模型）' : '尚无成功记录' }
  }
  summary.history = evidence.filter(f => f.recovered).map(f => `${names[f.phase]}此前失败，之后已有该环节成功记录；不表示原任务已重试。`)
  const playFailure = pending.some(f => f.phase === 'playback') || !!localFailure
  summary.steps = [
    { label: '消息采集', value: source, failed }, step('model'), step('speech'),
    { label: '声音播放', value: idle ? '已停止' : playFailure ? '最近失败，未确认恢复' : playing ? '播放中' : ready ? '待播' : '未就绪', failed: playFailure && !idle },
  ]
  // Only say "not processed" when this same session has no received event or task evidence.
  if (failed && c.received === 0 && d && !d.lastReceivedAt && !d.lastFailure && d.recent.length === 0
    && !d.lastModelSuccessAt && !d.lastSpeechSuccessAt && !d.lastPlaybackSuccessAt && !pending.length) {
    summary.steps[1]!.value = '未处理'; summary.steps[2]!.value = '未处理'
  }
  return summary
}
