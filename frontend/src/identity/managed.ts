import { computed, ref, shallowRef } from 'vue'
import type { DouyinCredentialStatus, DouyinConnectionCheck } from '../contracts.ts'
import { AccessError, accessMessage, type IdentityApi } from './api.ts'

export function createManagedIdentity(api: IdentityApi, context: () => { locked: boolean; roomId?: string; roomPending: boolean }, pollMs = 500) {
  const status = shallowRef<DouyinCredentialStatus | null>(null), result = shallowRef<DouyinConnectionCheck | null>(null)
  const mode = ref<'GUEST' | 'EXTERNAL'>('GUEST'), cookie = ref(''), agent = ref(''), visitor = ref(''), manual = ref(false), minutes = ref(60)
  const busy = ref(''), error = ref(''), notice = ref(''), confirmClear = ref(false)
  let epoch = 0, pending: AbortController | undefined, checkId: string | undefined, disposed = false
  let savedMinutes = 60
  const dirty = computed(() => !!cookie.value || !!agent.value || !!visitor.value || (status.value !== null &&
    (mode.value !== status.value.mode || (mode.value === 'EXTERNAL' &&
      (manual.value !== (status.value.identityMode === 'MANUAL') || minutes.value !== savedMinutes)))))
  const locked = computed(() => context().locked || !status.value || status.value.activeUse !== 'NONE')
  const canEdit = computed(() => !locked.value && !busy.value)
  function clean() { cookie.value = ''; agent.value = ''; visitor.value = '' }
  function apply(value: DouyinCredentialStatus, reset = false) {
    const preserveDraft = dirty.value
    if (reset) savedMinutes = minutes.value
    if (status.value && value.revision !== status.value.revision) result.value = null
    status.value = value
    if (reset || !preserveDraft) { mode.value = value.mode === 'EXTERNAL' ? 'EXTERNAL' : 'GUEST'; manual.value = value.identityMode === 'MANUAL' }
  }
  async function run<T>(name: string, work: (signal: AbortSignal, token: number) => Promise<T>, accept: (v: T) => void) {
    if (disposed || busy.value) return
    const token = ++epoch, ctrl = pending = new AbortController()
    busy.value = name; error.value = ''; notice.value = ''; confirmClear.value = false
    const timer = setTimeout(() => ctrl.abort(), name === 'check' ? 45000 : 10000)
    try {
      const value = await work(ctrl.signal, token)
      if (!disposed && token === epoch && !ctrl.signal.aborted) accept(value)
    } catch (e) {
      if (!disposed && token === epoch) {
        error.value = accessMessage(e); result.value = null
        // A failed mutation may have reached the server. Require a fresh revision before another write.
        status.value = null
      }
    } finally {
      clearTimeout(timer)
      if (token === epoch) { pending = undefined; busy.value = '' }
    }
  }
  function guard() { if (!canEdit.value) { error.value = '请先停止互动或连接检查，并刷新身份状态。'; return false } return true }
  async function refresh() {
    await run('read', signal => api.read(signal), value => { apply(value); result.value = null })
  }
  async function save() {
    if (!guard()) return
    const expected = status.value!.revision
    if (mode.value === 'GUEST') {
      await run('save', signal => api.guest(expected, signal), value => {
        clean(); apply(value, true); result.value = null; notice.value = '已切换为游客，运行时 Cookie 已清除；下次启动生效。'
      }); return
    }
    if (!cookie.value.trim() || !agent.value.trim()) { error.value = '请同时填写完整 Cookie 和同一浏览器的 User-Agent；替换时也需要重新提供。'; return }
    if (cookie.value.length > 16384 || agent.value.length > 2048 || /[^\x20-\x7e]/.test(cookie.value + agent.value) ||
      (manual.value && !/^[1-9][0-9]{0,29}$/.test(visitor.value)) || ![30, 60, 240, 480, 1440].includes(minutes.value)) {
      error.value = '格式不正确，请检查换行、长度、使用期限及数字访客标识。'; return
    }
    const input = { expectedRevision: expected, replace: true as const, cookie: cookie.value, userAgent: agent.value,
      userUniqueIdOverride: manual.value ? visitor.value : undefined, localLifetimeSeconds: minutes.value * 60 }
    await run('save', signal => api.replace(input, signal), value => {
      clean(); apply(value, true); result.value = null; notice.value = 'Cookie 配置已保存，输入已清空。尚未验证平台登录或礼物接收；下次启动生效。'
    })
  }
  async function clear() {
    if (!guard() || !confirmClear.value) return
    await run('clear', signal => api.clear(status.value!.revision, signal), value => {
      clean(); apply(value, true); result.value = null; notice.value = 'Cookie 已清除并切换为游客，不会在本进程回退到环境中的旧 Cookie。'
    })
  }
  function wait(signal: AbortSignal) {
    return new Promise<void>((resolve, reject) => {
      const abort = () => { clearTimeout(timer); signal.removeEventListener('abort', abort); reject(new AccessError('REQUEST_TIMEOUT')) }
      const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, pollMs)
      signal.addEventListener('abort', abort, { once: true }); if (signal.aborted) abort()
    })
  }
  async function cancelKnown() {
    const id = checkId; checkId = undefined
    if (id) { try { await api.cancel(id) } catch { /* bounded server check still expires without the browser */ } }
  }
  async function check() {
    if (!guard()) return
    if (dirty.value) { error.value = '请先保存或清空未提交的身份输入。'; return }
    const room = context().roomId
    if (context().roomPending || !room || !/^[0-9]{1,30}$/.test(room)) { error.value = '请先填写并保存房间号。'; return }
    const expected = status.value!.revision
    result.value = null
    await run('check', async (signal, token) => {
      let view = await api.start(expected, room, signal)
      // If navigation interrupted a successful start, release this exact check, never a later one.
      if (signal.aborted || token !== epoch) { await api.cancel(view.checkId); throw new AccessError('REQUEST_TIMEOUT') }
      checkId = view.checkId
      const id = view.checkId
      try {
        while (true) {
          if (view.configRevision !== expected || view.checkId !== id) throw new AccessError('INVALID_RESPONSE')
          if (token === epoch && !signal.aborted) result.value = view
          if (view.resourcesReleased) break
          await wait(signal); view = await api.check(id, signal)
        }
        checkId = undefined
        const latest = await api.read(signal)
        return { view, latest }
      } finally { if (checkId === id) await cancelKnown() }
    }, ({ view, latest }) => {
      apply(latest); result.value = view
      if (latest.revision !== expected) { result.value = null; notice.value = '身份配置已变化，请重新核对；旧检查结果已隐藏。' }
      else if (['FAILED', 'TIMED_OUT'].includes(view.state) || view.reason === 'ROOM_ENDED') error.value = accessMessage(new AccessError(view.reason))
    })
  }
  function invalidate() {
    ++epoch; pending?.abort(); pending = undefined; busy.value = ''; result.value = null; confirmClear.value = false
    status.value = null; return cancelKnown()
  }
  async function cancel() { await invalidate(); notice.value = '已请求取消，正在核对资源释放状态。'; await refresh() }
  async function contextChanged() { await invalidate(); await refresh() }
  function leave() { void invalidate(); clean() }
  function dispose() { disposed = true; leave() }
  return { status, result, mode, cookie, agent, visitor, manual, minutes, busy, error, notice, confirmClear,
    dirty, locked, canEdit, refresh, save, clear, check, cancel, contextChanged, leave, dispose }
}
