<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createIdentityEditor } from '../identity/editor.ts'
import { createIdentityDemo, identityDemoKey } from '../mock/identity.ts'
import type { CheckScenario } from '../identity/editor.ts'
const props = defineProps<{ demo: boolean; locked: boolean; roomId?: string; roomPending: boolean }>()
const simulatedRunning = ref(false), writeFailure = ref(false), readFailure = ref(false), scenario = ref<CheckScenario>('connected')
const demoService = props.demo ? createIdentityDemo() : null
const locked = computed(() => props.locked || simulatedRunning.value)
const editor = createIdentityEditor(demoService?.port ?? null, () => ({ locked: locked.value, roomId: props.roomId, roomPending: props.roomPending }), props.demo ? 2500 : 8000)
const { status, choice, agent, manual, visitorId, retentionMinutes, cookie, busy, error, notice, checkResult, checkIssue, needsId, clearConfirm, dirty, canEdit } = editor
const advanced = ref(false)
const clock = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined
const expired = computed(() => !!status.value?.expiresAt && status.value.expiresAt <= clock.value)
const timestamp = (value?: number | null) => value ? new Date(value).toLocaleString('zh-CN') : '尚无记录'
watch(choice, () => { cookie.value = ''; checkResult.value = null; clearConfirm.value = false })
watch(dirty, value => { if (value) checkResult.value = null })
watch(needsId, value => { if (value) advanced.value = true })
watch([locked, () => props.roomId, () => props.roomPending], () => { void editor.contextChanged() }, { flush: 'sync' })
watch(writeFailure, value => demoService?.setWriteFailure(value))
watch(readFailure, value => demoService?.setReadFailure(value))
watch(scenario, value => { demoService?.setScenario(value); checkResult.value = null })
function refresh() { if (props.demo && !busy.value) void editor.refresh() }
function storage(event: StorageEvent) { if (event.key === identityDemoKey || event.key === null) { editor.invalidate(); refresh() } }
function leave() { editor.invalidate(); cookie.value = ''; agent.value = ''; visitorId.value = '' }
function restart() { if (locked.value || busy.value) return; demoService?.restart(); refresh() }
onMounted(() => { refresh(); timer = setInterval(() => { clock.value = Date.now() }, 1000); window.addEventListener('focus', refresh); window.addEventListener('pageshow', refresh); window.addEventListener('pagehide', leave); window.addEventListener('storage', storage) })
onBeforeUnmount(() => { clearInterval(timer); editor.dispose(); window.removeEventListener('focus', refresh); window.removeEventListener('pageshow', refresh); window.removeEventListener('pagehide', leave); window.removeEventListener('storage', storage) })
</script>
<template>
  <section class="panel identity-panel" aria-labelledby="identity-title">
    <div class="form-title"><div><p class="eyebrow">DOUYIN ACCESS</p><h2 id="identity-title">抖音接入身份</h2></div><span class="identity-badge">{{ demo ? 'MOCK · 本地界面演练' : '真实接口 · 待接入' }}</span></div>
    <p class="muted">已保存房间：{{ roomId || '未填写' }}。<a href="#configuration">修改并保存房间配置 ↓</a>{{ roomPending ? '房间存在未保存或待核对的变更，不能检查连接。' : '' }}</p>
    <p v-if="!demo" class="message warning" role="status">身份管理公共契约尚未接入，无法确认后端凭据状态。真实保存、清除和检查暂不可用；不会悄悄改用 mock。<a href="/admin?transport=mock#douyin-identity">打开明确的本地演练</a>。</p>
    <p v-else class="message warning">本地 mock，不连接抖音、不保存真实凭据。请只输入假数据；演练结果不代表登录有效或真实接收成功。刷新只恢复本浏览器的脱敏演练状态。</p>
    <p class="muted">正式后端内存保存约定：<strong>刷新页面保留；后端重启后需重新确认配置。</strong>保存后下一次启动生效，无需重启 Java；本页不会启动互动或播放语音。当前真实能力仍待接口落地。</p>
    <p v-if="locked" class="message warning" role="status">互动正在运行或状态尚未确认，请先停止互动并刷新状态，再修改、清除或检查身份。</p>
    <div v-if="error && !checkIssue" class="message error" role="alert">{{ demo ? 'MOCK：' : '' }}{{ error }}</div>
    <p v-if="notice" class="message" role="status">{{ demo ? 'MOCK：' : '' }}{{ notice }}</p>
    <form class="identity-options" autocomplete="off" @submit.prevent="editor.saveOptions">
      <fieldset :disabled="!canEdit"><legend>接入选项（与 Cookie 分开保存）</legend>
        <div class="identity-choices"><label><input v-model="choice" type="radio" value="visitor" name="douyin-access-choice">游客模式</label><label><input v-model="choice" type="radio" value="cookie" name="douyin-access-choice">使用登录 Cookie</label></div>
        <p v-if="choice === 'visitor'" class="muted small">游客模式由采集器获取访客身份。切换模式不会清除已有 Cookie，也不会使用它；需要清除时请明确操作。</p>
        <template v-else>
          <label>User-Agent<input v-model="agent" maxlength="1024" autocomplete="off" spellcheck="false" :placeholder="status?.agentPresent ? '已设置（不回显）；留空保留，输入新值替换' : '从与 Cookie 相同的浏览器请求头获取'"></label>
          <label>本机保存期限<select v-model.number="retentionMinutes"><option :value="30">30 分钟</option><option :value="60">1 小时</option><option :value="240">4 小时</option><option :value="1440">24 小时</option></select></label>
          <p class="muted small">期限用于下次保存/替换 Cookie，不延长已有记录，也不代表平台 Cookie 的有效期；演练期限范围待后端确认。</p>
        </template>
        <details :open="advanced" @toggle="advanced = ($event.target as HTMLDetailsElement).open"><summary>高级设置：user_unique_id 手动覆盖</summary>
          <p class="muted small">默认由采集器解析。仅在解析失败时补填同一浏览器的访客标识；不是房间号，也不代表礼物权限。</p>
          <label class="checkbox"><input v-model="manual" type="checkbox">手动补填 user_unique_id（关闭则恢复自动解析）</label>
          <label v-if="manual">user_unique_id<input v-model="visitorId" inputmode="numeric" pattern="[0-9]{1,30}" maxlength="30" autocomplete="off" :placeholder="status?.manualIdPresent ? '已设置（不回显）；留空保留' : '从 /webcast/im/push/v2/ 查询参数获取'"></label>
        </details>
        <button type="submit" :disabled="!dirty">{{ busy === 'options' ? '保存中…' : '保存接入选项' }}</button>
      </fieldset>
    </form>
    <p v-if="dirty" class="muted" role="status">接入选项尚未保存；保存基础配置不会同时保存这些选项或 Cookie。</p>
    <form v-if="choice === 'cookie'" class="identity-secret" autocomplete="off" @submit.prevent="editor.saveCookie">
      <fieldset :disabled="!canEdit || dirty"><legend>Cookie 敏感凭据</legend>
        <label>Cookie<input v-model="cookie" type="password" autocomplete="off" autocapitalize="off" :spellcheck="false" maxlength="16384" aria-describedby="cookie-handling" :placeholder="demo ? '仅假数据，例如 demo_cookie=test' : '不会回显已有 Cookie'"></label>
        <p id="cookie-handling" class="muted small">仅复制完整 Cookie 值，不带“Cookie:”字段名。空输入不会覆盖已有值；提交成功后立即清空。不会写入浏览器存储、URL、控制台、埋点或配置导出。</p>
        <button type="submit">{{ status?.configured ? '替换 Cookie' : '保存 Cookie' }}</button>
      </fieldset>
    </form>
    <div class="identity-status" aria-live="polite">
      <strong>{{ demo ? 'MOCK 配置状态：' : '真实配置状态：' }}{{ !status ? '无法确认' : expired ? '本机保存期限已到，请重新配置' : status.configured ? '已配置（未证明登录有效）' : '未配置 Cookie' }}</strong>
      <p v-if="status" class="muted small">已保存模式：{{ status.choice === 'visitor' ? '游客模式' : '使用登录 Cookie' }} · 来源：本地 mock 元数据 · 更新时间：{{ timestamp(status.updatedAt) }} · 到期：{{ timestamp(status.expiresAt) }}</p>
      <p v-else class="muted small">来源、更新时间及到期状态尚无法确认，不显示过期成功记录。</p>
      <div class="toolbar"><button type="button" class="secondary" :disabled="!demo || !!busy" @click="refresh">刷新身份状态</button><button type="button" class="secondary" :disabled="!canEdit || !status?.configured" @click="clearConfirm = true">清除 Cookie</button></div>
      <div v-if="clearConfirm" class="message warning"><p>确认清除当前 Cookie？此操作不会切换接入模式，需要使用时须重新提供。</p><div class="toolbar"><button type="button" :disabled="!canEdit" @click="editor.clearCookie(true)">确认清除 Cookie</button><button type="button" class="secondary" @click="clearConfirm = false">取消清除</button></div></div>
    </div>
    <div class="identity-check">
      <div class="toolbar"><button type="button" :disabled="!canEdit || dirty || roomPending || !roomId" @click="editor.check">{{ busy === 'check' ? '检查中…' : demo ? '检查连接（MOCK）' : '检查连接' }}</button><span>仅检查采集连接与接收证据，不启动 AI 回复或语音。</span></div>
      <div v-if="error && checkIssue" class="message error" role="alert">{{ demo ? 'MOCK：' : '' }}{{ error }}</div>
      <p v-if="busy === 'check'" role="status">MOCK：检查中，等待本地演练结果…</p>
      <template v-else-if="checkResult && !expired">
        <p role="status">MOCK：{{ checkResult.kind === 'connected' ? '连接成功但未收到消息' : checkResult.kind === 'comments' ? '已收到评论（演练样本）' : '已收到礼物（演练样本）' }}，不是实际连接测试。</p>
        <p class="muted">评论 {{ checkResult.comments }} 条 · 礼物 {{ checkResult.gifts }} 条。{{ checkResult.gifts === 0 ? '尚未观察到礼物，不能据此判定无礼物权限。' : '仅证明演练状态，真实自然礼物仍待观察。' }}</p>
      </template>
      <p v-else class="muted">{{ status?.choice === 'cookie' && !status.configured ? '未配置有效 Cookie，尚不能检查。' : '尚无当前配置的连接检查记录。' }}尚未观察到礼物。</p>
      <button v-if="needsId" type="button" class="secondary" :disabled="!canEdit" @click="advanced = true; manual = true">补填 user_unique_id</button>
    </div>
    <details class="identity-guide"><summary>如何在 Chrome / Safari 获取 Cookie 与 User-Agent</summary>
      <p>仅在自己的浏览器中操作自己有权使用的账号，打开 live.douyin.com 的直播间。当前 mock 页面请勿粘贴真实凭据。</p>
      <ol><li><strong>Chrome：</strong>打开开发者工具 → Network（网络），保持面板打开并刷新直播间，选择该直播间的 Document（文档）请求 → Headers → Request Headers，复制完整 Cookie 值；User-Agent 从同一浏览器的请求头获取。</li>
        <li><strong>Safari：</strong>启用开发者功能后，在“开发”菜单打开网页检查器 → 网络，刷新直播间。WebSocket 请求可能显示<strong>“没有请求 Cookie”</strong>；此时选择 <strong>live.douyin.com 的直播间页面文稿请求 → 标头 → 请求 → Cookie</strong>，获取完整值；User-Agent 也从同一浏览器请求头获取。</li>
        <li><strong>仅在自动解析失败时：</strong>找到 <code>/webcast/im/push/v2/</code> 的 WebSocket 请求，在查询参数中取 <code>user_unique_id</code> 的值，填入高级设置。不要复制整条带签名 URL；找不到参数时不要猜测。</li></ol>
      <p>不要把 Cookie 发到聊天或截图中，不上传完整 HAR，不运行陌生控制台脚本。Cookie 与 User-Agent 应来自同一浏览器会话。</p>
      <p class="small"><a href="https://developer.chrome.com/docs/devtools/network/reference" target="_blank" rel="noopener noreferrer">Chrome 官方网络面板说明 ↗</a> · <a href="https://webkit.org/web-inspector/network-tab/" target="_blank" rel="noopener noreferrer">WebKit 官方网络面板说明 ↗</a></p>
    </details>
    <details v-if="demo" class="identity-debug"><summary>高级调试：仅本地身份配置演练</summary>
      <label class="checkbox"><input v-model="simulatedRunning" type="checkbox">模拟互动运行中（锁定修改）</label>
      <label class="checkbox"><input v-model="writeFailure" :disabled="!!busy" type="checkbox">模拟保存失败</label><label class="checkbox"><input v-model="readFailure" :disabled="!!busy" type="checkbox">模拟读取失败</label>
      <label>下次检查结果<select v-model="scenario" :disabled="!!busy"><option value="connected">连接成功但未收到消息</option><option value="comments">收到评论，无礼物</option><option value="gifts">收到礼物</option><option value="failed">连接失败</option><option value="timeout">检查超时</option><option value="id-required">user_unique_id 自动解析失败</option></select></label>
      <button type="button" class="secondary" :disabled="locked || !!busy" @click="restart">模拟后端重启（清空演练状态）</button>
      <p class="muted small">这里没有后端凭据：浏览器仅存脱敏 mock 标记用于刷新验收。模拟重启清空这些标记，不操作 Java 或其他窗口。</p>
    </details>
  </section>
</template>
<style>
.identity-panel { overflow-wrap: anywhere; }.identity-panel h2 { margin-top: 0; }.identity-panel a { text-decoration: underline; }
.identity-badge { background: #fff0c8; color: #65521d; border-radius: 20px; padding: 8px 12px; font-size: .8rem; }
.identity-choices { display: flex; gap: 1rem; flex-wrap: wrap; }.identity-choices label { display: flex; align-items: center; gap: .5rem; }.identity-choices input { width: auto; }
.identity-panel fieldset { border: 1px solid #d6e2dd; border-radius: 12px; padding: 16px; margin: 16px 0; min-width: 0; }
.identity-panel legend { font-weight: 600; padding: 0 .4rem; }.identity-panel label:not(.checkbox) { margin: .6rem 0; }.identity-panel input:not([type=radio]):not([type=checkbox]), .identity-panel select { display: block; width: 100%; min-width: 0; margin-top: .5rem; }
.identity-panel details { margin: 1rem 0; }.identity-status, .identity-check { padding: 1rem 0; border-bottom: 1px solid #dce6e4; }.identity-panel .toolbar { flex-wrap: wrap; }.identity-panel code { overflow-wrap: anywhere; }.identity-guide ol { padding-left: 1.4rem; }.identity-guide li { margin: .75rem 0; }
</style>
