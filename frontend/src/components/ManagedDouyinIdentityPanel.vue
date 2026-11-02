<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createIdentityApi } from '../identity/api.ts'
import { createManagedIdentity } from '../identity/managed.ts'
const props = defineProps<{ locked: boolean; roomId?: string; roomPending: boolean }>()
const editor = createManagedIdentity(createIdentityApi(), () => props)
const { status, result, mode, cookie, agent, visitor, manual, minutes, busy, error, notice, confirmClear, dirty, locked, canEdit } = editor
const clock = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined
const expired = computed(() => !!status.value?.localExpiresAt && Date.parse(status.value.localExpiresAt) <= clock.value)
const time = (value?: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '未设置'
const sources = { DEFAULT: '默认游客', ENVIRONMENT: '启动环境变量', RUNTIME: '本次后端运行期间的页面配置' }
const checkTitle = computed(() => {
  const v = result.value
  if (!v) return '尚无当前配置的连接检查记录'
  if (v.state === 'STOPPING') return '检查正在退出，等待资源释放'
  if (v.state === 'FAILED') return '连接检查失败'
  if (v.state === 'TIMED_OUT') return '连接检查超时'
  if (v.state === 'CANCELLED') return '检查已取消'
  if (v.reason === 'ROOM_ENDED') return '直播间已结束'
  if (v.gifts > 0) return '已收到礼物消息'
  if (v.comments > 0) return '已收到评论消息'
  return v.connected ? '连接成功，尚未收到评论或礼物' : '正在建立采集连接'
})
function refresh() { if (!busy.value) void editor.refresh() }
watch([() => props.locked, () => props.roomId, () => props.roomPending], () => { void editor.contextChanged() })
watch(mode, () => { cookie.value = ''; result.value = null; confirmClear.value = false })
watch(dirty, value => { if (value) result.value = null })
onMounted(() => { refresh(); timer = setInterval(() => { clock.value = Date.now() }, 1000); window.addEventListener('focus', refresh); window.addEventListener('pageshow', refresh); window.addEventListener('pagehide', editor.leave) })
onBeforeUnmount(() => { clearInterval(timer); editor.dispose(); window.removeEventListener('focus', refresh); window.removeEventListener('pageshow', refresh); window.removeEventListener('pagehide', editor.leave) })
</script>
<template>
  <section class="panel identity-panel" aria-labelledby="managed-identity-title">
    <div class="form-title"><div><p class="eyebrow">DOUYIN ACCESS</p><h2 id="managed-identity-title">抖音接入身份</h2></div><span class="identity-badge">REAL · 后端内存配置</span></div>
    <p class="muted">已保存房间：{{ roomId || '未填写' }}。<a href="#configuration">修改并保存房间配置 ↓</a></p>
    <p class="muted"><strong>刷新页面保留；后端重启后需重新确认配置。</strong>保存后下次启动互动生效，无需重启 Java。Cookie 不进入普通配置导出。</p>
    <p v-if="locked && status" class="message warning">互动或连接检查正在使用凭据，请先停止或等待资源释放，再刷新身份状态。</p>
    <div v-if="error" class="message error" role="alert">{{ error }}</div>
    <p v-if="notice" class="message" role="status">{{ notice }}</p>
    <div class="identity-status" aria-live="polite">
      <strong>{{ !status ? '后端身份状态尚未确认' : expired ? '本机使用期限已到' : status.mode === 'GUEST' ? '当前为游客模式' : status.cookieConfigured ? 'Cookie 已配置，未证明平台登录有效' : '凭据尚未配置完整' }}</strong>
      <p v-if="status" class="muted small">来源：{{ sources[status.source] }} · 配置版本：{{ status.version }} · 更新：{{ time(status.updatedAt) }} · 截止：{{ time(status.localExpiresAt) }} · 访客标识：{{ status.identityMode === 'AUTO' ? '自动解析' : '手工补填' }}</p>
      <p class="muted small">后端重启会清空页面配置；若启动环境仍配置了 Cookie，重启后将重新使用它，请核对来源。</p>
      <button type="button" class="secondary" :disabled="!!busy" @click="refresh">刷新身份状态</button>
    </div>
    <form autocomplete="off" @submit.prevent="editor.save">
      <fieldset :disabled="!canEdit"><legend>下次互动使用的身份</legend>
        <div class="identity-choices"><label><input v-model="mode" type="radio" value="GUEST" name="managed-access">游客模式</label><label><input v-model="mode" type="radio" value="EXTERNAL" name="managed-access">使用登录 Cookie</label></div>
        <p v-if="mode === 'GUEST'" class="muted small">保存游客模式会清除本进程的 Cookie 配置，并屏蔽环境中的旧 Cookie；再次使用 Cookie 时需重新填写。</p>
        <template v-else>
          <label>Cookie<input v-model="cookie" type="password" autocomplete="off" autocapitalize="off" :spellcheck="false" maxlength="16384" placeholder="粘贴完整 Cookie 值，不带 Cookie: 字段名"></label>
          <label>User-Agent<input v-model="agent" autocomplete="off" :spellcheck="false" maxlength="2048" placeholder="从与 Cookie 相同的浏览器请求头获取"></label>
          <label>本机允许使用期限<select v-model.number="minutes"><option :value="30">30 分钟</option><option :value="60">1 小时</option><option :value="240">4 小时</option><option :value="480">8 小时</option><option :value="1440">24 小时</option></select></label>
          <p class="muted small">这是本机使用期限，不是抖音登录有效期，不会自动续期。到期会停止使用该凭据的互动或检查。替换时请同时提供 Cookie 和 UA；空输入不会清除旧值。</p>
          <details><summary>高级设置：user_unique_id 手动补填</summary><p class="muted small">默认从同一 Cookie/UA 的直播页面自动解析；只有解析失败时再补填，不使用房间号或主播 ID。</p>
            <label class="checkbox"><input v-model="manual" type="checkbox">手动补填访客标识</label><label v-if="manual">user_unique_id<input v-model="visitor" inputmode="numeric" autocomplete="off" maxlength="30" placeholder="同一浏览器连接的数字访客标识"></label>
          </details>
        </template>
        <button type="submit">{{ busy === 'save' ? '保存中…' : mode === 'GUEST' ? '保存游客模式' : status?.cookieConfigured ? '替换 Cookie 配置' : '保存 Cookie 配置' }}</button>
      </fieldset>
    </form>
    <div class="toolbar"><button type="button" class="secondary" :disabled="!canEdit || !status?.cookieConfigured" @click="confirmClear = true">清除 Cookie</button></div>
    <div v-if="confirmClear" class="message warning"><p>确认清除当前 Cookie 并切换为游客？本进程不会自动回退到旧环境 Cookie。</p><button type="button" :disabled="!canEdit" @click="editor.clear">确认清除</button> <button type="button" class="secondary" @click="confirmClear = false">取消</button></div>
    <div class="identity-check">
      <div class="toolbar"><button type="button" :disabled="!canEdit || dirty || roomPending || !roomId || expired" @click="editor.check">{{ busy === 'check' ? '检查中…' : '检查连接（20 秒）' }}</button><button v-if="busy === 'check'" type="button" class="secondary" @click="editor.cancel">取消检查</button></div>
      <p class="muted small">仅接收直播消息，不启动 AI 或语音。使用已保存的房间与身份，未保存的输入不会用于检查。每次启动检查间隔至少 30 秒。</p>
      <p v-if="dirty || roomPending" class="muted small">存在未保存的身份输入或房间变更，请先保存或清空输入。</p>
      <p role="status">{{ checkTitle }}</p>
      <p v-if="result" class="muted">评论 {{ result.comments }} 条 · 礼物 {{ result.gifts }} 条 · {{ result.resourcesReleased ? '检查资源已释放' : '检查资源仍在使用' }}。{{ result.gifts === 0 ? '尚未观察到礼物，不能据此判定无礼物权限。' : '仅证明采集收到礼物；感谢语音需在舞台互动验证。' }}</p>
    </div>
    <details class="identity-guide"><summary>如何获取 Cookie 和 User-Agent</summary>
      <ol><li>在自己的 Chrome 或 Safari 中登录抖音，打开正在直播的房间，再打开开发者工具的“网络”面板并刷新页面。</li>
        <li>Chrome 选择直播间 Document 请求 → Headers → Request Headers。Safari 选择直播间页面文稿请求 → 标头 → 请求。复制完整 Cookie 值和同一浏览器的 User-Agent。</li>
        <li>Safari 的 WebSocket 可能显示“没有请求 Cookie”；如果文稿由服务工作线程响应也没有显示 Cookie，可选择同域 <code>/webcast/room/web/enter/</code> 请求。不要复制响应中的 Set-Cookie。</li>
        <li>只有自动解析访客标识失败时，才从 <code>/webcast/im/push/v2/</code> 的查询参数中获取 user_unique_id，填入高级设置。</li></ol>
      <p>只粘贴字段值，不发送到聊天、不上传完整 HAR、不执行复制的 cURL 或陌生控制台脚本。保存成功后敏感输入自动清空。</p>
    </details>
  </section>
</template>
