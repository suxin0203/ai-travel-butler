<template>
  <div class="page settings-page">
    <van-nav-bar title="设置" />

    <!-- 账号卡：白卡 + 实色头像 + 配额行（F8 去渐变：单一卡片系统，靠发丝线分层） -->
    <div class="user-card">
      <div class="card-main">
        <div class="avatar">{{ avatarChar }}</div>
        <div class="user-info">
          <div class="user-name">{{ me.username || '…' }}</div>
          <div class="user-sub">{{ me.email || '未绑定邮箱' }}</div>
        </div>
      </div>
      <div class="quota-row">
        <span class="quota-label">今日 AI 配额</span>
        <span class="quota-num">{{ me.quota ? `${me.quota.used}/${me.quota.limit}` : '…' }}</span>
      </div>
    </div>

    <van-cell-group inset title="绑定管理">
      <!-- 邮箱绑定：状态行 + 弹层全套流程（输入 → 发码倒计时 → 验证码 → 保存）。
           图标统一容器化（浅蓝圆角底，F15：裸图标居中排布与文字间隔关系松散） -->
      <van-cell title="邮箱绑定" center is-link @click="openEmailPopup"
        :label="me.email || '绑定后离线也能收到邮件提醒'">
        <template #icon><span class="bind-ico"><van-icon name="envelop-o" /></span></template>
        <template #value>
          <span class="bind-entry">{{ me.email ? '修改' : '去绑定' }}</span>
        </template>
      </van-cell>
      <!-- 微信提醒：状态行用短文案（防换行挤压右侧按钮） -->
      <van-cell title="微信提醒" center
        :label="wechatCellLabel" is-link @click="!me.wechatBound && genBindCode()">
        <template #icon><span class="bind-ico"><van-icon name="wechat" /></span></template>
        <template #value>
          <van-button v-if="!me.wechatBound" size="small" type="primary" plain round
            @click.stop="genBindCode">{{ bindCode ? '重新生成' : '生成绑定码' }}</van-button>
        </template>
      </van-cell>
      <!-- 绑定码卡片：大字码 + 复制 + 有效期提示（生成后出现） -->
      <div v-if="bindCode" class="bind-code-card">
        <div class="bind-code-row">
          <code class="bind-cmd">{{ bindCode.code }}</code>
          <van-button size="small" type="primary" round @click="copyBindCode">复制验证码</van-button>
        </div>
        <div class="bind-code-hint">10 分钟内有效 · 复制后发给测试号即可完成绑定</div>
      </div>
      <!-- 关注测试号：仅未绑定时展示（已绑定后二维码无用，自动折叠——用户拍板） -->
      <van-cell v-if="!me.wechatBound" title="关注测试号">
        <template #icon><span class="bind-ico"><van-icon name="qr" /></span></template>
        <template #label>
          <span>用微信扫描二维码关注，关注后才能收到推送</span>
          <div class="qr-wrap"><img class="wx-qr" :src="qrUrl" alt="测试号二维码" /></div>
        </template>
      </van-cell>
      <!-- 解绑：仅已绑定时显示；解绑后回到未绑定态，可重新生成绑定码绑定任意微信 -->
      <van-cell v-if="me.wechatBound" title="解绑微信" center clickable
        label="解绑后原微信收不到推送；可重新生成绑定码绑定本微信或其他微信" @click="unbindWechat">
        <template #icon><span class="bind-ico"><van-icon name="link-o" /></span></template>
        <template #value>
          <van-button size="small" type="danger" plain round :loading="unbinding">解绑</van-button>
        </template>
      </van-cell>
      <van-cell title="绑定状态" center>
        <template #icon><span class="bind-ico"><van-icon name="shield-o" /></span></template>
        <template #value>
          <van-tag type="success" v-if="me.wechatBound">已绑定</van-tag>
          <van-tag type="default" v-else>未绑定</van-tag>
        </template>
      </van-cell>
    </van-cell-group>

    <!-- 推送偏好（契约 §2.5b）：控制邮件/微信两个离线渠道，SSE 在线弹窗不受影响；
         开关拨动即时保存（最后写入胜出），未绑定的渠道置灰（没绑定谈不上偏好） -->
    <van-cell-group inset title="推送偏好">
      <van-cell title="邮件推送" :label="me.email ? undefined : '未绑定邮箱，完成绑定后可用'">
        <template #right-icon>
          <van-switch v-model="notifyMail" :disabled="!me.email" size="22" @change="saveNotify" />
        </template>
      </van-cell>
      <van-cell title="微信推送" :label="me.wechatBound ? undefined : '未绑定微信，完成绑定后可用'">
        <template #right-icon>
          <van-switch v-model="notifyWechat" :disabled="!me.wechatBound" size="22" @change="saveNotify" />
        </template>
      </van-cell>
      <div class="pref-hint">
        关闭后该渠道不再发送提醒；网页在线时的实时弹窗不受影响。两个都关闭且不在网页时，到期提醒将标记为失败（回网页仍可查看历史）。
      </div>
    </van-cell-group>

    <!-- 邮箱绑定弹层：全套流程（输入 → 发码倒计时 → 验证码 → 保存），替代裸输入行 -->
    <van-popup v-model:show="showEmailPopup" position="bottom" round style="padding-bottom: 16px">
      <div class="form-head">
        <span class="form-title">{{ me.email ? '修改邮箱' : '绑定邮箱' }}</span>
        <van-icon name="cross" class="form-close" @click="showEmailPopup = false" />
      </div>
      <van-form @submit="saveEmail">
        <van-cell-group inset>
          <van-field v-model="email" label="新邮箱" left-icon="envelop-o" placeholder="输入邮箱地址" clearable
            :rules="[{ validator: (v) => /^\S+@\S+\.\S+$/.test(String(v).trim()), message: '请输入正确的邮箱地址' }]" />
          <van-field v-model="emailCode" label="验证码" left-icon="shield-o" placeholder="6 位数字验证码" clearable
            :rules="[{ validator: (v) => /^\d{6}$/.test(String(v).trim()), message: '请输入 6 位数字验证码' }]">
            <template #button>
              <van-button size="small" type="primary" plain round :disabled="countdown > 0" @click="sendCode">
                {{ countdown > 0 ? countdown + 's 后重发' : '发送验证码' }}</van-button>
            </template>
          </van-field>
        </van-cell-group>
        <div class="popup-hint">验证码 10 分钟内有效。换绑邮箱需验证码校验，防止误绑他人邮箱。</div>
        <div style="padding: 14px 16px 0">
          <van-button type="primary" block round native-type="submit" :loading="saving">保存绑定</van-button>
        </div>
      </van-form>
    </van-popup>

    <div class="logout-wrap">
      <van-button block round type="danger" plain @click="logout">退出登录</van-button>
    </div>
    <div class="app-footer">AI 旅行管家 · 用 AI 安排你的每一次出行</div>
  </div>
</template>

<script setup>
defineOptions({ name: 'SettingsView' })
import { ref, computed, onMounted, onActivated, onUnmounted } from 'vue'
import { showToast, showSuccessToast } from 'vant'
import http, { clearToken, clearRecommendCache } from '../api/http'
import { disconnectNotify } from '../api/sse'
import qrUrl from '../assets/wechat-test-qr.jpg'

const me = ref({})
const email = ref('')
const emailCode = ref('')
const originalEmail = ref('')  // 服务端当前绑定，作为「邮箱是否变更」的归一比较基准
const bindCode = ref(null)
const showEmailPopup = ref(false)
const countdown = ref(0)
const saving = ref(false)
const unbinding = ref(false)
const notifyMail = ref(true)
const notifyWechat = ref(true)
let countdownTimer = null
let bindPollTimer = null
let bindPollStopper = null

const avatarChar = computed(() => (me.value.username || '?').charAt(0).toUpperCase())

// 邮箱归一后与当前绑定不同 → 需要验证码（契约 §2.5）
const needsCode = computed(() => {
  const cur = email.value.trim().toLowerCase()
  return cur !== '' && cur !== (originalEmail.value || '').trim().toLowerCase()
})

// 微信提醒行状态文案（短句防换行挤压按钮）
const wechatCellLabel = computed(() => {
  if (me.value.wechatBound) return '已绑定 · 推送开关见下方'
  if (bindCode.value) return '10 分钟内有效 · 复制后发给测试号'
  return '未绑定 · 生成验证码后发给测试号'
})

async function load() {
  const data = await http.get('/api/auth/me')
  me.value = data.data
  email.value = data.data.email || ''
  originalEmail.value = data.data.email || ''
  notifyMail.value = data.data.notify ? data.data.notify.mail !== false : true
  notifyWechat.value = data.data.notify ? data.data.notify.wechat !== false : true
}

/** 推送偏好即时保存（契约 §2.5b）：失败时回滚到服务端值（错误文案由 http 拦截器 toast） */
async function saveNotify() {
  try {
    const data = await http.put('/api/auth/notify-channels', {
      mail: notifyMail.value, wechat: notifyWechat.value
    })
    me.value.notify = data.data
    showSuccessToast('推送偏好已保存')
  } catch {
    notifyMail.value = me.value.notify ? me.value.notify.mail !== false : true
    notifyWechat.value = me.value.notify ? me.value.notify.wechat !== false : true
  }
}

/** 解绑微信：确认后清 openid（契约 §4.5），回到未绑定态 */
async function unbindWechat() {
  try {
    await showConfirmDialog({ title: '解绑微信', message: '确定解绑吗？解绑后原微信收不到推送，可随时重新绑定。' })
  } catch { return }
  unbinding.value = true
  try {
    await http.post('/api/wechat/unbind')
    bindCode.value = null   // 清掉在途绑定码展示与轮询（码已无意义）
    stopBindPolling()
    await load()
    showSuccessToast('已解绑微信')
  } finally {
    unbinding.value = false
  }
}

/** 复制验证码：clipboard API 失败时降级 execCommand（http 环境下 clipboard 不可用） */
async function copyBindCode() {
  if (!bindCode.value) return
  const code = bindCode.value.code
  try {
    await navigator.clipboard.writeText(code)
    showSuccessToast('验证码已复制，去微信粘贴发送')
  } catch {
    const input = document.createElement('textarea')
    input.value = code
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    document.body.removeChild(input)
    showSuccessToast('验证码已复制，去微信粘贴发送')
  }
}

function stopCountdown() {
  if (countdownTimer) { clearInterval(countdownTimer); countdownTimer = null }
  countdown.value = 0
}

/** 发送验证码（契约 §2.5a）：400/429 文案由 http 拦截器透传 toast，这里不再重复报错 */
async function sendCode() {
  const mail = email.value.trim()
  if (!/^\S+@\S+\.\S+$/.test(mail)) { showToast('请输入正确的邮箱地址'); return }
  try {
    const data = await http.post('/api/auth/email/code', { email: mail })
    showSuccessToast(`验证码已发送，${Math.round((data.data?.expireSeconds || 600) / 60)} 分钟内有效`)
    countdown.value = 60
    countdownTimer = setInterval(() => {
      countdown.value--
      if (countdown.value <= 0) stopCountdown()
    }, 1000)
  } catch { /* 拦截器已提示 */ }
}

/** 打开邮箱弹层：预填当前邮箱（未绑定为空），验证码清空 */
function openEmailPopup() {
  email.value = originalEmail.value
  emailCode.value = ''
  showEmailPopup.value = true
}

/** 弹层保存（契约 §2.5）：未变更幂等提示；变更需验证码；成功关弹层并刷新状态 */
async function saveEmail() {
  if (!needsCode.value) {
    showEmailPopup.value = false
    showToast('邮箱未变更')
    return
  }
  saving.value = true
  try {
    const data = await http.put('/api/auth/me', {
      email: email.value.trim(), emailCode: emailCode.value.trim()
    })
    me.value.email = data.data.email
    originalEmail.value = data.data.email
    email.value = data.data.email
    emailCode.value = ''
    stopCountdown()
    showEmailPopup.value = false
    showSuccessToast('邮箱绑定成功')
  } catch {
    // 400（错码/占用）/网络异常已由 http 拦截器 toast，这里静默避免 unhandled rejection
  } finally {
    saving.value = false
  }
}

async function genBindCode() {
  const data = await http.post('/api/wechat/bindcode')
  bindCode.value = data.data
  startBindPolling()
}

// 绑定状态自动刷新（绑定回复可能因隧道延迟不显示，以服务端状态为准）：
// 生成绑定码后每 4 秒拉一次 me，wechatBound 变 true 即提示成功并解锁开关；
// 10.5 分钟（绑定码有效期 + 余量）后自动停止
function startBindPolling() {
  stopBindPolling()
  bindPollTimer = setInterval(async () => {
    if (!bindCode.value) { stopBindPolling(); return }
    try {
      const data = await http.get('/api/auth/me')
      if (data.data.wechatBound) {
        me.value = data.data
        originalEmail.value = data.data.email || ''
        notifyMail.value = data.data.notify ? data.data.notify.mail !== false : true
        notifyWechat.value = data.data.notify ? data.data.notify.wechat !== false : true
        bindCode.value = null
        stopBindPolling()
        showSuccessToast('微信绑定成功，提醒将推送到测试号')
      }
    } catch { /* 轮询失败忽略，下个周期再试 */ }
  }, 4000)
  bindPollStopper = setTimeout(stopBindPolling, 10.5 * 60 * 1000)
}

function stopBindPolling() {
  if (bindPollTimer) { clearInterval(bindPollTimer); bindPollTimer = null }
  if (bindPollStopper) { clearTimeout(bindPollStopper); bindPollStopper = null }
}

function logout() {
  http.post('/api/auth/logout').finally(() => {
    clearToken()
    disconnectNotify()
    // 行程页的最近推荐结果也是用户态数据（TravelView localStorage 持久化），换账号一并清掉
    clearRecommendCache()
    // 整页跳转：清掉 keep-alive 缓存的视图状态，防止换账号看到上一用户的数据
    location.assign('/login')
  })
}

onMounted(load)
onActivated(load)
onUnmounted(() => { stopCountdown(); stopBindPolling() })
</script>

<style scoped>
/* 账号卡：白卡分层（发丝线分隔配额行），无彩色底无光斑 */
.user-card {
  background: #fff; border-radius: var(--tt-radius);
  box-shadow: var(--tt-shadow); padding: 4px 16px;
}
.card-main { display: flex; align-items: center; gap: 12px; padding: 14px 0; }
.avatar {
  width: 44px; height: 44px; border-radius: 50%; flex: none;
  background: var(--van-primary-color); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; font-weight: 700;
}
.user-name { font-size: 16px; font-weight: 700; color: var(--tt-text); }
.user-sub { font-size: 12px; color: var(--tt-text-sub); margin-top: 2px; }
.quota-row {
  display: flex; justify-content: space-between; align-items: center;
  border-top: 1px solid #f0f2f7; padding: 10px 0;
  font-size: 12px; color: var(--tt-text-sub);
}
.quota-num { font-size: 14px; font-weight: 700; color: var(--van-primary-color); }

/* 绑定验证码：等宽字体大号浅底胶囊（纯数字码，一眼可读手输也可复制） */
.bind-cmd {
  display: inline-block; margin-top: 6px; padding: 6px 10px;
  background: #f0f5ff; color: var(--van-primary-color);
  border-radius: 6px; font-size: 18px; font-weight: 700; letter-spacing: 2px;
  font-family: Consolas, "Courier New", monospace;
  user-select: all;
}
/* 绑定码卡片：浅蓝底整卡，码与按钮同行、提示在下 */
.bind-code-card {
  background: #f0f5ff; border-radius: var(--tt-radius);
  padding: 12px 16px; margin-bottom: 12px;
}
.bind-code-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.bind-code-hint { font-size: 11px; color: var(--tt-text-sub); margin-top: 8px; }

/* 测试号二维码：组内居中 */
.wx-qr { width: 128px; height: 128px; border-radius: 8px; margin: 8px auto 2px; display: block; }
.qr-wrap { display: flex; justify-content: center; }

/* 邮箱弹层头 */
.form-head { display: flex; align-items: center; justify-content: space-between; padding: 16px 18px 8px; }
.form-title { font-size: 16px; font-weight: 700; color: #323233; }
.form-close { color: #969799; font-size: 18px; padding: 4px; cursor: pointer; }
.popup-hint { padding: 8px 16px 0; font-size: 11px; color: var(--tt-text-sub); line-height: 1.6; }

/* 绑定入口小字（cell value 位） */
.bind-entry { font-size: 12px; color: var(--van-primary-color); }
/* 绑定管理行图标容器（F15）：浅蓝圆角底统一视觉语言，与账号卡头像同款气质；
   van-cell 的 icon 槽自带右间距，容器贴齐标题基线即可 */
.bind-ico {
  width: 32px; height: 32px; margin-right: 10px; border-radius: 9px;
  background: rgba(42, 107, 243, 0.08); color: var(--van-primary-color);
  display: flex; align-items: center; justify-content: center; font-size: 16px;
}
.bind-code-row { display: flex; align-items: center; gap: 10px; }

/* 推送偏好组尾说明 */
.pref-hint { padding: 8px 16px 6px; font-size: 11px; color: var(--tt-text-sub); line-height: 1.6; }

.logout-wrap { padding: 22px 16px 8px; }
.app-footer { text-align: center; font-size: 11px; color: #b0b6bf; padding: 10px 0 16px; }
</style>
