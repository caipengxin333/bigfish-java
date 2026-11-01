import type { RuntimeDiagnosticsView, RuntimeView } from './contracts.ts'

type LocalPlayer = { connection: string; ready: boolean; playing: boolean }
type Tone = 'neutral' | 'warning' | 'error' | 'success'
export interface SessionHealth {
  tone: Tone; title: string; detail: string; next: string; blocking: boolean
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

/** Read-only presentation. Capability flags and historical errors are not current success/failure. */
export function sessionHealth(runtime: RuntimeView | null, trace: RuntimeDiagnosticsView | null,
  local?: LocalPlayer, loadError = ''): SessionHealth {
  const result = (tone: Tone, title: string, detail: string, next = '', blocking = false): SessionHealth =>
    ({ tone, title, detail, next, blocking, steps: [] })
  if (loadError || !runtime) return result('warning', '暂时无法确认运行状态', '尚未取得完整后端状态，请检查服务是否正在运行。', '刷新状态后再操作，避免重复启动。', true)
  const r = runtime, c = r.collector
  const d = trace?.sessionId === r.sessionId && trace.state === r.state ? trace : null
  const active = r.state === 'RUNNING'
  const failed = c.state === 'FAILED' && (active || r.state === 'FAILED')
  const idle = r.state === 'STOPPED' || r.state === 'FAILED'
  let summary: SessionHealth
  if (failed) {
    const [detail, next] = collectorHelp[c.lastError] ?? ['直播消息采集遇到错误，本次互动已停止。', '展开详细诊断，将采集错误码交给采集器维护窗口排查。']
    summary = result('error', r.mode === 'DOUYIN' ? '抖音采集失败，互动已停止' : '消息采集失败，互动已停止', detail, next, true)
  } else if (r.state === 'FAILED') {
    summary = result('error', '互动运行失败', '后端已停止本次互动，播放端也随之断开。', '展开详细诊断查看本次错误，再处理对应环节。', true)
  } else if (r.state === 'STOPPED') {
    summary = result('neutral', r.sessionId ? '互动已停止' : '准备开始互动', '确认消息来源和服务选项后，点击“开始互动”。')
  } else if (r.state === 'STOPPING') {
    summary = result('neutral', '正在停止互动', '正在清理采集与播放任务，请等待后端确认。')
  } else if (r.state === 'STARTING' || c.state === 'CONNECTING') {
    summary = result('neutral', '正在连接消息来源', '尚未确认收到直播消息，请等待采集连接结果。')
  } else if (c.state === 'RECONNECTING') {
    summary = result('warning', '直播连接正在恢复', '采集器正在重连；历史错误不代表本次已经失败。', '先观察连接状态，无需反复启动。')
  } else if (c.state === 'ENDED') {
    summary = result('neutral', '直播已结束', '正在结束本次互动，请确认直播间重新开播后再开始。')
  } else if (local ? !local.ready : !r.player.ready) {
    summary = result('warning', '互动已启动，声音尚未就绪', local?.connection === 'leased'
      ? '已取得播放连接，等待浏览器允许声音。' : '后端会话仍在运行，播放端尚未就绪。', local ? '按下方播放提示启用声音或恢复播放；其他舞台占用时先释放它。' : '打开舞台并点击“开始互动”接入，或恢复原舞台播放。')
  } else if (local?.playing || r.player.playing) {
    summary = result('success', '正在播放回复', '字幕与嘴型跟随实际播放。')
  } else if (r.queues.modelActive || r.queues.speechActive) {
    summary = result('neutral', r.queues.modelActive ? '正在生成回复' : '正在合成语音', '处理完成后会自动交给舞台播放。')
  } else {
    summary = result('success', '互动已就绪，等待消息', r.mode === 'MOCK' ? '可展开下方测试工具，发送模拟评论或礼物。' : '播放端已就绪；是否收到直播消息以采集和任务记录为准。')
  }
  const source = failed ? '失败' : c.state === 'RECONNECTING' ? '重连中' : c.state === 'CONNECTING' ? '连接中'
    : c.state === 'ENDED' ? '直播结束' : idle ? '已停止' : c.emitted ? `已派发 ${c.emitted} 条` : '等待消息'
  summary.steps = [
    { label: '消息采集', value: source, failed },
    { label: 'AI 回复', value: r.queues.modelActive ? '处理中' : d?.lastModelSuccessAt ? '有成功记录' : '尚无成功记录' },
    { label: '语音合成', value: r.queues.speechActive ? '处理中' : d?.lastSpeechSuccessAt ? '有成功记录' : '尚无成功记录' },
    { label: '声音播放', value: idle ? '已停止' : (local?.playing || r.player.playing) ? '播放中' : (local ? local.ready : r.player.ready) ? '待播' : '未就绪' },
  ]
  // Only say "not processed" when this same session has no received event or task evidence.
  if (failed && c.received === 0 && d && !d.lastReceivedAt && d.recent.length === 0
    && !d.lastModelSuccessAt && !d.lastSpeechSuccessAt && !d.lastPlaybackSuccessAt) {
    summary.steps[1]!.value = '未处理'; summary.steps[2]!.value = '未处理'
  }
  return summary
}
