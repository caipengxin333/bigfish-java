import type { ConversationMode, EventResult, ModelCheck, ProvidersView, RuntimeView, SpeechMode } from './contracts.ts'
import { RequestError } from './api.ts'
const object = (x: unknown): x is Record<string, unknown> => !!x && typeof x === 'object' && !Array.isArray(x)
const count = (x: unknown) => Number.isSafeInteger(x) && Number(x) >= 0
export function isRuntime(x: unknown): x is RuntimeView {
  if (!object(x) || !object(x.queues) || !object(x.player) || !object(x.collector)) return false
  const q = x.queues, p = x.player, c = x.collector
  return typeof x.sessionId === 'string' && typeof x.roomId === 'string' &&
    ['STOPPED', 'STARTING', 'RUNNING', 'STOPPING', 'FAILED'].includes(String(x.state)) &&
    x.mode === 'MOCK' && ['MOCK', 'DEEPSEEK'].includes(String(x.conversationMode)) && ['LOCAL_TEST_TONE', 'ALIYUN_NLS'].includes(String(x.speechMode)) &&
    typeof x.lastError === 'string' && typeof x.lastErrorAt === 'string' &&
    [x.savedConfigVersion, x.appliedConfigVersion, x.dropped, x.failures, p.leaseGeneration, c.received, c.emitted, c.duplicates, c.rejected].every(count) &&
    [q.modelActive, q.speechActive, p.connected, p.ready, p.playing].every(v => typeof v === 'boolean') &&
    ['comments', 'templates', 'synthesis', 'playback'].every(k => object(q[k]) && count(q[k].depth) && count(q[k].capacity) && Number(q[k].capacity) > 0) &&
    c.mode === 'MOCK' && ['STOPPED', 'CONNECTING', 'CONNECTED', 'RECONNECTING', 'ENDED', 'FAILED'].includes(String(c.state)) && typeof c.lastError === 'string'
}
const strings = (x: unknown): x is string[] => Array.isArray(x) && x.every(v => typeof v === 'string')
export function isProviders(x: unknown): x is ProvidersView {
  return object(x) && ['deepseek', 'aliyun', 'douyin'].every(k => {
    const p = x[k]; return object(p) && typeof p.configured === 'boolean' && typeof p.enabled === 'boolean' && typeof p.state === 'string' && typeof p.reason === 'string'
  }) && strings(x.aliyunVoices) && count(x.speechTextLimit) && count(x.audioDurationLimitSeconds)
}
export function isModelCheck(x: unknown): x is ModelCheck {
  return object(x) && ['authentication', 'selectedModel', 'generation'].every(k => ['NOT_CHECKED', 'PASSED', 'FAILED'].includes(String(x[k]))) &&
    strings(x.availableModels) && typeof x.code === 'string' && count(x.elapsedMs)
}
const results: EventResult[] = ['ACCEPTED', 'DUPLICATE', 'REJECTED_FULL', 'REJECTED_SESSION', 'INVALID']
const messages: Record<string, string> = {
  SESSION_ACTIVE: '已有运行会话，请刷新状态。', SESSION_MISMATCH: '会话已变化，请刷新状态。',
  PLAYER_NOT_READY: '请先在舞台连接播放端并启用声音。', DOUYIN_UNAVAILABLE: '真实抖音连接尚未接入。',
  PROVIDER_UNAVAILABLE: '模型服务不可用，请检查服务端凭据和显式启用设置。',
  PAID_CALLS_DISABLED: '后端尚未启用云服务调用：请设置 BIGFISH_ALLOW_PAID=true 后重启后端。',
  DEEPSEEK_MISSING_CREDENTIALS: '后端未配置 DeepSeek API Key。',
  NLS_MISSING_CREDENTIALS: '后端未配置阿里云 NLS AppKey 和 Token。',
  NLS_UNSUPPORTED_VOICE: '音色不受阿里云 NLS 支持，请先在配置页保存 xiaoyun 等支持的音色。',
  AUTHENTICATION_FAILED: 'DeepSeek 凭据验证失败。', RATE_LIMITED: '服务正在限流，请稍后重试。',
}
export function createRuntimeApi(fetcher: typeof fetch = fetch, timeout = 8000) {
  async function request<T>(path: string, validate: (v: unknown) => v is T, body?: unknown, event = false, deadline = timeout): Promise<T> {
    const abort = new AbortController(), timer = setTimeout(() => abort.abort(), deadline)
    try {
      const response = await fetcher(`/api/v1${path}`, { method: body === undefined ? 'GET' : 'POST',
        headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), signal: abort.signal })
      const data: unknown = await response.json()
      if (!response.ok && !(event && [400, 409, 429].includes(response.status) && validate(data))) {
        const code = object(data) && typeof data.code === 'string' ? data.code : ''
        throw new RequestError('http', messages[code] ?? `操作失败（HTTP ${response.status}），请刷新状态后核对。`)
      }
      if (!validate(data)) throw new RequestError('invalid', '运行接口响应格式不符合契约。')
      return data
    } catch (e) {
      if (e instanceof RequestError) throw e
      throw new RequestError(abort.signal.aborted ? 'timeout' : 'network', abort.signal.aborted
        ? '请求超时，操作结果未确认。请刷新状态，不要连续重复启动。' : '无法读取运行接口，请检查后端连接。')
    } finally { clearTimeout(timer) }
  }
  return {
    status: () => request('/runtime', isRuntime),
    providers: () => request('/providers', isProviders),
    checkModel: () => request('/providers/deepseek/check', isModelCheck, {}, false, Math.max(timeout, 15000)),
    start: (conversationMode: ConversationMode = 'MOCK', speechMode: SpeechMode = 'LOCAL_TEST_TONE') => request('/sessions', isRuntime, { mode: 'MOCK', conversationMode, speechMode }),
    stop: (session: string) => request(`/sessions/${encodeURIComponent(session)}/stop`, isRuntime, {}),
    replay: (session: string) => request(`/sessions/${encodeURIComponent(session)}/replay`, isRuntime, {}),
    simulate: (session: string, type: 'COMMENT' | 'GIFT') => request('/events/simulate',
      (v: unknown): v is { result: EventResult } => object(v) && results.includes(v.result as EventResult), {
        schemaVersion: 1, eventId: crypto.randomUUID(), sessionId: session, roomId: 'mock-room', type,
        userId: 'console-demo', username: '模拟观众', receivedAt: new Date().toISOString(),
        payload: type === 'COMMENT' ? { content: '你好，大肥鱼！' } : { giftId: 'heart', giftName: '小心心',
          quantity: 1, comboId: crypto.randomUUID(), unitValue: null, valueUnit: 'DOUYIN_DIAMOND' },
      }, true),
  }
}
export const runtimeApi = createRuntimeApi()
