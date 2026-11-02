import { computed, ref, shallowRef } from 'vue'

// Frontend view model / injectable UI port ONLY. No published HTTP DTO or endpoint is invented here.
export type AccessMode = 'visitor' | 'cookie'
export interface IdentityStatus {
  generation: number; revision: number; choice: AccessMode; agentPresent: boolean; manualIdPresent: boolean
  retentionMinutes: number; configured: boolean; updatedAt: number | null; expiresAt: number | null
}
export interface OptionDraft { choice: AccessMode; agent: string; manual: boolean; visitorId: string; retentionMinutes: number }
export type CheckResult = { kind: 'connected' | 'comments' | 'gifts'; comments: number; gifts: number }
export type CheckScenario = CheckResult['kind'] | 'failed' | 'timeout' | 'id-required'
export interface IdentityPort {
  read(signal: AbortSignal): Promise<IdentityStatus>
  options(value: OptionDraft, expected: IdentityStatus, signal: AbortSignal): Promise<IdentityStatus>
  replace(secret: string, expected: IdentityStatus, signal: AbortSignal): Promise<IdentityStatus>
  clear(expected: IdentityStatus, signal: AbortSignal): Promise<IdentityStatus>
  check(expected: IdentityStatus, roomId: string, signal: AbortSignal): Promise<CheckResult>
}
export class IdentityError extends Error {
  readonly code: string
  constructor(code: string) { super(code); this.code = code }
}
const errors: Record<string, string> = {
  TIMEOUT: '请求超时，结果尚未确认。输入已保留，请刷新状态核对后再操作；不会自动重试。',
  SAVE_FAILED: '保存失败，输入已保留，请检查服务后重试。',
  READ_FAILED: '身份状态读取失败，当前配置无法确认。请刷新状态后再操作。',
  CONFLICT: '身份配置已被另一页面修改或服务已重启。输入已保留，请刷新状态核对。',
  CHECK_FAILED: '连接检查失败。请核对已保存房间、Cookie 与同一浏览器的 User-Agent，再手动检查。',
  ID_REQUIRED: '采集器未能从直播间页面解析 user_unique_id，可能是页面结构变化或当前身份受限。可在高级设置补填同一浏览器 WebSocket 请求的该查询参数，再保存接入选项。',
  NOT_CONFIGURED: '尚未配置有效 Cookie，请先保存 Cookie；不会自动改用游客模式。',
  LOCKED: '互动正在运行或状态尚未确认，请先停止互动并刷新状态。',
  OPTIONS_FIRST: '请先保存接入选项，再保存 Cookie 或检查连接。',
  ROOM_REQUIRED: '请先在基础配置中填写并保存房间号；本次不会自动保存表单。',
  COOKIE_REQUIRED: '请输入待保存的新 Cookie。空输入不会覆盖已有 Cookie。',
  INVALID: '输入格式不正确。请检查 User-Agent、数字访客 ID 和保存期限。',
  UNAVAILABLE: '身份管理公共契约尚未接入，当前不能读取或修改真实凭据。',
}
export function identityFailure(error: unknown): string {
  const code = error instanceof IdentityError ? error.code : ''
  return Object.hasOwn(errors, code) ? errors[code] : '操作失败，输入已保留。请刷新状态后重试。'
}
export function createIdentityEditor(port: IdentityPort | null, context: () => { locked: boolean; roomId?: string; roomPending: boolean }, timeoutMs = 8000) {
  const status = shallowRef<IdentityStatus | null>(null), busy = ref(''), error = ref(''), notice = ref('')
  const choice = ref<AccessMode>('visitor'), agent = ref(''), manual = ref(false), visitorId = ref(''), retentionMinutes = ref(60), cookie = ref('')
  const checkResult = shallowRef<CheckResult | null>(null), needsId = ref(false), clearConfirm = ref(false)
  const checkIssue = ref(false)
  let revision = 0, pending: AbortController | undefined, disposed = false
  const dirty = computed(() => !!status.value && (choice.value !== status.value.choice || !!agent.value.trim() || manual.value !== status.value.manualIdPresent || !!visitorId.value.trim() || retentionMinutes.value !== status.value.retentionMinutes))
  const canEdit = computed(() => !!port && !!status.value && !context().locked && !busy.value)
  function fail(code: string) { error.value = identityFailure(new IdentityError(code)) }
  function apply(value: IdentityStatus, reset: boolean) {
    if (status.value && value.generation !== status.value.generation) notice.value = '服务状态已重置，请重新确认身份与 Cookie 配置。'
    status.value = { ...value }
    if (reset) { choice.value = value.choice; agent.value = ''; manual.value = value.manualIdPresent; visitorId.value = ''; retentionMinutes.value = value.retentionMinutes }
  }
  async function run<T>(name: string, work: (signal: AbortSignal) => Promise<T>, accept: (value: T) => void) {
    if (disposed || busy.value || !port) return
    const token = ++revision, abort = pending = new AbortController()
    busy.value = name; error.value = ''; notice.value = ''; clearConfirm.value = false; checkIssue.value = name === 'check'
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      const value = await Promise.race([work(abort.signal), new Promise<never>((_, reject) => { timer = setTimeout(() => { abort.abort(); reject(new IdentityError('TIMEOUT')) }, timeoutMs) })])
      if (!disposed && token === revision && !abort.signal.aborted) accept(value)
    } catch (e) {
      if (!disposed && token === revision) {
        error.value = identityFailure(e); checkResult.value = null
        if (e instanceof IdentityError && e.code === 'ID_REQUIRED') needsId.value = true
        if (name === 'read' || (e instanceof IdentityError && ['TIMEOUT', 'CONFLICT'].includes(e.code))) status.value = null
      }
    } finally { clearTimeout(timer); if (token === revision) { busy.value = ''; pending = undefined } }
  }
  function guard(): boolean {
    if (!port) { fail('UNAVAILABLE'); return false }
    if (context().locked) { fail('LOCKED'); return false }
    if (!status.value) { fail('READ_FAILED'); return false }
    return !busy.value && !disposed
  }
  async function refresh() {
    const preserve = dirty.value
    await run('read', signal => port!.read(signal), value => { apply(value, !preserve); checkResult.value = null })
  }
  async function saveOptions() {
    checkIssue.value = false
    if (!guard()) return
    const value = { choice: choice.value, agent: agent.value.trim(), manual: manual.value, visitorId: visitorId.value.trim(), retentionMinutes: retentionMinutes.value }
    if (!['visitor', 'cookie'].includes(value.choice) || ![30, 60, 240, 1440].includes(value.retentionMinutes) || value.agent.length > 1024 || /[\r\n]/.test(value.agent) ||
      (value.manual && !/^[0-9]{1,30}$/.test(value.visitorId) && !(status.value!.manualIdPresent && !value.visitorId)) ||
      (value.choice === 'cookie' && !value.agent && !status.value!.agentPresent)) { fail('INVALID'); return }
    await run('options', signal => port!.options(value, status.value!, signal), result => {
      apply(result, true); checkResult.value = null; needsId.value = false; notice.value = '接入选项已保存，下次启动生效；没有提交或替换 Cookie。'
    })
  }
  async function saveCookie() {
    checkIssue.value = false
    if (!guard()) return
    if (dirty.value || choice.value !== 'cookie') { fail('OPTIONS_FIRST'); return }
    if (!cookie.value.trim()) { fail('COOKIE_REQUIRED'); return }
    if (cookie.value.length > 16384 || /[\r\n]/.test(cookie.value)) { fail('INVALID'); return }
    await run('cookie', signal => port!.replace(cookie.value, status.value!, signal), result => {
      apply(result, false); cookie.value = ''; checkResult.value = null; notice.value = 'Cookie 提交成功，敏感输入已清空。仅表示已配置，不代表平台登录有效；下次启动生效。'
    })
  }
  async function clearCookie(confirmed = false) {
    checkIssue.value = false
    if (!guard() || !confirmed || !clearConfirm.value) return
    await run('clear', signal => port!.clear(status.value!, signal), result => {
      apply(result, false); cookie.value = ''; checkResult.value = null; notice.value = 'Cookie 已明确清除。接入模式未自动切换。'
    })
  }
  async function check() {
    checkIssue.value = true
    if (!guard()) return
    if (dirty.value) { fail('OPTIONS_FIRST'); return }
    if (context().roomPending || !/^[0-9]{1,30}$/.test(context().roomId ?? '')) { fail('ROOM_REQUIRED'); return }
    if (choice.value === 'cookie' && !status.value!.configured) { fail('NOT_CONFIGURED'); return }
    checkResult.value = null; needsId.value = false
    await run('check', signal => port!.check(status.value!, context().roomId!, signal), value => { checkResult.value = value })
  }
  function invalidate() {
    if (busy.value && busy.value !== 'read') { status.value = null; error.value = '操作已中断，结果尚未确认。输入已保留，请刷新身份状态后核对。' }
    ++revision; pending?.abort(); pending = undefined; busy.value = ''; checkResult.value = null; clearConfirm.value = false
  }
  async function contextChanged() {
    invalidate()
    error.value = ''; notice.value = ''; needsId.value = false; checkIssue.value = false
    // Re-read metadata only. A parent config load can invalidate our initial read;
    // never leave the editor disabled forever or restart a probe/mutation here.
    await refresh()
  }
  function dispose() { disposed = true; invalidate(); cookie.value = ''; agent.value = ''; visitorId.value = '' }
  return { status, choice, agent, manual, visitorId, retentionMinutes, cookie, busy, error, notice, checkResult, checkIssue, needsId, clearConfirm, dirty, canEdit,
    refresh, saveOptions, saveCookie, clearCookie, check, invalidate, contextChanged, dispose }
}
