import type { Bootstrap, ConfigSnapshot, Settings } from './contracts'

export class RequestError extends Error {
  readonly kind: 'conflict' | 'timeout' | 'network' | 'invalid' | 'http'
  readonly code: string
  constructor(kind: RequestError['kind'], message: string, code = '') { super(message); this.kind = kind; this.code = code }
}
const integer = (v: unknown, min: number, max: number) => Number.isInteger(v) && Number(v) >= min && Number(v) <= max
const string = (v: unknown, min: number, max: number) => typeof v === 'string' && v.length >= min && v.length <= max
export function isSnapshot(value: unknown): value is ConfigSnapshot {
  if (!value || typeof value !== 'object') return false
  const v = value as ConfigSnapshot, s = v.settings
  return integer(v.version, 1, Number.MAX_SAFE_INTEGER) && typeof v.updatedAt === 'string' && Number.isFinite(Date.parse(v.updatedAt)) && !!s &&
    string(s.profileName, 1, 80) && string(s.persona, 0, 8000) && typeof s.roomId === 'string' && /^[0-9]{0,30}$/.test(s.roomId) &&
    string(s.model, 1, 100) && string(s.voice, 1, 100) && typeof s.readComment === 'boolean' && integer(s.maxOutputTokens, 32, 2048) &&
    integer(s.welcomeIntervalSeconds, 5, 600) && integer(s.synthesisQueueCapacity, 1, 100) && integer(s.playbackQueueCapacity, 1, 100) &&
    !!s.priorities && ['gift', 'comment', 'follow', 'entrance'].every(k => integer(s.priorities[k as keyof Settings['priorities']], 0, 100))
}
export function isBootstrap(value: unknown): value is Bootstrap {
  if (!value || typeof value !== 'object') return false
  const v = value as Bootstrap
  return typeof v.application === 'string' && typeof v.version === 'string' && ['SCAFFOLD', 'READY'].includes(v.mode) &&
    typeof v.sessionState === 'string' && !!v.capabilities && typeof v.capabilities === 'object' && Object.values(v.capabilities).every(x => typeof x === 'boolean')
}
// Only published fields may be sent back; unexpected server properties are never echoed.
export function copySettings(s: Settings): Settings {
  return { profileName: s.profileName, persona: s.persona, roomId: s.roomId, model: s.model, maxOutputTokens: s.maxOutputTokens,
    voice: s.voice, readComment: s.readComment, welcomeIntervalSeconds: s.welcomeIntervalSeconds,
    synthesisQueueCapacity: s.synthesisQueueCapacity, playbackQueueCapacity: s.playbackQueueCapacity,
    priorities: { gift: s.priorities.gift, comment: s.priorities.comment, follow: s.priorities.follow, entrance: s.priorities.entrance } }
}
export function createApi(fetcher: typeof fetch = fetch, timeoutMs = 8000) {
  async function request<T>(path: string, validate: (v: unknown) => v is T, init?: RequestInit, signal?: AbortSignal): Promise<T> {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    const cancel = () => controller.abort()
    signal?.addEventListener('abort', cancel, { once: true })
    if (signal?.aborted) cancel()
    try {
      const response = await fetcher(`/api/v1${path}`, { ...init, signal: controller.signal, headers: { 'Content-Type': 'application/json' } })
      if (response.status === 409) throw new RequestError('conflict', '保存冲突：另一页面已更新配置。当前草稿已保留，请先读取最新版本再核对。')
      if (!response.ok) throw new RequestError('http', response.status === 400 ? '配置校验失败，请检查字段范围。' : `请求失败（HTTP ${response.status}），请检查后端服务。`)
      const body: unknown = await response.json()
      if (!validate(body)) throw new RequestError('invalid', '服务响应格式不符合当前契约，请联系集成窗口。')
      return body
    } catch (error) {
      if (error instanceof RequestError) throw error
      if (controller.signal.aborted) throw new RequestError('timeout', '请求超时，结果尚未确认。草稿已保留，请读取最新版本后核对。')
      if (error instanceof SyntaxError) throw new RequestError('invalid', '服务未返回有效 JSON，请检查接口与代理。')
      throw new RequestError('network', '无法连接服务，请检查后端和网络后重试。')
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', cancel) }
  }
  return {
    bootstrap: (signal?: AbortSignal) => request('/bootstrap', isBootstrap, undefined, signal),
    config: (signal?: AbortSignal) => request('/config', isSnapshot, undefined, signal),
    save: (expectedVersion: number, settings: Settings) => request('/config', isSnapshot, { method: 'PUT', body: JSON.stringify({ expectedVersion, settings: copySettings(settings) }) }),
  }
}
export const api = createApi()
