<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { runtimeApi } from '../runtime-api'
import type { ConversationMode, ModelCheck, ProvidersView, RuntimeView, SpeechMode } from '../contracts'
defineProps<{ compact?: boolean }>()
const runtime = ref<RuntimeView | null>(null), busy = ref(false), error = ref(''), notice = ref('')
const providers = ref<ProvidersView | null>(null), modelCheck = ref<ModelCheck | null>(null)
const conversationMode = ref<ConversationMode>('MOCK'), speechMode = ref<SpeechMode>('LOCAL_TEST_TONE')
const stopped = computed(() => !!runtime.value && ['STOPPED', 'FAILED'].includes(runtime.value.state))
const selectable = computed(() => (conversationMode.value === 'MOCK' || providers.value?.deepseek.enabled) &&
  (speechMode.value === 'LOCAL_TEST_TONE' || providers.value?.aliyun.enabled))
let timer: ReturnType<typeof setTimeout> | undefined, disposed = false, revision = 0
const labels = { comments: '评论', templates: '礼物 / 欢迎', synthesis: '合成', playback: '待播放' }
async function refresh() {
  if (busy.value || disposed) return
  const version = ++revision
  try {
    const [value, available] = await Promise.all([runtimeApi.status(), runtimeApi.providers()])
    if (!disposed && version === revision) {
      runtime.value = value; providers.value = available
      if (!['STOPPED', 'FAILED'].includes(value.state)) {
        conversationMode.value = value.conversationMode; speechMode.value = value.speechMode
      }
    }
  }
  catch { if (!disposed && version === revision) { runtime.value = null; providers.value = null; error.value = '运行状态读取失败，请检查后端。' } }
}
async function poll() { await refresh(); if (!disposed) timer = setTimeout(poll, 1000) }
async function action(kind: 'start' | 'stop' | 'replay' | 'COMMENT' | 'GIFT' | 'checkModel') {
  if (busy.value) return
  busy.value = true; ++revision; error.value = ''; notice.value = ''
  const session = runtime.value?.sessionId ?? ''
  try {
    if (kind === 'checkModel') {
      modelCheck.value = null
      const result = await runtimeApi.checkModel()
      if (!disposed) modelCheck.value = result
      notice.value = '目录检查完成；是否能生成回复仍需启动会话并发送模拟评论验证。'
    } else if (kind === 'COMMENT' || kind === 'GIFT') {
      const result = await runtimeApi.simulate(session, kind)
      notice.value = `事件结果：${result.result}（ACCEPTED 表示已接收，不等于已播出）`
    } else {
      const value = await (kind === 'start' ? runtimeApi.start(conversationMode.value, speechMode.value) : kind === 'stop' ? runtimeApi.stop(session) : runtimeApi.replay(session))
      if (!disposed) runtime.value = value
      notice.value = kind === 'start' ? '会话已启动，采集来源为模拟事件。请在舞台连接播放端、启用声音，然后发送测试事件。' +
        (value.conversationMode === 'DEEPSEEK' || value.speechMode === 'ALIYUN_NLS' ? '所选云服务会产生真实调用。' : '当前仅使用模拟回复和测试音。')
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
    <p class="message warning">MOCK 采集 · {{ runtime?.conversationMode ?? '未读取' }} 回复 · {{ runtime?.speechMode === 'ALIYUN_NLS' ? '阿里云 NLS 人声' : '300ms 测试音（不是人声）' }}。真实抖音尚缺签名实现，暂不可启动。</p>
    <p v-if="error" class="message error" role="alert">{{ error }}</p><p v-if="notice" class="message" role="status">{{ notice }}</p>
    <p v-if="runtime?.lastError" class="message error" role="alert">最近任务错误：{{ runtime.lastError }}（{{ runtime.lastErrorAt }}）</p>
    <div class="toolbar">
      <label>回复服务 <select v-model="conversationMode" :disabled="busy || !stopped"><option value="MOCK">MOCK 模拟回复</option><option value="DEEPSEEK" :disabled="!providers?.deepseek.enabled">DeepSeek</option></select></label>
      <label>语音服务 <select v-model="speechMode" :disabled="busy || !stopped"><option value="LOCAL_TEST_TONE">本地测试音</option><option value="ALIYUN_NLS" :disabled="!providers?.aliyun.enabled">阿里云 NLS</option></select></label>
    </div>
    <template v-if="providers && !compact">
      <p>DeepSeek：{{ providers.deepseek.state }} / {{ providers.deepseek.reason }} · 阿里云：{{ providers.aliyun.state }} / {{ providers.aliyun.reason }}</p>
      <p class="muted small">UNVERIFIED 表示凭据尚未验证。云服务需在后端设置 BIGFISH_ALLOW_PAID=true；DeepSeek 使用 BIGFISH_DEEPSEEK_API_KEY，阿里云使用 BIGFISH_ALIYUN_NLS_APP_KEY 和 BIGFISH_ALIYUN_NLS_TOKEN，修改后重启后端。凭据不在网页填写。</p>
      <p class="muted small">阿里云音色需先在下方配置中保存：{{ providers.aliyunVoices.join('、') }}。合成文本最多 {{ providers.speechTextLimit }} 字符，音频最多 {{ providers.audioDurationLimitSeconds }} 秒，超过会显示错误。当前 Token 需手动更新，未接自动刷新。</p>
      <button class="secondary" :disabled="busy || !providers.deepseek.enabled" @click="action('checkModel')">检查 DeepSeek 模型目录</button>
      <p v-if="modelCheck" role="status">目录检查：{{ modelCheck.code }} · 鉴权 {{ modelCheck.authentication }} · 已保存模型 {{ modelCheck.selectedModel }} · 生成 {{ modelCheck.generation }}（本按钮不生成回复）。可用模型：{{ modelCheck.availableModels.join('、') || '未返回' }}</p>
    </template>
    <div class="toolbar">
      <button :disabled="busy || !stopped || !selectable" @click="action('start')">启动会话</button>
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
