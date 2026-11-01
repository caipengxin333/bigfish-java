import { computed, ref } from 'vue'
import type { SessionSelection } from '../contracts.ts'
import type { createSessionControls } from '../session-controls.ts'
import { RequestError } from '../api.ts'
import type { OperationIssue } from '../session-health.ts'
import type { StageController, Transport } from './controller.ts'

type Sessions = Pick<ReturnType<typeof createSessionControls>, 'prepareSession' | 'stopSession' | 'verifySession'>
export function createInteraction(stage: StageController, sessions: Sessions, options: () => SessionSelection, transport: () => Transport) {
  const phase = ref<'idle' | 'starting' | 'connecting' | 'attached' | 'stopping'>('idle')
  const target = ref(''), notice = ref(''), error = ref('')
  const issue = ref<OperationIssue | null>(null)
  const operationIssue = (action: OperationIssue['action'], e: unknown): OperationIssue => ({ action, sessionId: target.value,
    kind: e instanceof RequestError ? e.kind : 'unknown', code: e instanceof RequestError ? e.code : '' })
  const busy = computed(() => ['starting', 'connecting', 'stopping'].includes(phase.value))
  let revision = 0, operation: AbortController | undefined, disposed = false
  const current = (version: number) => !disposed && version === revision
  const failure = (e: unknown) => e instanceof RequestError ? e.message : '操作未确认，请核对后端状态。'
  async function start(recover = false) {
    if (busy.value || disposed || stage.view.connection === 'connecting' || stage.view.ready) return
    if (recover && !target.value) { error.value = '没有可恢复的已确认会话，请先开始互动。'; return }
    const expected = recover ? target.value : undefined
    if (!recover) target.value = ''
    const chosen = { ...options() }, version = ++revision
    operation?.abort(); operation = new AbortController()
    stage.disconnect(); phase.value = 'starting'; error.value = ''; issue.value = null; notice.value = '正在核对后端互动会话…'
    // Invoke synchronously on the click stack, before any session request.
    const sound = stage.unlock()
    try {
      const runtime = await sessions.prepareSession(chosen, operation.signal, expected)
      if (!current(version)) return
      if (!runtime.sessionId) throw new RequestError('invalid', '后端未返回有效会话标识，播放端未连接。')
      target.value = runtime.sessionId
      if (runtime.state !== 'RUNNING') { phase.value = 'idle'; notice.value = '后端互动会话正在启动，请稍后恢复播放。'; return }
      notice.value = ['mode', 'conversationMode', 'speechMode'].some(key => runtime[key as keyof SessionSelection] !== chosen[key as keyof SessionSelection])
        ? '已接入后端正在运行的实际选项；本浏览器偏好未覆盖它。' : '互动会话已确认，正在连接本页播放端。'
      phase.value = 'connecting'
      stage.connect(transport(), runtime.sessionId)
      await sound
      if (!current(version)) return
      // Lease arrival is asynchronous; stage READY remains gated by both facts.
      phase.value = 'attached'
    } catch (e) {
      if (current(version)) { stage.disconnect(); phase.value = 'idle'; error.value = failure(e); issue.value = operationIssue(recover ? 'recover' : 'start', e) }
    }
  }
  async function stop() {
    if (disposed || phase.value === 'stopping') return
    const version = ++revision, expected = target.value || undefined
    operation?.abort(); operation = new AbortController()
    stage.disconnect(); phase.value = 'stopping'; error.value = ''; issue.value = null; notice.value = '本页已静音，正在确认后端停止结果…'
    try {
      const runtime = await sessions.stopSession(expected, operation.signal)
      if (!current(version)) return
      notice.value = runtime.state === 'STOPPED' ? '后端已确认互动停止，本页保持静音。' : `本页已静音，后端状态为 ${runtime.state}，停止尚未确认。`
      if (runtime.state === 'STOPPED') target.value = ''
    } catch (e) { if (current(version)) { error.value = `本页已静音；后端停止未确认：${failure(e)}`; issue.value = operationIssue('stop', e) } }
    finally { if (current(version)) phase.value = 'idle' }
  }
  async function resumePage() {
    stage.checkActivity()
    if (disposed || busy.value || !target.value || stage.view.connection !== 'leased') return
    const version = revision, expected = target.value
    if (!await sessions.verifySession(expected) && current(version)) {
      issue.value = operationIssue('recover', new RequestError('http', '', 'SESSION_MISMATCH'))
      stage.disconnect('SESSION_CHANGED'); error.value = '恢复页面后无法确认原会话，已静音；请核对状态后恢复播放。'
    }
  }
  function freeze() { ++revision; operation?.abort(); stage.disconnect('PAGE_FROZEN'); phase.value = 'idle' }
  function dispose() { disposed = true; ++revision; operation?.abort(); stage.disconnect() }
  return { phase, target, notice, error, issue, busy, start, stop, resumePage, freeze, dispose }
}
