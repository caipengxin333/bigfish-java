<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import type { Bootstrap, Settings } from '../contracts'
import { mode } from '../mode'
const props = defineProps<{ bootstrap: Bootstrap | null; savedVersion: number | null; settings: Settings | null }>()
const activeVersion = ref<number | null>(null), running = ref(false), failures = ref<string[]>([])
const channel = mode === 'mock' && typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('bigfish.mock.controls.v1') : null
function start() { running.value = true; activeVersion.value = props.savedVersion }
function stop() { running.value = false; activeVersion.value = null; channel?.postMessage({ action: 'stop' }) }
function simulate() {
  failures.value.unshift('MOCK / QUEUE_FULL：演示入队被拒；未调用模型或语音服务。')
  failures.value = failures.value.slice(0, 8)
}
onBeforeUnmount(() => channel?.close())
</script>
<template>
  <section class="status-grid" aria-label="运行状态">
    <article><span>会话与采集</span><strong>{{ mode === 'mock' ? (running ? '演示运行中' : '演示未启动') : (bootstrap?.sessionState ?? '未确认') }}</strong><p>{{ mode === 'mock' ? '仅本页模拟状态，未连接抖音' : '采集状态不可用，等待 runtime 正式接口' }}</p></article>
    <article><span>保存 / 生效</span><strong>保存 {{ savedVersion ? `v${savedVersion}` : '未读取' }}</strong><p>{{ mode === 'mock' ? `模拟生效：${activeVersion ? `v${activeVersion}` : '未应用'}（启动演示时捕获）` : '运行生效版本：不可用，后端尚未提供' }}</p></article>
    <article><span>播放端</span><strong>{{ mode === 'mock' ? '独立舞台演练' : '状态不可用' }}</strong><p>舞台获得租约并点击启用声音后才能播出；本页不创建播放器。</p></article>
  </section>
  <section class="panel">
    <div class="form-title"><div><h2>队列与失败原因</h2><p class="muted">{{ mode === 'mock' ? '以下是界面演练数据，不是后端指标。' : '尚无运行数据，不能将未知显示为零或成功。' }}</p></div><div class="toolbar"><button :disabled="mode !== 'mock' || running || !savedVersion" @click="start">{{ mode === 'mock' ? '启动演示' : '启动会话（待接入）' }}</button><button class="secondary" :disabled="mode !== 'mock'" @click="stop">{{ mode === 'mock' ? '停止演示舞台' : '停止会话（待接入）' }}</button></div></div>
    <div class="queue-grid"><div>合成队列<strong>{{ mode === 'mock' ? '0（演示）' : '—' }} / {{ settings?.synthesisQueueCapacity ?? '—' }}</strong></div><div>待播放队列<strong>{{ mode === 'mock' ? '0（演示）' : '—' }} / {{ settings?.playbackQueueCapacity ?? '—' }}</strong></div><div>引擎失败计数<strong>{{ mode === 'mock' ? failures.length : '—' }}</strong></div></div>
    <button v-if="mode === 'mock'" class="secondary" :disabled="!running" @click="simulate">演练队列拒绝</button>
    <ul v-if="failures.length" class="diagnostics"><li v-for="(item, index) in failures" :key="index">{{ item }}</li></ul><p v-else class="muted">{{ mode === 'mock' ? '尚无演练失败记录。' : '真实错误原因等待 runtime 接口；请求错误显示在页面上方。' }}</p>
  </section>
</template>
