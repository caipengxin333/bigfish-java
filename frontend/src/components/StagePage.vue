<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { mode, pageUrl } from '../mode'
import { BrowserAudio } from '../stage/audio'
import { StageController } from '../stage/controller'
import type { StageView } from '../stage/controller'
import { WebSocketTransport } from '../stage/websocket'
import { MockStageTransport, mockAudio } from '../mock/stage'
import type { Scenario } from '../mock/stage'
import { stageAvatarKey } from '../stage/avatar'
import PlaceholderAvatar from './PlaceholderAvatar.vue'
import BackendRuntimePanel from './BackendRuntimePanel.vue'
import { createSessionControls, sourceLabel } from '../session-controls.ts'
import { createLaunchOptions } from '../launch-options.ts'
import { createInteraction } from '../stage/interaction.ts'
import { bindStageLifecycle } from '../stage/lifecycle.ts'
const Avatar = inject(stageAvatarKey, PlaceholderAvatar)
const view = shallowRef<StageView | null>(null)
const controller = new StageController(new BrowserAudio(mode === 'mock' ? mockAudio : undefined), window.location.origin, value => { view.value = value })
view.value = { ...controller.view }
let mock: MockStageTransport | null = null
let timer: ReturnType<typeof setInterval> | null = null
let unbindLifecycle: (() => void) | undefined
const enabling = ref(false), heartbeat = ref(true)
const preferences = createLaunchOptions()
const { options, error: preferenceError } = preferences
const controls = createSessionControls()
const { runtime } = controls
watch([options, controls.stopped], () => { if (controls.stopped.value && !controls.busy.value) controls.selection.value = { ...options.value } })
const interaction = createInteraction(controller, controls, () => preferences.options.value, () => new WebSocketTransport())
const { busy: interacting, phase, target, notice: operationNotice, error: operationError } = interaction
const actual = computed(() => runtime.value && ['RUNNING', 'STARTING', 'STOPPING'].includes(runtime.value.state) ? runtime.value : options.value)
const selectionMismatch = computed(() => !!runtime.value && ['RUNNING', 'STARTING', 'STOPPING'].includes(runtime.value.state) &&
  (runtime.value.mode !== options.value.mode || runtime.value.conversationMode !== options.value.conversationMode || runtime.value.speechMode !== options.value.speechMode))
const active = computed(() => !!runtime.value && ['RUNNING', 'STARTING', 'STOPPING'].includes(runtime.value.state))
const recoverable = computed(() => !!target.value && runtime.value?.sessionId === target.value && runtime.value.state === 'RUNNING' && !connected.value)
const startBlocked = computed(() => mode === 'real' && !active.value && !!runtime.value ? controls.startProblem.value : '')
const disconnected = computed(() => mode === 'real' && runtime.value?.state === 'RUNNING' && !connected.value && view.value?.connection !== 'connecting')
const connected = computed(() => view.value?.connection === 'leased')
const states = { idle: '尚未连接', connecting: '连接与握手中', leased: '已获得播放租约', closed: '已断开 · 静音' }
function start() {
  heartbeat.value = true
  if (mode === 'real') { void interaction.start(); return }
  if (view.value?.connection === 'connecting' || connected.value) return
  controller.disconnect()
  void controller.unlock() // Browser demo also unlocks directly from the click.
  mock = new MockStageTransport()
  controller.connect(mock)
}
function stop() { if (mode === 'real') void interaction.stop(); else controller.disconnect() }
async function enable() { enabling.value = true; try { await controller.enable() } finally { enabling.value = false } }
function scenario(value: Scenario) { mock?.scenario(value) }
function toggleHeartbeat() { heartbeat.value = !heartbeat.value; if (mock) mock.heartbeatEnabled = heartbeat.value }
function visibility() { if (!document.hidden) { if (mode === 'real') void interaction.resumePage(); else controller.checkActivity() } }
function freeze() { interaction.freeze() }
function leave() { interaction.freeze(); controller.disconnect('USER_DISCONNECTED') }
function storage(event: StorageEvent) { preferences.onStorage(event) }
onMounted(() => {
  preferences.reload()
  if (mode === 'real') void controls.poll()
  timer = setInterval(() => controller.tick(), 100)
  unbindLifecycle = bindStageLifecycle(document, window, { resume: visibility, freeze, leave })
  window.addEventListener('storage', storage)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  interaction.dispose(); controls.dispose(); controller.dispose()
  unbindLifecycle?.(); window.removeEventListener('storage', storage)
})
</script>
<template>
  <main v-if="view" class="stage-page">
    <div class="heading"><div><p class="eyebrow">LIVE STAGE / 02</p><h1>声音到达，舞台才开口。</h1><p class="muted">开始 AI 消息处理，不会替你在抖音开播</p></div><a class="button secondary" :href="pageUrl('stage', mode === 'real' ? 'mock' : 'real')">{{ mode === 'real' ? '选择 mock 演练' : '选择真实 WebSocket' }}</a></div>
    <section class="panel" aria-label="互动控制">
      <p v-if="mode === 'real'" class="message">{{ active ? '后端正在运行' : '本浏览器启动选项' }}：{{ sourceLabel(actual.mode) }} · {{ actual.conversationMode }} 回复 · {{ actual.speechMode === 'ALIYUN_NLS' ? '阿里云 NLS' : '本地测试音' }}。已保存房间：{{ controls.saved.value ? (controls.saved.value.settings.roomId || '未填写') : '尚未读取' }}。</p>
      <p v-else class="message warning">浏览器本地演示：只播放原创测试音，不访问后端、抖音或云服务。</p>
      <p v-if="mode === 'real' && (actual.conversationMode === 'DEEPSEEK' || actual.speechMode === 'ALIYUN_NLS')" class="message warning">当前选项包含付费云服务；消息到达后可能产生调用费用。</p>
      <p v-if="mode === 'real' && selectionMismatch" class="message warning">已有会话与本浏览器启动选项不同，本页保持后端实际选项，不会重配或重启它。</p>
      <p v-if="preferenceError" class="message error" role="alert">{{ preferenceError }}</p>
      <p v-if="mode === 'real'" class="muted small">启动选项在管理页设置；房间、人设和音色使用后端已保存配置，未保存输入不会生效。</p>
      <div class="toolbar">
        <button :disabled="interacting || connected || view.connection === 'connecting' || !!startBlocked" @click="start">{{ phase === 'starting' ? '正在启动…' : view.connection === 'connecting' ? '正在连接播放端…' : '开始互动' }}</button>
        <button class="secondary" :disabled="phase === 'stopping' || (!interacting && !active && !connected && view.connection !== 'connecting')" @click="stop">{{ phase === 'stopping' ? '正在确认停止…' : '停止互动' }}</button>
        <button v-if="recoverable" class="secondary" :disabled="interacting || view.connection === 'connecting'" @click="interaction.start(true)">恢复播放</button>
        <button v-if="connected && !view.ready" :disabled="enabling || interacting" @click="enable">{{ enabling ? '启用中…' : '点击启用声音' }}</button>
      </div>
      <p v-if="startBlocked" class="message warning" role="alert">{{ startBlocked }}</p>
      <p v-if="operationError" class="message error" role="alert">{{ operationError }}</p>
      <p v-if="operationNotice" class="muted" role="status">{{ phase === 'attached' ? (connected ? (view.ready ? '互动会话与本页播放端已就绪。' : '互动会话已确认，等待声音授权。') : view.connection === 'connecting' ? '互动会话已确认，正在连接本页播放端。' : '本页播放端未连接，请查看下方状态。') : operationNotice }}</p>
      <p v-if="disconnected" class="message warning">后端会话仍在运行，本页播放端已断开或尚未接入；不会自动重启会话。</p>
      <p class="message" role="status">本页播放端：{{ view.notice }}</p>
    </section>
    <section class="stage-canvas" aria-label="播放舞台">
      <div class="stage-status"><span class="live-dot" :class="{ active: view.playing }"></span>{{ view.playing ? '正在播放' : states[view.connection] }}<span class="stage-mode">{{ mode === 'real' ? '后端播放连接' : '浏览器演示' }}</span></div>
      <component :is="Avatar" :mouth-level="view.level" :speaking="view.playing" :connected="connected" />
      <div class="subtitle" data-testid="subtitle" aria-live="polite">{{ view.subtitle || (view.ready ? '等待下一段声音' : '点击开始互动，字幕将在实际播放时出现') }}</div>
      <div class="volume"><span>音量</span><meter min="0" max="1" :value="view.level" aria-label="实时音量"></meter><span>{{ Math.round(view.level * 100) }}%</span></div>
    </section>
    <details class="panel"><summary>高级调试：本页播放连接</summary>
      <div class="toolbar"><button :disabled="connected || interacting || view.connection === 'connecting'" @click="mode === 'real' && target ? interaction.start(true) : start()">手工连接 / 恢复</button><button :disabled="!connected || view.ready || enabling || interacting" @click="enable">启用声音</button><button class="secondary" :disabled="interacting" @click="controller.disconnect()">仅断开本页播放</button></div>
      <dl class="metadata"><div><dt>声音状态</dt><dd>{{ view.playing ? '实际播放中' : view.ready ? '待播' : '静音' }}</dd></div><div><dt>当前会话</dt><dd>{{ view.sessionId || '尚未分配' }}</dd></div><div><dt>当前任务</dt><dd>{{ view.taskId || '无' }}</dd></div><div><dt>租约到期</dt><dd>{{ view.leaseExpiresAt ? new Date(view.leaseExpiresAt).toLocaleTimeString('zh-CN') : '无有效租约' }}</dd></div></dl>
      <p class="muted small">普通标签切换不主动断开；浏览器冻结、系统休眠或租约到期无法保证连续播放。返回后会核对租约与会话，失效时保持静音，需手动恢复。另一页面占用租约时不会抢占。</p>
    </details>
    <BackendRuntimePanel v-if="mode === 'real'" compact :controls="controls" />
    <details v-if="mode === 'mock'" class="panel"><summary>高级调试：本地播放协议演练</summary><p class="muted">原创建模占位与内存生成的 4 秒测试音；没有真实采集、模型生成或 TTS。每个页面使用独立 playerId，Web Locks 保证本地演示只有一个播放端。</p>
      <div class="toolbar"><button :disabled="!view.ready" @click="scenario('normal')">播放测试音</button><button class="secondary" :disabled="!view.ready" @click="scenario('duplicate')">重发上一任务</button><button class="secondary" :disabled="!view.ready" @click="scenario('expired')">发送过期任务</button><button class="secondary" :disabled="!view.ready" @click="scenario('old-session')">发送旧会话任务</button></div>
      <details><summary>故障注入</summary><div class="toolbar"><button class="secondary" :disabled="!view.ready" @click="scenario('download')">下载失败</button><button class="secondary" :disabled="!view.ready" @click="scenario('decode')">解码失败</button><button class="secondary" :disabled="!view.ready" @click="scenario('timeout')">下载超时</button><button class="secondary" :disabled="!connected" @click="scenario('cancel')">取消当前任务</button><button class="secondary" :disabled="!connected" @click="scenario('stop')">停止会话</button><button class="secondary" :disabled="!connected" @click="scenario('disconnect')">模拟断线</button><button class="secondary" :disabled="!connected" @click="toggleHeartbeat">{{ heartbeat ? '停止续租（15 秒后静音）' : '恢复续租' }}</button></div></details>
    </details>
    <details class="panel"><summary>最近播放诊断（最多 40 条）</summary><p v-if="!view.history.length" class="muted">暂无记录。</p><ol v-else class="diagnostics"><li v-for="(item, index) in view.history" :key="index"><time>{{ new Date(item.at).toLocaleTimeString('zh-CN') }}</time><strong>{{ item.code }}</strong><span>{{ item.taskId ? `任务 ${item.taskId}` : '播放端' }}</span></li></ol></details>
    <p class="muted small">当前角色为原创 SVG 占位，已预留可替换渲染组件；未使用旧 Live2D 模型或第三方素材。</p>
  </main>
</template>
