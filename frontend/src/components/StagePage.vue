<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
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
const Avatar = inject(stageAvatarKey, PlaceholderAvatar)
const view = shallowRef<StageView | null>(null)
const controller = new StageController(new BrowserAudio(mode === 'mock' ? mockAudio : undefined), window.location.origin, value => { view.value = value })
view.value = { ...controller.view }
let mock: MockStageTransport | null = null
let timer: ReturnType<typeof setInterval> | null = null
const enabling = ref(false), heartbeat = ref(true)
const connected = computed(() => view.value?.connection === 'leased')
const states = { idle: '尚未连接', connecting: '连接与握手中', leased: '已获得播放租约', closed: '已断开 · 静音' }
function connect() {
  heartbeat.value = true
  mock = mode === 'mock' ? new MockStageTransport() : null
  controller.connect(mock ?? new WebSocketTransport())
}
async function enable() { enabling.value = true; try { await controller.enable() } finally { enabling.value = false } }
function scenario(value: Scenario) { mock?.scenario(value) }
function toggleHeartbeat() { heartbeat.value = !heartbeat.value; if (mock) mock.heartbeatEnabled = heartbeat.value }
function hide() { if (document.hidden) controller.disconnect('PAGE_HIDDEN') }
function leave() { controller.disconnect('USER_DISCONNECTED') }
onMounted(() => { timer = setInterval(() => controller.tick(), 100); document.addEventListener('visibilitychange', hide); window.addEventListener('pagehide', leave) })
onBeforeUnmount(() => { if (timer) clearInterval(timer); controller.dispose(); document.removeEventListener('visibilitychange', hide); window.removeEventListener('pagehide', leave) })
</script>
<template>
  <main v-if="view" class="stage-page">
    <div class="heading"><div><p class="eyebrow">LIVE STAGE / 02</p><h1>声音到达，舞台才开口。</h1><p class="muted">独占播放 · 实时字幕 · 音量驱动</p></div><a class="button secondary" :href="pageUrl('stage', mode === 'real' ? 'mock' : 'real')">{{ mode === 'real' ? '选择 mock 演练' : '选择真实 WebSocket' }}</a></div>
    <section class="stage-canvas" aria-label="播放舞台">
      <div class="stage-status"><span class="live-dot" :class="{ active: view.playing }"></span>{{ view.playing ? '正在播放' : states[view.connection] }}<span class="stage-mode">{{ mode === 'real' ? '后端播放连接' : '浏览器演示' }}</span></div>
      <component :is="Avatar" :mouth-level="view.level" :speaking="view.playing" :connected="connected" />
      <div class="subtitle" data-testid="subtitle" aria-live="polite">{{ view.subtitle || (view.ready ? '等待下一段声音' : '点击启用声音后，字幕将在实际播放时出现') }}</div>
      <div class="volume"><span>音量</span><meter min="0" max="1" :value="view.level" aria-label="实时音量"></meter><span>{{ Math.round(view.level * 100) }}%</span></div>
    </section>
    <section class="panel">
      <div class="toolbar"><button :disabled="connected || view.connection === 'connecting'" @click="connect">{{ view.connection === 'connecting' ? '连接中…' : '连接播放端' }}</button><button :disabled="!connected || view.ready || enabling" @click="enable">{{ view.ready ? '声音已启用' : enabling ? '启用中…' : '启用声音' }}</button><button class="secondary" :disabled="!connected && view.connection !== 'connecting'" @click="controller.disconnect()">断开并静音</button></div>
      <p class="message" role="status">{{ view.notice }}</p>
      <dl class="metadata"><div><dt>声音状态</dt><dd>{{ view.playing ? '实际播放中' : view.ready ? '待播' : '静音' }}</dd></div><div><dt>当前会话</dt><dd>{{ view.sessionId || '尚未分配' }}</dd></div><div><dt>当前任务</dt><dd>{{ view.taskId || '无' }}</dd></div><div><dt>租约到期</dt><dd>{{ view.leaseExpiresAt ? new Date(view.leaseExpiresAt).toLocaleTimeString('zh-CN') : '无有效租约' }}</dd></div></dl>
      <p class="muted small">声音只在本页播放。另一页面占用租约时保持静音；离开前台、断线或停止会立即清空音频与嘴型，返回后需重新连接并启用声音。</p>
    </section>
    <BackendRuntimePanel v-if="mode === 'real'" compact />
    <section v-if="mode === 'mock'" class="panel">
      <h2>本地播放协议演练</h2><p class="muted">原创建模占位与内存生成的 4 秒测试音；没有真实采集、模型生成或 TTS。每个页面使用独立 playerId，Web Locks 保证本地演示只有一个播放端。</p>
      <div class="toolbar"><button :disabled="!view.ready" @click="scenario('normal')">播放测试音</button><button class="secondary" :disabled="!view.ready" @click="scenario('duplicate')">重发上一任务</button><button class="secondary" :disabled="!view.ready" @click="scenario('expired')">发送过期任务</button><button class="secondary" :disabled="!view.ready" @click="scenario('old-session')">发送旧会话任务</button></div>
      <details><summary>故障注入</summary><div class="toolbar"><button class="secondary" :disabled="!view.ready" @click="scenario('download')">下载失败</button><button class="secondary" :disabled="!view.ready" @click="scenario('decode')">解码失败</button><button class="secondary" :disabled="!view.ready" @click="scenario('timeout')">下载超时</button><button class="secondary" :disabled="!connected" @click="scenario('cancel')">取消当前任务</button><button class="secondary" :disabled="!connected" @click="scenario('stop')">停止会话</button><button class="secondary" :disabled="!connected" @click="scenario('disconnect')">模拟断线</button><button class="secondary" :disabled="!connected" @click="toggleHeartbeat">{{ heartbeat ? '停止续租（15 秒后静音）' : '恢复续租' }}</button></div></details>
    </section>
    <details class="panel" open><summary>最近播放诊断（最多 40 条）</summary><p v-if="!view.history.length" class="muted">暂无记录。</p><ol v-else class="diagnostics"><li v-for="(item, index) in view.history" :key="index"><time>{{ new Date(item.at).toLocaleTimeString('zh-CN') }}</time><strong>{{ item.code }}</strong><span>{{ item.taskId ? `任务 ${item.taskId}` : '播放端' }}</span></li></ol></details>
    <p class="muted small">当前角色为原创 SVG 占位，已预留可替换渲染组件；未使用旧 Live2D 模型或第三方素材。</p>
  </main>
</template>
