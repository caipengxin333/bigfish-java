import type { DouyinCredentialStatus, DouyinCredentialReplacement, DouyinConnectionCheck } from '../contracts.ts'

export class AccessError extends Error {
  readonly code: string
  constructor(code: string) { super(code); this.code = code }
}
const code = (x: unknown): x is string => typeof x === 'string' && /^[A-Z_]{0,80}$/.test(x)
const uuid = (x: unknown): x is string => typeof x === 'string' && /^[0-9a-f-]{36}$/.test(x)
const count = (x: unknown): x is number => Number.isSafeInteger(x) && Number(x) >= 0
const date = (x: unknown): x is string => typeof x === 'string' && Number.isFinite(Date.parse(x))
const modes = ['GUEST', 'EXTERNAL', 'INVALID'], sources = ['DEFAULT', 'ENVIRONMENT', 'RUNTIME']
export function isCredentialStatus(value: unknown): value is DouyinCredentialStatus {
  if (!value || typeof value !== 'object') return false
  const v = value as DouyinCredentialStatus
  return modes.includes(v.mode) && sources.includes(v.source) && count(v.version) && uuid(v.revision) && date(v.updatedAt) &&
    typeof v.cookieConfigured === 'boolean' && (v.localExpiresAt === null || date(v.localExpiresAt)) && code(v.validation) &&
    ['AUTO', 'MANUAL'].includes(v.identityMode) && ['NONE', 'INTERACTION', 'INTERACTION_STOPPING', 'CHECK', 'CHECK_STOPPING'].includes(v.activeUse) &&
    (v.activeVersion === null || count(v.activeVersion)) && !('cookie' in v || 'userAgent' in v || 'userUniqueIdOverride' in v)
}
export function isConnectionCheck(value: unknown): value is DouyinConnectionCheck {
  if (!value || typeof value !== 'object') return false
  const v = value as DouyinConnectionCheck
  return uuid(v.checkId) && uuid(v.configRevision) && count(v.configVersion) && modes.includes(v.mode) && sources.includes(v.source) &&
    code(v.localValidation) && ['CONNECTING', 'OBSERVING', 'STOPPING', 'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(v.state) &&
    code(v.reason) && code(v.cleanupError) && typeof v.connected === 'boolean' && count(v.comments) && count(v.gifts) &&
    date(v.startedAt) && date(v.deadline) && typeof v.resourcesReleased === 'boolean'
}
export function createIdentityApi(fetcher: typeof fetch = fetch, timeoutMs = 8000) {
  async function request<T>(path: string, validate: (v: unknown) => v is T, signal?: AbortSignal, method = 'GET', data?: unknown): Promise<T> {
    const ctrl = new AbortController(), cancel = () => ctrl.abort()
    signal?.addEventListener('abort', cancel, { once: true }); if (signal?.aborted) cancel()
    const timer = setTimeout(cancel, timeoutMs)
    try {
      const response = await fetcher(`/api/v1/douyin/credentials${path}`, {
        method, signal: ctrl.signal, cache: 'no-store', credentials: 'same-origin',
        headers: { 'X-BigFish-Local-Management': '1', ...(data === undefined ? {} : { 'Content-Type': 'application/json' }) },
        body: data === undefined ? undefined : JSON.stringify(data),
      })
      if (!response.ok) {
        let errorCode = response.status === 404 ? 'API_UNAVAILABLE' : 'REQUEST_FAILED'
        try { const body: unknown = await response.json(); if (body && typeof body === 'object' && 'code' in body && code(body.code)) errorCode = body.code } catch { /* never return response text */ }
        throw new AccessError(errorCode)
      }
      const body: unknown = await response.json()
      if (!validate(body)) throw new AccessError('INVALID_RESPONSE')
      return body
    } catch (e) {
      if (e instanceof AccessError) throw e
      throw new AccessError(ctrl.signal.aborted ? 'REQUEST_TIMEOUT' : 'NETWORK_FAILED')
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', cancel) }
  }
  return {
    read: (signal?: AbortSignal) => request('', isCredentialStatus, signal),
    guest: (revision: string, signal?: AbortSignal) => request('/mode', isCredentialStatus, signal, 'PUT', { expectedRevision: revision, mode: 'GUEST' }),
    replace: (value: DouyinCredentialReplacement, signal?: AbortSignal) => request('/cookie', isCredentialStatus, signal, 'PUT', {
      expectedRevision: value.expectedRevision, replace: true, cookie: value.cookie, userAgent: value.userAgent,
      userUniqueIdOverride: value.userUniqueIdOverride, localLifetimeSeconds: value.localLifetimeSeconds, localExpiresAt: value.localExpiresAt,
    }),
    clear: (revision: string, signal?: AbortSignal) => request('/clear', isCredentialStatus, signal, 'POST', { expectedRevision: revision, confirm: true }),
    start: (revision: string, roomId: string, signal?: AbortSignal) => request('/checks', isConnectionCheck, signal, 'POST', { expectedRevision: revision, roomId, durationSeconds: 20 }),
    check: (id: string, signal?: AbortSignal) => request(`/checks/${encodeURIComponent(id)}`, isConnectionCheck, signal),
    cancel: (id: string, signal?: AbortSignal) => request(`/checks/${encodeURIComponent(id)}/cancel`, isConnectionCheck, signal, 'POST', {}),
  }
}
export type IdentityApi = ReturnType<typeof createIdentityApi>
const messages: Record<string, string> = {
  API_UNAVAILABLE: '后端尚未提供身份接口。请启动已合并的新版本，再刷新状态。',
  INVALID_RESPONSE: '身份接口响应与当前版本不一致，请核对前后端版本。',
  NETWORK_FAILED: '无法连接后端，输入已保留，请检查服务。',
  REQUEST_TIMEOUT: '请求超时，结果尚未确认。请刷新身份状态核对，不会自动重试。',
  CREDENTIAL_CONFIG_CONFLICT: '配置已被另一页面修改或后端已重启，请刷新状态后核对输入。',
  SESSION_ACTIVE: '互动正在运行，请先停止互动。', COLLECTOR_STOPPING: '采集器仍在退出，请稍后刷新状态。',
  CHECK_ACTIVE: '已有连接检查正在运行，请等待或取消。', CHECK_STOPPING: '连接检查仍在释放资源，请稍后刷新。',
  CHECK_COOLDOWN: '检查启动后需间隔 30 秒才能再次检查。', CHECK_ATTEMPT_LIMIT: '五分钟内最多检查三次，请稍后再试。',
  CREDENTIALS_MISSING: '请同时提供完整 Cookie 和同一浏览器的 User-Agent。',
  CREDENTIALS_INVALID: '凭据格式不正确，请检查是否复制了字段名、换行、截断内容或错误的访客标识。',
  CREDENTIALS_EXPIRED: '本机使用期限已到，请重新获取并保存凭据。',
  EXTERNAL_ID_MISSING: '无法自动解析访客标识，请在高级设置补填同一会话的 user_unique_id。',
  EXTERNAL_ID_CONFLICT: '手填访客标识与当前页面身份不一致，请从同一浏览器会话重新获取。',
  PAGE_STRUCTURE_CHANGED: '直播页面结构无法识别，请核对直播间或交由采集窗口适配。',
  AUTH_REQUIRED: '平台要求登录或验证，请在抖音页面完成后更新 Cookie。',
  ROOM_ENDED: '直播间已结束，请换到正在直播的房间。',
  CHECK_TIMEOUT: '观察时间内未能建立连接，请核对房间和凭据。',
  LOCAL_DEADLINE_INVALID: '本机使用期限须在 1 分钟到 7 天之间。',
  INVALID_REQUEST: '输入校验未通过，请检查各项内容。', REQUEST_TOO_LARGE: '提交内容过长，请仅填写必要字段。',
  ORIGIN_REJECTED: '管理请求来源不匹配，请通过本机前端同源代理访问。',
  LOCAL_ACCESS_REQUIRED: 'Cookie 配置只允许本机访问。',
}
export function accessMessage(error: unknown) {
  return error instanceof AccessError && Object.hasOwn(messages, error.code) ? messages[error.code] : '操作失败，请核对配置并刷新状态后重试。'
}
