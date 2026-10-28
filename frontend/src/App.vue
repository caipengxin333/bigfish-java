<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from './api'
import type { Bootstrap, ConfigSnapshot } from './contracts'
const isStage = window.location.pathname.startsWith('/stage')
const bootstrap = ref<Bootstrap | null>(null)
const config = ref<ConfigSnapshot | null>(null)
const notice = ref('')
const error = ref('')
const busy = ref(false)
const priorities = computed(() => config.value ? Object.entries(config.value.settings.priorities) : [])
const labels: Record<string, string> = { gift: '礼物感谢', comment: '评论回复', follow: '关注', entrance: '入场' }
async function load() {
  busy.value = true; error.value = ''; notice.value = ''
  try { const [status, settings] = await Promise.all([api.bootstrap(), api.config()]); bootstrap.value = status; config.value = settings }
  catch (e) { error.value = e instanceof Error ? e.message : '无法连接后端' }
  finally { busy.value = false }
}
async function save() {
  if (!config.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { config.value = await api.save(config.value.version, config.value.settings); notice.value = `已保存配置 v${config.value.version}。直播引擎尚未接入，当前仅保存设置。` }
  catch (e) { error.value = e instanceof Error ? e.message : '保存失败' }
  finally { busy.value = false }
}
onMounted(load)
</script>

<template>
  <div class="shell">
    <header><a class="brand" href="/admin"><span class="mark">◒</span> BIGFISH <small>直播工作台</small></a><nav><a href="/admin">管理配置</a><a href="/stage">播放舞台 ↗</a></nav></header>
    <main v-if="!isStage">
      <div class="heading"><div><p class="eyebrow">WORKSPACE / 01</p><h1>让每一次互动，有序发生。</h1><p class="muted">Java 版基础框架 · 单直播间 · 本地运行</p></div><span class="badge">{{ bootstrap ? '服务已连接' : '等待连接' }}</span></div>
      <div v-if="error" class="message error" role="alert">{{ error }} <button :disabled="busy" @click="load">重新加载</button></div>
      <div v-if="notice" class="message" role="status">{{ notice }}</div>
      <section class="status-grid"><article><span>运行状态</span><strong>尚未启动</strong><p>采集、模型与语音将在后续任务接入</p></article><article><span>配置存储</span><strong>{{ config ? `SQLite · v${config.version}` : '等待后端' }}</strong><p>保存时检查版本，防止多页面覆盖配置</p></article><article><span>播放端</span><strong>待接入</strong><p>单一播放端，字幕与嘴型跟随声音</p></article></section>
      <form v-if="config" @submit.prevent="save">
        <div class="form-title"><div><h2>基础配置</h2><p class="muted">先确定人设与播报规则，密钥不在此页面配置。</p></div><button type="submit" :disabled="busy">{{ busy ? '处理中…' : '保存配置' }}</button></div>
        <fieldset :disabled="busy"><div class="fields"><label>配置名称<input v-model="config.settings.profileName" required maxlength="80"></label><label>直播间号<input v-model="config.settings.roomId" inputmode="numeric" pattern="[0-9]{0,30}" placeholder="暂未填写"></label><label>模型名称<input v-model="config.settings.model" required></label><label>最大输出 tokens<input v-model.number="config.settings.maxOutputTokens" type="number" min="32" max="2048" required></label><label>音色标识<input v-model="config.settings.voice" required></label><label>欢迎间隔（秒）<input v-model.number="config.settings.welcomeIntervalSeconds" type="number" min="5" max="600" required></label></div>
        <label class="persona">主播人设<textarea v-model="config.settings.persona" rows="3" maxlength="8000"></textarea></label>
        <div class="priority-row"><div v-for="[key, value] in priorities" :key="key"><span>{{ labels[key] }}</span><strong>{{ value }}</strong></div></div>
        <p class="muted small">数字越大越优先；合成与播放队列各 {{ config.settings.synthesisQueueCapacity }} / {{ config.settings.playbackQueueCapacity }} 条上限。规则引擎待实现。</p></fieldset>
      </form>
    </main>
    <main v-else class="stage"><p class="eyebrow">STAGE / PREVIEW</p><div class="stage-orb">◒</div><h1>舞台已预留</h1><p>Live2D、声音播放和字幕同步将在前端任务中接入。</p><p class="muted">当前页面不会调用模型、合成语音或连接抖音。</p><a href="/admin">← 返回管理页</a></main>
    <footer>BIGFISH JAVA <span>0.1.0 · FRAMEWORK PREVIEW</span></footer>
  </div>
</template>
