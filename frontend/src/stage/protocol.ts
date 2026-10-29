// Internal validated views of playback-protocol.md; not an additional public API contract.
export type RecordValue = Record<string, unknown>
export function record(value: unknown): value is RecordValue { return !!value && typeof value === 'object' && !Array.isArray(value) }
export function id(value: unknown): value is string { return typeof value === 'string' && value.length > 0 && value.length <= 200 }
export function timestamp(value: unknown): number { return typeof value === 'string' ? Date.parse(value) : NaN }
export function generation(value: unknown): value is number { return Number.isSafeInteger(value) && Number(value) >= 0 }
export function envelope(raw: unknown) {
  if (typeof raw !== 'string' || raw.length > 32768) return null
  try {
    const v: unknown = JSON.parse(raw)
    if (!record(v) || v.schemaVersion !== 1 || !id(v.messageId) || typeof v.type !== 'string' || typeof v.sessionId !== 'string' || !record(v.payload)) return null
    return { type: v.type, sessionId: v.sessionId, payload: v.payload }
  } catch { return null }
}
export function taskView(p: RecordValue, origin: string) {
  if (!id(p.taskId) || !id(p.sessionId) || !Number.isSafeInteger(p.configVersion) || Number(p.configVersion) < 1 ||
      !id(p.type) || typeof p.text !== 'string' || p.text.length > 8000 ||
      !Number.isInteger(p.priority) || Number(p.priority) < 0 || Number(p.priority) > 100 || !Number.isFinite(timestamp(p.expiresAt)) || typeof p.audioUrl !== 'string') return null
  // Reject queries, traversal, encoded path separators, remote URLs, credentials and fragments.
  const relative = /^\/api\/v1\/audio\/[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)?$/.test(p.audioUrl)
  let url: URL
  try { url = new URL(p.audioUrl, origin) } catch { return null }
  if (url.origin !== origin || url.username || url.password || url.search || url.hash || (!relative && p.audioUrl !== `${origin}${url.pathname}`) ||
      !/^\/api\/v1\/audio\/[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)?$/.test(url.pathname)) return null
  return { taskId: p.taskId, sessionId: p.sessionId, text: p.text, audioUrl: url.pathname, expiresAt: timestamp(p.expiresAt) }
}
export type StageTask = NonNullable<ReturnType<typeof taskView>>
export const reasons: Record<string, string> = {
  COMPLETED: '播放已结束，等待下一任务。',
  SESSION_CACHE_FULL: '本页已记录 128 个停止会话，停止建立新连接；请刷新页面并重新握手。',
  CONNECT_TIMEOUT: '连接或租约握手超时，请检查服务后重连。', DISCONNECTED: '连接断开，已停止音频并清空字幕。',
  PLAYER_ALREADY_ACTIVE: '另一页面已获得播放租约，本页面保持静音。', LEASE_EXPIRED: '播放租约已到期，已停止声音，请重新连接。',
  SESSION_STOPPED: '会话已停止，已取消播放。', CANCELLED: '任务已取消。', TASK_EXPIRED: '任务已过期，已丢弃。',
  OLD_SESSION: '旧会话任务已丢弃。', DUPLICATE: '重复任务已忽略，不会重播。', NOT_READY: '尚未启用声音，任务未播放。',
  PLAYER_BUSY: '服务端同时下发多个任务，额外任务已拒绝。', INVALID_MESSAGE: '消息格式不符合播放协议，已静音断开。',
  AUDIO_DOWNLOAD_FAILED: '音频下载失败。', AUDIO_DOWNLOAD_TIMEOUT: '音频下载或解码超时。', AUDIO_DECODE_FAILED: '音频解码失败。',
  AUDIO_TOO_LARGE: '音频超过本地安全上限。', AUDIO_TOO_LONG: '音频时长超过 120 秒上限。', PLAYBACK_BLOCKED: '浏览器阻止播放，请点击启用声音。',
  AUDIO_INTERRUPTED: '音频上下文已中断，已停止播放，请重新启用声音。', PLAYBACK_TIMEOUT: '播放超时，已停止音频。',
  CACHE_FULL: '本页去重缓存已满，停止接收任务，请稍后重连。', MOCK_UNAVAILABLE: '浏览器不支持本地独占租约，mock 保持静音。',
  PAGE_HIDDEN: '页面进入后台，已静音断开；返回后请重新连接。', USER_DISCONNECTED: '已断开播放端。',
}
