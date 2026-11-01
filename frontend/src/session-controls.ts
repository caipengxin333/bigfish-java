import { computed, ref } from 'vue'
import type { Bootstrap, CollectionMode, ConfigSnapshot, ModelCheck, ProvidersView, RuntimeDiagnosticsView, RuntimeView, SessionSelection } from './contracts.ts'
import { api, RequestError } from './api.ts'
import { runtimeApi } from './runtime-api.ts'

export const sourceLabel = (mode?: CollectionMode) => mode === 'DOUYIN' ? '抖音直播' : mode === 'MOCK' ? '模拟采集' : '尚未读取'
// Collector/provider errors are codes, never upstream bodies or signed URLs.
export const safeCode = (value: string) => /^[A-Z][A-Z0-9_]{0,79}$/.test(value) ? value : 'UNKNOWN_ERROR'
const unavailableReasons: Record<string, string> = {
  LIVE_RECEIVE_NOT_VERIFIED: '后端暂未开放真实接收，等待联调。',
  DOUYIN_UNAVAILABLE: '后端尚未开放抖音采集。',
  MISSING_CREDENTIALS: '后端采集配置不完整。',
  SIGNER_UNAVAILABLE: '后端签名服务不可用。',
}
export function douyinAvailability(bootstrap: Bootstrap | null, providers: ProvidersView | null) {
  if (!bootstrap || !providers) return { enabled: false, reason: '尚未读取后端采集能力。' }
  const provider = providers.douyin, capability = bootstrap.capabilities.douyinCollector
  if (provider.enabled && (capability !== true || !provider.configured || ['DISABLED', 'UNAVAILABLE', 'MISSING'].includes(provider.state))) {
    return { enabled: false, reason: '后端能力字段不一致，暂不能启动抖音；请交主窗口核对。' }
  }
  if (!provider.enabled || capability !== true) return { enabled: false,
    reason: unavailableReasons[provider.reason] ?? `后端未开放抖音采集${provider.reason ? `（${safeCode(provider.reason)}）` : '。'}` }
  return { enabled: true, reason: provider.state === 'UNVERIFIED'
    ? '后端已开放；真实接收尚未验证，启动后以采集状态与事件计数为准。'
    : '后端已开放；连接和接收结果见下方实时状态。' }
}
export function collectorSummary(runtime: RuntimeView) {
  const c = runtime.collector
  const state = { STOPPED: '已停止', CONNECTING: '连接中', CONNECTED: '已连接', RECONNECTING: '重连中', ENDED: '直播已结束', FAILED: '采集失败' }[c.state]
  const reception = c.emitted > 0 ? `当前采集器已派发 ${c.emitted} 条事件（不代表评论、礼物均已验证）`
    : c.received > 0 ? '已收到采集数据，尚无派发事件' : '尚未收到采集事件'
  return `${sourceLabel(c.mode)}：${state} · ${reception}`
}
export type RoomDraft = { roomId: string | undefined; pending: boolean }
export type SessionAction = 'start' | 'stop' | 'replay' | 'COMMENT' | 'GIFT' | 'checkModel'

/** Shared by admin and stage; read-only polling never starts a session or saves configuration. */
export function createSessionControls(client = runtimeApi, configuration = api, draft: () => RoomDraft | undefined = () => undefined) {
  const runtime = ref<RuntimeView | null>(null), providers = ref<ProvidersView | null>(null)
  const bootstrap = ref<Bootstrap | null>(null), saved = ref<ConfigSnapshot | null>(null)
  const diagnostics = ref<RuntimeDiagnosticsView | null>(null), modelCheck = ref<ModelCheck | null>(null)
  const selection = ref<SessionSelection>({ mode: 'MOCK', conversationMode: 'MOCK', speechMode: 'LOCAL_TEST_TONE' })
  const busy = ref(false), error = ref(''), loadError = ref(''), notice = ref('')
  const stopped = computed(() => !!runtime.value && ['STOPPED', 'FAILED'].includes(runtime.value.state))
  const locked = computed(() => busy.value || !stopped.value)
  const availability = computed(() => douyinAvailability(bootstrap.value, providers.value))
  const inconsistency = computed(() => runtime.value && (runtime.value.mode !== runtime.value.collector.mode ||
    (runtime.value.mode === 'DOUYIN' && !stopped.value && !availability.value.enabled))
    ? '后端运行来源与采集能力/模式不一致；以下为原始状态，请交主窗口核对。' : '')
  const roomProblem = computed(() => {
    if (selection.value.mode !== 'DOUYIN') return ''
    if (!saved.value) return '房间配置尚未读取，请刷新后再启动。'
    const local = draft()
    if (local && (local.pending || local.roomId === undefined || local.roomId !== saved.value.settings.roomId))
      return '房间号未保存或配置仍需核对，请先保存并确认，再启动抖音。'
    if (!saved.value.settings.roomId.trim()) return '请先在管理页填写并保存直播间号。'
    return ''
  })
  const startProblem = computed(() => {
    if (!stopped.value) return '请先停止当前会话；状态未知时请刷新。'
    if (selection.value.mode === 'DOUYIN' && !availability.value.enabled) return availability.value.reason
    if (roomProblem.value) return roomProblem.value
    if (selection.value.conversationMode === 'DEEPSEEK' && !providers.value?.deepseek.enabled) return 'DeepSeek 当前不可用。'
    if (selection.value.speechMode === 'ALIYUN_NLS' && !providers.value?.aliyun.enabled) return '阿里云 NLS 当前不可用。'
    return ''
  })
  const canSimulate = computed(() => !busy.value && runtime.value?.state === 'RUNNING' &&
    runtime.value.mode === 'MOCK' && runtime.value.collector.mode === 'MOCK' && runtime.value.player.ready)
  const canStop = computed(() => !busy.value && !!runtime.value?.sessionId && runtime.value.state !== 'STOPPED')
  let uncertainStart = false
  let revision = 0, disposed = false, pending: AbortController | undefined, timer: ReturnType<typeof setTimeout> | undefined
  const failure = (e: unknown) => e instanceof RequestError ? e.message : '操作失败，请刷新状态后核对。'
  function begin(parent?: AbortSignal) {
    pending?.abort()
    const controller = new AbortController()
    pending = controller
    const cancel = () => controller.abort()
    parent?.addEventListener('abort', cancel, { once: true })
    if (parent?.aborted) cancel()
    return { version: ++revision, signal: controller.signal, detach: () => parent?.removeEventListener('abort', cancel) }
  }
  const current = (version: number) => !disposed && version === revision
  function accept(value: RuntimeView) {
    runtime.value = value
    diagnostics.value = null
    if (!['STOPPED', 'FAILED'].includes(value.state)) selection.value = {
      mode: value.mode, conversationMode: value.conversationMode, speechMode: value.speechMode,
    }
  }
  async function read(version: number, signal: AbortSignal) {
    const [value, available, trace, boot, config] = await Promise.all([
      client.status(signal), client.providers(signal), client.diagnostics(signal), configuration.bootstrap(signal), configuration.config(signal),
    ])
    if (!current(version)) return false
    accept(value); providers.value = available; bootstrap.value = boot; saved.value = config; loadError.value = ''
    diagnostics.value = trace.sessionId === value.sessionId && trace.state === value.state ? trace : null
    return true
  }
  async function refresh() {
    if (busy.value || disposed) return
    const { version, signal } = begin()
    try { await read(version, signal) }
    catch (e) { if (current(version)) {
      pending?.abort(); runtime.value = null; providers.value = null; diagnostics.value = null; bootstrap.value = null; saved.value = null
      loadError.value = `运行状态读取失败：${failure(e)}`
    } }
  }
  async function action(kind: SessionAction) {
    if (busy.value || disposed) return
    if (kind === 'start' && startProblem.value) { error.value = startProblem.value; return }
    if (['COMMENT', 'GIFT', 'replay'].includes(kind) && !canSimulate.value) { error.value = '模拟事件仅用于已就绪的 MOCK 会话。'; return }
    if (kind === 'stop' && !canStop.value) return
    const { version, signal } = begin()
    const session = runtime.value?.sessionId ?? ''
    const chosen = { ...selection.value }
    busy.value = true; error.value = ''; notice.value = ''; diagnostics.value = null
    try {
      if (kind === 'start') {
        // Re-read the saved room/capabilities immediately before the mutation, never PUT the form.
        if (!await read(version, signal)) return
        if (startProblem.value) { error.value = startProblem.value; return }
        const value = await client.start(chosen, signal)
        if (!current(version)) return
        accept(value)
        notice.value = value.mode !== chosen.mode ? '后端返回的消息来源与选择不一致，请停止并交主窗口核对。'
          : `会话请求已受理，消息来源：${sourceLabel(value.mode)}。连接和事件接收请查看采集状态；播放需在舞台连接并启用声音。`
      } else if (kind === 'checkModel') {
        modelCheck.value = null
        const result = await client.checkModel(signal)
        if (!current(version)) return
        modelCheck.value = result; notice.value = '目录检查完成；此操作不生成回复。'
      } else if (kind === 'COMMENT' || kind === 'GIFT') {
        const result = await client.simulate(session, kind, signal)
        if (current(version)) notice.value = `事件结果：${result.result}（ACCEPTED 表示已接收，不等于已播出）`
      } else {
        const value = await (kind === 'stop' ? client.stop(session, signal) : client.replay(session, signal))
        if (!current(version)) return
        // Another page may already have replaced the stopped session; do not adopt a mismatched action response.
        if (value.sessionId !== session) { runtime.value = null; error.value = '会话已变化，请刷新状态。'; return }
        accept(value)
        notice.value = kind === 'stop' ? '停止请求已返回，请以会话和采集状态为准。' : '已启动模拟采集回放：礼物累计 1 → 3 → 3，去重后总数应为 3。'
      }
    } catch (e) { if (current(version)) { pending?.abort(); error.value = failure(e); runtime.value = null; diagnostics.value = null } }
    finally { if (current(version)) busy.value = false }
  }
  // Used by the stage coordinator. It holds the same polling revision/busy gate
  // as ordinary actions, so a late status response cannot replace the chosen session.
  async function prepareSession(chosen: SessionSelection, parent?: AbortSignal, target?: string): Promise<RuntimeView> {
    if (disposed || busy.value) throw new RequestError('http', '操作正在进行，请稍候。')
    const { version, signal, detach } = begin(parent)
    const ensure = () => { if (!current(version) || signal.aborted) throw new RequestError('network', '本次操作已取消。') }
    busy.value = true; error.value = ''; notice.value = ''
    try {
      await read(version, signal); ensure()
      let value = runtime.value!
      if (target) {
        if (value.sessionId !== target || value.state !== 'RUNNING') throw new RequestError('http', '原互动会话已停止或变化；恢复播放不会新建会话。', 'SESSION_MISMATCH')
        return value
      }
      if (value.state === 'RUNNING' || value.state === 'STARTING') { uncertainStart = false; return value }
      if (value.state === 'STOPPING') throw new RequestError('http', '后端仍在停止，请等待确认。')
      if (uncertainStart) throw new RequestError('timeout', '上次启动结果仍未确认，本次只查询状态，未重复启动。请稍后核对后端。')
      selection.value = { ...chosen }
      if (startProblem.value) throw new RequestError('http', startProblem.value)
      uncertainStart = true
      try {
        value = await client.start(chosen, signal); ensure(); uncertainStart = false; accept(value)
      } catch (e) {
        ensure()
        const ambiguous = e instanceof RequestError && (['timeout', 'network'].includes(e.kind) || e.code === 'SESSION_ACTIVE')
        if (!ambiguous) { uncertainStart = false; throw e }
        // Exactly one read after an ambiguous POST; never retry the mutation.
        value = await client.status(signal); ensure(); accept(value)
        if (!['RUNNING', 'STARTING'].includes(value.state)) throw new RequestError('timeout', '启动结果未确认，已查询状态但未发现活动会话；不会重复发送启动请求。')
        uncertainStart = false
      }
      return value
    } finally { detach(); if (current(version)) busy.value = false }
  }
  async function stopSession(target?: string, parent?: AbortSignal): Promise<RuntimeView> {
    const { version, signal, detach } = begin(parent)
    const ensure = () => { if (!current(version) || signal.aborted) throw new RequestError('network', '本次操作已取消。') }
    busy.value = true; diagnostics.value = null
    try {
      let value = await client.status(signal); ensure(); accept(value)
      if (target && value.sessionId !== target) throw new RequestError('http', '后端会话已变化，本次未停止其他会话。', 'SESSION_MISMATCH')
      if (value.state === 'STOPPED' && uncertainStart) throw new RequestError('timeout', '先前的启动请求仍未确认；本页已静音，但暂不能确认后端最终停止。')
      if (value.sessionId && value.state !== 'STOPPED') {
        const session = value.sessionId
        value = await client.stop(session, signal); ensure()
        if (value.sessionId !== session) throw new RequestError('http', '停止响应会话不一致，请核对后端。', 'SESSION_MISMATCH')
        accept(value)
        if (value.state === 'STOPPED') uncertainStart = false
      }
      return value
    } finally { detach(); if (current(version)) busy.value = false }
  }
  async function verifySession(target: string): Promise<boolean> {
    await refresh()
    return runtime.value?.sessionId === target && runtime.value.state === 'RUNNING'
  }
  async function poll() { await refresh(); if (!disposed) timer = setTimeout(poll, 1000) }
  function dispose() { disposed = true; ++revision; pending?.abort(); if (timer) clearTimeout(timer) }
  return { runtime, providers, bootstrap, saved, diagnostics, modelCheck, selection, busy, error, loadError, notice,
    stopped, locked, availability, inconsistency, roomProblem, startProblem, canSimulate, canStop, refresh, action, prepareSession, stopSession, verifySession, poll, dispose }
}

export type SessionControls = ReturnType<typeof createSessionControls>
