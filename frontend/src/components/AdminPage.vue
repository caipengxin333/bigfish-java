<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, copySettings, RequestError } from '../api'
import type { Bootstrap, ConfigSnapshot, Settings } from '../contracts'
import { mode, pageUrl } from '../mode'
import { mockConfig } from '../mock/config'
import RuntimePanel from './RuntimePanel.vue'
import BackendRuntimePanel from './BackendRuntimePanel.vue'
const service = mode === 'mock' ? mockConfig : api
const bootstrap = ref<Bootstrap | null>(null)
const saved = ref<ConfigSnapshot | null>(null)
const draft = ref<Settings | null>(null)
const latest = ref<ConfigSnapshot | null>(null)
const busy = ref(false), error = ref(''), notice = ref(''), needsReview = ref(false), fault = ref('none')
const roomLocked = ref(true)
const roomDirty = computed(() => !!draft.value && draft.value.roomId !== saved.value?.settings.roomId)
const dirty = computed(() => !!draft.value && JSON.stringify(draft.value) !== JSON.stringify(saved.value?.settings))
const priorities = ['gift', 'comment', 'follow', 'entrance'] as const
const labels = { gift: '礼物感谢', comment: '评论回复', follow: '关注', entrance: '入场' }
const failure = (e: unknown) => e instanceof RequestError ? e.message : '读取本地数据失败，请检查浏览器存储权限。'
async function load() {
  busy.value = true; error.value = ''; notice.value = ''
  const results = await Promise.allSettled([service.bootstrap(), service.config()])
  const status = results[0], config = results[1]
  if (status.status === 'fulfilled') bootstrap.value = status.value
  else { bootstrap.value = null; error.value = `状态加载失败：${failure(status.reason)}` }
  if (config.status === 'fulfilled') {
    if (draft.value && (dirty.value || needsReview.value)) { latest.value = config.value; notice.value = '已读取最新保存版本。选择下方操作后再保存；当前草稿尚未覆盖。' }
    else { saved.value = config.value; draft.value = copySettings(config.value.settings); latest.value = null }
  } else error.value += ` 配置加载失败：${failure(config.reason)}`
  busy.value = false
}
function reconcile(keepDraft: boolean) {
  if (!latest.value) return
  saved.value = latest.value
  if (!keepDraft) draft.value = copySettings(latest.value.settings)
  latest.value = null; needsReview.value = false; error.value = ''
  notice.value = keepDraft ? '已以最新版本为基准保留整份草稿，请核对所有字段后保存。' : '已采用最新保存配置。'
}
async function save() {
  if (!draft.value || !saved.value || needsReview.value || latest.value) return
  if (mode === 'real' && roomDirty.value && roomLocked.value) { error.value = '请先停止会话，再保存房间变更。'; return }
  busy.value = true; error.value = ''; notice.value = ''
  try {
    if (mode === 'mock' && fault.value !== 'none') throw new RequestError(fault.value === 'conflict' ? 'conflict' : 'timeout', fault.value === 'conflict' ? '模拟 409：保存冲突，草稿已保留。请读取最新版本。' : '模拟超时：保存结果未确认，草稿已保留。请读取最新版本。')
    saved.value = await service.save(saved.value.version, draft.value)
    draft.value = copySettings(saved.value.settings)
    notice.value = `已保存 v${saved.value.version}${mode === 'mock' ? '（浏览器本地存储）' : '（后端存储）'}。保存不代表运行引擎已应用。`
  } catch (e) { error.value = failure(e); needsReview.value = e instanceof RequestError && ['conflict', 'timeout', 'network'].includes(e.kind) }
  finally { busy.value = false }
}
function openStage(event: MouseEvent) {
  if (busy.value || roomDirty.value || needsReview.value || latest.value) { event.preventDefault(); error.value = '请先保存或核对房间配置，再打开舞台。' }
}
function beforeLeave(event: BeforeUnloadEvent) { if (dirty.value) event.preventDefault() }
onMounted(() => { void load(); window.addEventListener('beforeunload', beforeLeave) })
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeLeave))
</script>
<template>
  <main>
    <div class="heading"><div><p class="eyebrow">CONTROL ROOM / 01</p><h1>让每一次互动，有序发生。</h1><p class="muted">保存配置 → 打开舞台 → 开始互动</p></div><a class="button secondary" :href="pageUrl('stage')" @click="openStage" target="_blank" rel="noopener">打开舞台 ↗</a></div>
    <div class="toolbar"><a class="button secondary" :href="pageUrl('admin', mode === 'real' ? 'mock' : 'real')">{{ mode === 'real' ? '切换到本地 mock' : '切换到真实接口' }}</a><button class="secondary" :disabled="busy" @click="load">{{ busy ? '处理中…' : '读取最新版本' }}</button><span class="muted">{{ bootstrap ? (mode === 'mock' ? '本地演示可用' : `后端响应：${bootstrap.mode}`) : '运行信息未确认' }}</span></div>
    <div v-if="error" class="message error" role="alert">{{ error }}</div><div v-if="notice" class="message" role="status">{{ notice }}</div>
    <div v-if="latest" class="message warning"><p>本地基准 v{{ saved?.version }} → 最新保存 v{{ latest.version }}。保留草稿将以整份草稿覆盖最新配置，请先核对。</p><details><summary>查看最新保存内容</summary><pre>{{ JSON.stringify(copySettings(latest.settings), null, 2) }}</pre></details><div class="toolbar"><button :disabled="busy" @click="reconcile(true)">以最新版本保留草稿</button><button class="secondary" :disabled="busy" @click="reconcile(false)">采用最新配置，放弃草稿</button></div></div>
    <BackendRuntimePanel v-if="mode === 'real'" editing-config :room-draft="draft?.roomId" :config-pending="busy || needsReview || !!latest" @room-lock="roomLocked = $event" />
    <details v-else class="panel"><summary>高级调试：本地管理演示</summary><RuntimePanel :bootstrap="bootstrap" :saved-version="saved?.version ?? null" :settings="saved?.settings ?? null" /></details>
    <form id="configuration" v-if="draft && saved" @submit.prevent="save">
      <div class="form-title"><div><h2>基础配置</h2><p class="muted">已保存 v{{ saved.version }} · {{ dirty ? '有未保存修改' : '草稿与保存版本一致' }}</p><p class="muted">保存时间：{{ new Date(saved.updatedAt).toLocaleString('zh-CN') }}</p></div><button type="submit" :disabled="busy || needsReview || !!latest || !dirty || (mode === 'real' && roomDirty && roomLocked)">{{ busy ? '处理中…' : '保存配置' }}</button></div>
      <p v-if="needsReview" class="message warning">保存结果需要核对。请点击“读取最新版本”，草稿会保留。</p>
      <p id="room-help" class="muted">修改房间号后需要保存，停止并重新启动会话后生效。保存或刷新页面不会自动连接抖音。{{ mode === 'real' && roomLocked ? '会话运行或状态未知时，房间输入暂时锁定。' : '' }}</p>
      <fieldset :disabled="busy"><div class="fields">
        <label>配置名称<input v-model="draft.profileName" required maxlength="80"></label><label>直播间号<input v-model="draft.roomId" :disabled="mode === 'real' && roomLocked" aria-describedby="room-help" inputmode="numeric" pattern="[0-9]{0,30}" maxlength="30" placeholder="暂未填写"></label>
        <label>模型名称<input v-model="draft.model" required maxlength="100"></label><label>最大输出 tokens<input v-model.number="draft.maxOutputTokens" type="number" min="32" max="2048" step="1" required></label>
        <label>音色标识<input v-model="draft.voice" required maxlength="100"></label><label>欢迎间隔（秒）<input v-model.number="draft.welcomeIntervalSeconds" type="number" min="5" max="600" step="1" required></label>
        <label>合成队列容量<input v-model.number="draft.synthesisQueueCapacity" type="number" min="1" max="100" step="1" required></label><label>播放队列容量<input v-model.number="draft.playbackQueueCapacity" type="number" min="1" max="100" step="1" required></label>
        <label class="checkbox"><input v-model="draft.readComment" type="checkbox">播报评论原文</label>
      </div><label class="persona">主播人设<textarea v-model="draft.persona" rows="4" maxlength="8000"></textarea></label>
      <div class="priority-row"><label v-for="key in priorities" :key="key">{{ labels[key] }}优先级<input v-model.number="draft.priorities[key]" type="number" min="0" max="100" step="1" required></label></div>
      <p class="muted small">数字越大越优先。不要在任何配置或人设中填入 API Key、Cookie 或其他凭据。</p></fieldset>
    </form>
    <section v-else class="empty-state" aria-live="polite">{{ busy ? '正在读取配置…' : '配置尚未加载，请检查服务后读取最新版本。' }}</section>
    <details v-if="mode === 'mock'" class="panel"><summary>Mock 保存故障演练</summary><label>下次保存<select v-model="fault"><option value="none">正常保存</option><option value="conflict">模拟 409 冲突</option><option value="timeout">模拟超时</option></select></label><p class="muted">只用于验证提示与草稿保留，不是实际网络故障。</p></details>
  </main>
</template>
