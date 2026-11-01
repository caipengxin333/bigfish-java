<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import { createSessionControls, collectorSummary, safeCode, sourceLabel } from '../session-controls.ts'
const props = defineProps<{ compact?: boolean; editingConfig?: boolean; roomDraft?: string; configPending?: boolean }>()
const emit = defineEmits<{ roomLock: [locked: boolean] }>()
const controls = createSessionControls(undefined, undefined, () => props.editingConfig
  ? { roomId: props.roomDraft, pending: !!props.configPending } : undefined)
const { runtime, providers, saved, diagnostics, modelCheck, selection, busy, error, loadError, notice,
  stopped, locked, availability, inconsistency, roomProblem, startProblem, canSimulate, canStop, refresh, action } = controls
const labels = { comments: '评论', templates: '礼物 / 欢迎', synthesis: '合成', playback: '待播放' }
const waitingLabels: Record<string, string> = {
  STOPPED: '会话未运行', NO_MESSAGES: '尚未收到消息', NO_PENDING_TASKS: '当前任务已处理完', PLAYER_NOT_READY: '播放端未就绪',
  MODEL_WAIT: '模型处理中或等待模型', SPEECH_WAIT: '语音合成中或等待合成', PLAYBACK_WAIT: '等待播放', PLAYING: '正在播放',
  WORKER_BUSY: '上次任务取消后，工作线程尚未释放',
}
const timeLabel = (value: string | null) => value ? new Date(value).toLocaleTimeString() : '暂无'
const recent = computed(() => diagnostics.value?.recent.slice(-8).reverse() ?? [])
watch(locked, value => emit('roomLock', value), { immediate: true, flush: 'sync' })
onMounted(() => void controls.poll())
onBeforeUnmount(controls.dispose)
</script>
<template>
  <section class="panel" aria-label="后端会话控制">
    <h2>{{ compact ? '会话与消息来源' : '直播会话与运行状态' }}</h2>
    <p class="muted">当前连接真实后端接口（transport=real）；消息来源由下方单独选择。浏览器演示 transport=mock 不连接直播。</p>
    <p class="message">当前消息来源：{{ sourceLabel(runtime?.mode) }} · {{ runtime?.conversationMode ?? '未读取' }} 回复 · {{ runtime ? (runtime.speechMode === 'ALIYUN_NLS' ? '阿里云 NLS 人声' : '300ms 本地测试音') : '语音状态未读取' }}</p>
    <p v-if="loadError" class="message error" role="alert">{{ loadError }}</p>
    <p v-if="inconsistency" class="message warning" role="alert">{{ inconsistency }}</p>
    <p v-if="error" class="message error" role="alert">{{ error }}</p><p v-if="notice" class="message" role="status">{{ notice }}</p>
    <p v-if="runtime?.lastError" class="message error" role="alert">最近任务错误：{{ runtime.lastError }}（{{ runtime.lastErrorAt }}）</p>
    <div class="toolbar">
      <label>消息来源 <select v-model="selection.mode" :disabled="locked" aria-describedby="source-availability"><option value="MOCK">模拟采集</option><option value="DOUYIN" :disabled="!availability.enabled">抖音直播</option></select></label>
      <label>回复服务 <select v-model="selection.conversationMode" :disabled="busy || !stopped"><option value="MOCK">MOCK 模拟回复</option><option value="DEEPSEEK" :disabled="!providers?.deepseek.enabled">DeepSeek</option></select></label>
      <label>语音服务 <select v-model="selection.speechMode" :disabled="busy || !stopped"><option value="LOCAL_TEST_TONE">本地测试音</option><option value="ALIYUN_NLS" :disabled="!providers?.aliyun.enabled">阿里云 NLS</option></select></label>
    </div>
    <p id="source-availability" class="muted" role="status">抖音可用性：{{ availability.reason }}</p>
    <p class="muted">已保存房间：{{ saved?.settings.roomId || '未填写' }}{{ saved ? `（v${saved.version}）` : '' }}。修改房间号后需要保存，停止并重新启动会话后生效；启动不会自动保存表单。</p>
    <p v-if="selection.mode === 'DOUYIN' && roomProblem" class="message warning" role="alert">{{ roomProblem }}</p>
    <p v-if="compact" class="muted small">房间号请在管理页保存；本页仅使用后端已保存值，其他页面的未保存输入不会用于连接。</p>
    <p v-if="selection.conversationMode === 'DEEPSEEK' || selection.speechMode === 'ALIYUN_NLS'" class="message warning">所选回复或语音服务会产生真实云调用；直播事件到达后也可能触发调用。</p>
    <template v-if="providers && !compact">
      <p>DeepSeek：{{ providers.deepseek.state }} / {{ providers.deepseek.reason }} · 阿里云：{{ providers.aliyun.state }} / {{ providers.aliyun.reason }}</p>
      <p class="muted small">UNVERIFIED 表示尚未真实验证。云服务需在后端启用 BIGFISH_ALLOW_PAID。阿里云默认 STATIC 使用 AppKey + NLS Token，显式 RAM 使用 AppKey + AccessKey 自动获取和续期。模式或凭据修改后重启后端，凭据不在网页填写。</p>
      <p class="muted small">阿里云音色需先在下方配置中保存：{{ providers.aliyunVoices.join('、') }}。合成文本最多 {{ providers.speechTextLimit }} 字符，音频最多 {{ providers.audioDurationLimitSeconds }} 秒，超过会显示错误。</p>
      <p>Token 模式：{{ providers.aliyunToken.mode }} · 状态：{{ providers.aliyunToken.status }} · 刷新：{{ providers.aliyunToken.refreshResult }}</p>
      <p class="muted small">到期时间：{{ providers.aliyunToken.expiresAt ? new Date(providers.aliyunToken.expiresAt).toLocaleString() : '未知 / 尚未获取' }} · 剩余：{{ providers.aliyunToken.remainingSeconds === null ? '未知' : `${providers.aliyunToken.remainingSeconds} 秒` }} · 签发尝试：{{ providers.aliyunToken.attempts }} · 最近签发成功：{{ timeLabel(providers.aliyunToken.lastSuccessAt) }}</p>
      <p v-if="providers.aliyunToken.lastError" class="message warning">Token：{{ providers.aliyunToken.lastError }} · 重试等待 {{ providers.aliyunToken.retryAfterMillis }}ms。{{ providers.aliyunToken.refreshResult === 'USING_VALID_CACHED' ? '刷新暂时失败，当前使用仍有效的缓存 Token。' : '' }}</p>
      <button class="secondary" :disabled="busy || !providers.deepseek.enabled" @click="action('checkModel')">检查 DeepSeek 模型目录</button>
      <p v-if="modelCheck" role="status">目录检查：{{ modelCheck.code }} · 鉴权 {{ modelCheck.authentication }} · 已保存模型 {{ modelCheck.selectedModel }} · 生成 {{ modelCheck.generation }}（本按钮不生成回复）。可用模型：{{ modelCheck.availableModels.join('、') || '未返回' }}</p>
    </template>
    <div class="toolbar">
      <button :disabled="busy || !!startProblem" @click="action('start')">启动会话</button>
      <button class="secondary" :disabled="!canStop" @click="action('stop')">停止会话</button>
      <button class="secondary" :disabled="busy" @click="refresh">刷新状态</button>
      <span>{{ runtime?.state ?? '状态未知' }} · 播放端{{ runtime?.player.ready ? '已就绪' : '未就绪' }}</span>
    </div>
    <p v-if="stopped && startProblem && !roomProblem" class="muted" role="status">{{ startProblem }}</p>
    <template v-if="runtime">
      <p class="muted session-identity">会话：{{ runtime.sessionId || '尚未启动' }} · 当前运行房间：{{ runtime.roomId || '无' }}</p>
      <p role="status">{{ collectorSummary(runtime) }}</p>
      <p class="muted small">采集接收计数 {{ runtime.collector.received }} / 派发事件 {{ runtime.collector.emitted }} / 去重 {{ runtime.collector.duplicates }} / 入队拒绝 {{ runtime.collector.rejected }}。会话运行或舞台 WebSocket 连接不代表已收到弹幕。</p>
      <p v-if="runtime.collector.lastError" class="message error" role="alert">采集错误：{{ safeCode(runtime.collector.lastError) }}。请核对后端状态；不会自动回退为模拟采集。</p>
    </template>
    <template v-if="diagnostics">
      <p role="status">当前阶段：{{ diagnostics.waiting.map(value => waitingLabels[value] ?? value).join('；') }}</p>
      <p class="muted small">最近模型成功 {{ timeLabel(diagnostics.lastModelSuccessAt) }} · 合成成功 {{ timeLabel(diagnostics.lastSpeechSuccessAt) }} · 播放完成 {{ timeLabel(diagnostics.lastPlaybackSuccessAt) }}</p>
      <p v-if="diagnostics.lastFailure" class="message warning">最近阶段失败：{{ diagnostics.lastFailure.reason }}（{{ timeLabel(diagnostics.lastFailure.at) }}）；历史失败不代表当前仍未恢复。</p>
      <details v-if="!compact">
        <summary>查看最近任务阶段</summary>
        <p class="muted small">仅保留有限记录，当前显示最近 8 条阶段变化。任务记录容量淘汰 {{ diagnostics.taskEvictions }}，阶段记录容量淘汰 {{ diagnostics.historyEvictions }}。</p>
        <p v-for="trace in recent" :key="trace.sequence">{{ timeLabel(trace.at) }} · {{ trace.eventType || '无效事件' }} · {{ trace.stage }} / {{ trace.reason }} · 阶段耗时 {{ trace.previousStageMs }}ms · 任务耗时 {{ trace.taskElapsedMs }}ms</p>
      </details>
    </template>
    <div class="toolbar">
      <button class="secondary" :disabled="!canSimulate" @click="action('COMMENT')">发送模拟评论</button>
      <button class="secondary" :disabled="!canSimulate" @click="action('GIFT')">发送模拟礼物</button>
      <button class="secondary" :disabled="!canSimulate" @click="action('replay')">采集器连击回放</button>
    </div>
    <p v-if="runtime?.mode === 'DOUYIN'" class="muted small">抖音会话禁止发送模拟评论、模拟礼物和连击回放。</p>
    <template v-if="runtime && !compact">
      <p>保存 v{{ runtime.savedConfigVersion }} / 最近应用 v{{ runtime.appliedConfigVersion }}（任务各自保留配置快照）</p>
      <div class="queue-grid"><div v-for="(label, key) in labels" :key="key">{{ label }}队列<strong>{{ runtime.queues[key].depth }} / {{ runtime.queues[key].capacity }}</strong></div></div>
      <p>模型{{ runtime.queues.modelActive ? '处理中' : '空闲' }} · 合成{{ runtime.queues.speechActive ? '处理中' : '空闲' }} · {{ runtime.player.playing ? '等待播放完成' : '无播放中任务' }}</p>
      <p>引擎累计丢弃 {{ runtime.dropped }} / 失败 {{ runtime.failures }}</p>
    </template>
    <p class="muted small">请保持舞台在前台。MOCK 会话可在舞台内发送测试事件，无需切回管理页；进入后台时会静音断开。</p>
  </section>
</template>
