<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { runtimeApi } from '../runtime-api'
import type { RuntimeView } from '../contracts'
defineProps<{ compact?: boolean }>()
const runtime = ref<RuntimeView | null>(null), busy = ref(false), error = ref(''), notice = ref('')
let timer: ReturnType<typeof setTimeout> | undefined, disposed = false, revision = 0
const labels = { comments: '评论', templates: '礼物 / 欢迎', synthesis: '合成', playback: '待播放' }
async function refresh() {
  if (busy.value || disposed) return
  const version = ++revision
  try { const value = await runtimeApi.status(); if (!disposed && version === revision) { runtime.value = value; error.value = '' } }
  catch { if (!disposed && version === revision) { runtime.value = null; error.value = '运行状态读取失败，请检查后端。' } }
}
async function poll() { await refresh(); if (!disposed) timer = setTimeout(poll, 1000) }
async function action(kind: 'start' | 'stop' | 'replay' | 'COMMENT' | 'GIFT') {
  if (busy.value) return
  busy.value = true; ++revision; error.value = ''; notice.value = ''
  const session = runtime.value?.sessionId ?? ''
  try {
    if (kind === 'COMMENT' || kind === 'GIFT') {
      const result = await runtimeApi.simulate(session, kind)
      notice.value = `事件结果：${result.result}（ACCEPTED 表示已接收，不等于已播出）`
    } else {
      const value = await (kind === 'start' ? runtimeApi.start() : kind === 'stop' ? runtimeApi.stop(session) : runtimeApi.replay(session))
      if (!disposed) runtime.value = value
      notice.value = kind === 'start' ? '模拟会话已启动。请在舞台连接播放端、启用声音，然后发送测试事件。'
        : kind === 'stop' ? '会话已停止，音频和采集任务已撤销。' : '已启动模拟采集回放：评论 + 礼物累计 1 → 3 → 3，去重后感谢总数应为 3。'
    }
  } catch (e) { error.value = e instanceof Error ? e.message : '操作失败，请刷新状态。' }
  finally { busy.value = false }
}
onMounted(() => void poll())
onBeforeUnmount(() => { disposed = true; ++revision; if (timer) clearTimeout(timer) })
</script>
<template>
  <section class="panel" aria-label="后端会话控制">
    <h2>{{ compact ? '联调操作' : '直播会话与运行状态' }}</h2>
    <p class="message warning">MOCK 采集 · {{ runtime?.conversationMode ?? '未读取' }} 回复 · 300ms 测试音（不是人声）。真实抖音和人声 TTS 尚未接入。</p>
    <p v-if="error" class="message error" role="alert">{{ error }}</p><p v-if="notice" class="message" role="status">{{ notice }}</p>
    <div class="toolbar">
      <button :disabled="busy || !runtime || !['STOPPED', 'FAILED'].includes(runtime.state)" @click="action('start')">启动模拟会话</button>
      <button class="secondary" :disabled="busy || !runtime || runtime.state !== 'RUNNING'" @click="action('stop')">停止会话</button>
      <button class="secondary" :disabled="busy" @click="refresh">刷新状态</button>
      <span>{{ runtime?.state ?? '状态未知' }} · 播放端{{ runtime?.player.ready ? '已就绪' : '未就绪' }}</span>
    </div>
    <div class="toolbar">
      <button class="secondary" :disabled="busy || runtime?.state !== 'RUNNING' || !runtime?.player.ready" @click="action('COMMENT')">发送模拟评论</button>
      <button class="secondary" :disabled="busy || runtime?.state !== 'RUNNING' || !runtime?.player.ready" @click="action('GIFT')">发送模拟礼物</button>
      <button class="secondary" :disabled="busy || runtime?.state !== 'RUNNING' || !runtime?.player.ready" @click="action('replay')">采集器连击回放</button>
    </div>
    <template v-if="runtime && !compact">
      <p class="muted">会话：{{ runtime.sessionId || '尚未启动' }} · 房间：{{ runtime.roomId || '无' }}</p>
      <p>保存 v{{ runtime.savedConfigVersion }} / 最近应用 v{{ runtime.appliedConfigVersion }}（任务各自保留配置快照）</p>
      <div class="queue-grid"><div v-for="(label, key) in labels" :key="key">{{ label }}队列<strong>{{ runtime.queues[key].depth }} / {{ runtime.queues[key].capacity }}</strong></div></div>
      <p>模型{{ runtime.queues.modelActive ? '处理中' : '空闲' }} · 合成{{ runtime.queues.speechActive ? '处理中' : '空闲' }} · {{ runtime.player.playing ? '等待播放完成' : '无播放中任务' }}</p>
      <p>引擎累计丢弃 {{ runtime.dropped }} / 失败 {{ runtime.failures }}</p>
      <p>模拟采集器 {{ runtime.collector.state }} · 本轮接收 {{ runtime.collector.received }} / 派发 {{ runtime.collector.emitted }} / 去重 {{ runtime.collector.duplicates }} / 入队拒绝 {{ runtime.collector.rejected }}</p>
      <p v-if="runtime.collector.lastError" class="message error">采集错误：{{ runtime.collector.lastError }}</p>
    </template>
    <p class="muted small">请保持舞台在前台。舞台内也提供相同测试按钮，无需切回管理页；进入后台时会静音断开。</p>
  </section>
</template>
