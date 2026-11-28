<template>
  <div class="page">
    <van-nav-bar title="行程" />

    <!-- 推荐表单 -->
    <van-cell-group inset title="AI 行程推荐">
      <van-field v-model="form.city" label="目的地" placeholder="如：北京" />
      <van-field v-model="form.days" type="digit" label="天数" placeholder="1~30" />
      <van-field v-model="form.budget" type="digit" label="预算(元)" placeholder="如 3000" />
      <!-- v2.9 备注：仅作 AI 生成的偏好上下文（人数/忌口/不玩什么），契约 §2.6 -->
      <van-field v-model="form.note" type="textarea" rows="1" autosize maxlength="200" show-word-limit
        label="偏好备注" placeholder="可选：2 大 1 小 / 不吃辣 / 不玩刺激项目…（AI 会据此调整）" />
      <div style="padding:10px 16px">
        <van-button type="primary" block round :loading="recommending"
          :loading-text="recommending ? '后台生成中…' : '加载中…'"
          @click="recommend" :disabled="!form.city || !form.days || !form.budget">生成推荐</van-button>
      </div>
    </van-cell-group>

    <!-- 推荐结果 -->
    <div v-if="result" class="result">
      <van-cell-group inset :title="`${result.city} · ${result.days} 天 · 预算 ¥${result.totalBudget}`">
        <div v-for="d in result.dailyItineraries" :key="d.day" class="day-card">
          <div class="day-title">第 {{ d.day }} 天</div>
          <van-steps direction="vertical" :active="3" active-icon="passed">
            <van-step v-for="slot in slotsOf(d)" :key="slot.label">
              <h4>{{ slot.label }} · {{ slot.spot }}</h4>
              <p v-if="slot.description">{{ slot.description }}</p>
              <p class="meta" v-if="slot.duration">时长：{{ slot.duration }}</p>
              <p class="meta" v-if="slot.ticket">门票：{{ slot.ticket }}</p>
              <p class="meta" v-if="slot.transportation">交通：{{ slot.transportation }}</p>
            </van-step>
          </van-steps>
        </div>
        <div v-if="result.tips?.length" style="padding:8px 16px">
          <van-tag type="success" v-for="t in result.tips" :key="t" plain style="margin:2px">{{ t }}</van-tag>
        </div>
        <div style="padding:10px 16px 16px">
          <van-button type="warning" block round @click="openSaveDialog">保存为行程</van-button>
          <van-button plain block round style="margin-top:8px" @click="copyResult">复制为 Markdown</van-button>
        </div>
      </van-cell-group>
    </div>

    <!-- 保存弹窗：选出发日期（契约 §4.1 不得早于今天）。
         :show-toolbar="false"：DatePicker 自带工具栏与 Dialog 底部按钮重复出现两个「确认」，
         工具栏那个没绑事件是纯摆设（F15 用户反馈），只留 Dialog 的确认 -->
    <van-dialog v-model:show="showSave" title="选择出发日期"
      @confirm="saveTrip" :show-cancel-button="true">
      <van-date-picker v-model="saveDate" title="出发日期" :min-date="minDate" :max-date="maxDate"
        :columns-type="['year','month','day']" :show-toolbar="false" style="margin:10px 0" />
    </van-dialog>

    <!-- 我的行程 -->
    <van-cell-group inset title="我的行程" style="margin-top:14px">
      <van-cell v-for="t in trips" :key="t.id" :title="`${t.city} · ${t.days}天`"
        :label="`${t.startDate} 出发 · 预算 ¥${t.budget}`" :value="statusText(t.status)"
        is-link clickable @click="openDetail(t)" />
      <van-empty v-if="!trips.length" description="还没有保存的行程" />
    </van-cell-group>

    <!-- 推荐生成中覆盖层（同步接口约需 1 分钟）：假时间线 + 可后台运行（F15，方案二）。
         步骤间隔递增模拟"越规划越细"的过程；「后台运行」收起覆盖层后可切页操作，
         请求不受影响（keep-alive 不销毁组件），底部导航「行程」tab 显示加载圆点 -->
    <van-overlay :show="showOverlay" z-index="999" class="plan-overlay">
      <div class="plan-card">
        <div class="plan-head">
          <div class="plan-title">正在规划行程</div>
          <div class="plan-sub">{{ form.city }} · {{ form.days }} 天 · 预算 ¥{{ form.budget }}</div>
        </div>
        <div class="plan-steps">
          <div v-for="(s, i) in TIMELINE" :key="s" class="pstep">
            <div class="prail">
              <span class="pdot" :class="i < activeStep ? 'done' : i === activeStep ? 'active' : 'pending'">
                <van-loading v-if="i === activeStep" type="circular" size="13" color="var(--van-primary-color)" />
                <van-icon v-else-if="i < activeStep" name="success" />
              </span>
              <span v-if="i < TIMELINE.length - 1" class="pline" :class="{ done: i < activeStep }" />
            </div>
            <div class="plabel" :class="{ active: i <= activeStep }">{{ s }}</div>
          </div>
        </div>
        <div class="plan-actions">
          <van-button size="small" plain round @click="cancelRecommend">取消</van-button>
          <van-button size="small" plain round type="primary" @click="showOverlay = false">后台运行，先去逛逛</van-button>
        </div>
      </div>
    </van-overlay>

    <!-- 行程详情 -->
    <van-popup v-model:show="showDetail" position="bottom" style="height:80%">
      <van-nav-bar :title="detail?.trip?.city + ' 行程详情'">
        <template #right>
          <div style="display:flex;gap:8px">
            <van-button v-if="detail?.trip?.status === 'DRAFT'" size="small" type="primary"
              @click="confirmTrip">确认行程</van-button>
            <van-button v-if="detail?.trip?.status === 'DRAFT' || detail?.trip?.status === 'CONFIRMED'"
              size="small" type="danger" plain @click="cancelTrip">取消行程</van-button>
          </div>
        </template>
      </van-nav-bar>
      <div style="padding:12px">
        <van-tag :type="detail?.trip?.status === 'DRAFT' ? 'warning' : 'success'" class="statusTag">
          {{ statusText(detail?.trip?.status) }}
        </van-tag>
        <van-steps direction="vertical" :active="detail?.schedules?.length" active-icon="passed">
          <van-step v-for="s in detail?.schedules" :key="s.id">
            <h4>{{ s.startTime }} · {{ s.title }}</h4>
            <p v-if="s.note">{{ s.note }}</p>
            <van-tag :type="s.status === 'PENDING' ? 'primary' : (s.status === 'FAILED' ? 'danger' : 'success')">
              {{ s.status }}</van-tag>
          </van-step>
        </van-steps>
      </div>
    </van-popup>
  </div>
</template>

<script setup>
defineOptions({ name: 'TravelView' })
import { ref, watch, onMounted, onActivated, onDeactivated } from 'vue'
import { showToast, showSuccessToast, showConfirmDialog } from 'vant'
import http, { RECOMMEND_KEY } from '../api/http'
import { recommending } from '../store/travel'  // 跨页共享：App.vue 行程 tab 加载圆点用

const form = ref({ city: '', days: '', budget: '', note: '' })
// recommending 从 ../store/travel 共享导入（见 import 区）——本地不得重复声明
// 最近一次推荐结果持久化（F15 用户反馈：刷新后推荐结果丢失）——
// keep-alive 只保「切 tab 不丢」，刷新/关浏览器即丢，落 localStorage 补齐；
// 换账号场景由 SettingsView.logout 与 handleUnauthorized 清理同一 key
const STORE_KEY = RECOMMEND_KEY
const result = ref(null)
try { result.value = JSON.parse(localStorage.getItem(STORE_KEY) || 'null') } catch { localStorage.removeItem(STORE_KEY) }
watch(result, (v) => {
  try {
    if (v) localStorage.setItem(STORE_KEY, JSON.stringify(v))
    else localStorage.removeItem(STORE_KEY)
  } catch { /* 存储满/隐私模式静默放弃持久化，不影响主流程 */ }
})
const showSave = ref(false)
const saveDate = ref([])
// keep-alive 页面跨天 setup 常量会过期（ScheduleView 同款问题，稳健性轮对称修复）：
// 每次打开保存弹窗时刷新日期边界
const minDate = ref(new Date())
const maxDate = ref(new Date(Date.now() + 365 * 864e5))
function openSaveDialog() {
  minDate.value = new Date()
  maxDate.value = new Date(Date.now() + 365 * 864e5)
  showSave.value = true
}
const trips = ref([])
const showDetail = ref(false)
const detail = ref(null)

const slotsOf = (d) => [
  { label: '上午', ...d.morning }, { label: '下午', ...d.afternoon }, { label: '晚上', ...d.evening }
].filter(s => s.spot && s.spot.trim())

const statusText = (s) => ({ DRAFT: '待确认', CONFIRMED: '已确认', FINISHED: '已结束' }[s] || s)

let recommendCtrl = null

// ---------- 假时间线（F15 方案二，契约无需后端配合）：固定 4 步 ----------
// F16 用户拍板：平均生成 1~2 分钟 → 每步平均 20 秒（60s 走完前三步），最后一步
// 「生成结果」恰在平均完成时刻激活，不再长时间空转
const TIMELINE = ['理解需求与偏好', '匹配预算与住宿', '规划每日行程', '生成结果']
const activeStep = ref(0)      // 0~3：当前进行中的步骤下标；真实返回后覆盖层直接关闭
const showOverlay = ref(false) // 覆盖层独立于 recommending：「后台运行」收起后生成继续
let timelineTimers = []
function startTimeline() {
  stopTimeline()
  activeStep.value = 0
  showOverlay.value = true
  ;[20000, 40000, 60000].forEach((ms, i) => {
    timelineTimers.push(setTimeout(() => { activeStep.value = i + 1 }, ms))
  })
}
function stopTimeline() {
  timelineTimers.forEach(clearTimeout)
  timelineTimers = []
}

async function recommend() {
  recommending.value = true
  startTimeline()
  recommendCtrl = new AbortController()
  try {
    // 同步推荐后端最长 60s+，单独放宽超时到 120s（全局 30s 会掐断），并支持取消
    // note 可选（契约 §2.9）：空串不传，后端按「未提供」处理
    const data = await http.post('/api/travel/recommend', {
      city: form.value.city, days: +form.value.days, budget: +form.value.budget,
      note: form.value.note?.trim() || undefined
    }, { timeout: 120000, signal: recommendCtrl.signal })
    result.value = data.data
    stopTimeline()
    showToast('生成完成')
  } catch (e) {
    stopTimeline()
    if (e?.code !== 'ERR_CANCELED' && e?.name !== 'CanceledError') {
      result.value = null
    }
  } finally {
    recommending.value = false
    showOverlay.value = false
  }
}

function cancelRecommend() {
  recommendCtrl?.abort()
}

// keep-alive 切走时若仍在生成，全局 toast 告知后台运行中（结果完成时 toast 同样全局可见）
onDeactivated(() => {
  if (recommending.value) showToast('行程生成中，已后台运行，可随时回来查看')
})

async function saveTrip() {
  if (!saveDate.value || saveDate.value.length < 3) { showToast('请选择出发日期'); return }
  const startDate = `${saveDate.value[0]}-${saveDate.value[1]}-${saveDate.value[2]}`
  const data = await http.post('/api/trips', {
    city: result.value.city, days: result.value.days, budget: result.value.totalBudget,
    startDate, recommend: result.value
  })
  showSave.value = false
  showSuccessToast(`已保存，生成 ${data.data.scheduleCount} 条日程`)
  loadTrips()
}

function copyResult() {
  const r = result.value
  let md = `# ${r.city} ${r.days} 日游（预算 ¥${r.totalBudget}）\n\n`
  for (const d of r.dailyItineraries || []) {
    md += `## 第 ${d.day} 天\n`
    for (const s of slotsOf(d)) {
      md += `- **${s.label}**：${s.spot}${s.duration ? '（' + s.duration + '）' : ''}\n`
      if (s.description) md += `  ${s.description}\n`
    }
    md += '\n'
  }
  if (r.tips?.length) md += '> 提示：' + r.tips.join('；') + '\n'
  if (r.warnings?.length) md += '> 注意：' + r.warnings.join('；') + '\n'
  copyText(md, '已复制 Markdown')
}
/** 剪贴板统一入口：clipboard API 不可用（http 非 localhost）时降级 execCommand */
function copyText(text, okMsg) {
  const fallback = () => {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    showToast(ok ? okMsg : '复制失败')
  }
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).then(() => showToast(okMsg), fallback)
  } else {
    fallback()
  }
}

async function loadTrips() {
  const data = await http.get('/api/trips')
  trips.value = data.data
}

async function openDetail(t) {
  const data = await http.get(`/api/trips/${t.id}`)
  detail.value = data.data
  showDetail.value = true
}

async function confirmTrip() {
  const data = await http.post(`/api/trips/${detail.value.trip.id}/confirm`)
  showSuccessToast('已确认，已生成行前提醒')
  detail.value.trip.status = 'CONFIRMED'
  loadTrips()
}

// 取消/删除行程（契约 §4.3a）：行程 CANCELLED + 未触发日程级联取消，之后可重新生成
async function cancelTrip() {
  const trip = detail.value?.trip
  if (!trip) return
  try {
    await showConfirmDialog({
      title: '取消行程',
      message: `确定取消「${trip.city} ${trip.startDate} 出发」的行程吗？未触发的提醒将一并取消，之后可重新生成新行程。`
    })
  } catch { return }
  const data = await http.delete(`/api/trips/${trip.id}`)
  showSuccessToast(`已取消行程${data.data.cancelledSchedules > 0 ? `，一并取消 ${data.data.cancelledSchedules} 条未触发日程` : ''}`)
  showDetail.value = false
  loadTrips()
}

// keep-alive：激活时刷新行程列表（表单填写内容由 keep-alive 保留，不丢）
onMounted(loadTrips)
onActivated(loadTrips)
</script>

<style scoped>
.day-card { padding: 8px 0; border-bottom: 1px solid #f0f0f0; }
/* 与日程页日期头同款竖条，页面间视觉语言统一（F4-3） */
.day-title { font-weight: bold; padding: 4px 16px 4px 26px; position: relative; }
.day-title::before {
  content: ''; position: absolute; left: 16px; top: 50%; transform: translateY(-50%);
  width: 4px; height: 13px; border-radius: 2px; background: var(--van-primary-color);
}
.meta { color: #999; font-size: 12px; margin: 2px 0; }
.statusTag { margin-bottom: 8px; }

/* ---------- 规划时间线卡片（F15）：与对话页步骤卡同一视觉语言，材质更细 ---------- */
/* overlay 自身 flex 居中（van-overlay 默认只是遮罩，内容会顶在页首） */
.plan-overlay { display: flex; align-items: center; justify-content: center; }
.plan-card {
  width: 78%; max-width: 320px;
  background: #fff; border-radius: 20px;
  padding: 20px 20px 14px;
  box-shadow: 0 24px 64px rgba(23, 43, 99, 0.35);
}
.plan-title { font-size: 16px; font-weight: 700; }
.plan-sub { font-size: 12px; color: var(--tt-text-sub); margin-top: 4px; }
.plan-steps { margin: 18px 2px 4px; }
.pstep { display: flex; align-items: stretch; min-height: 36px; }
.prail { width: 20px; display: flex; flex-direction: column; align-items: center; flex: none; }
.pdot {
  width: 18px; height: 18px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; flex: none; background: #fff;
}
/* 已完成：主题色实心白勾；进行中：主题色描边 + 旋转 loading；待进行：灰描边空点 */
.pdot.done { background: var(--van-primary-color); color: #fff; border: none; }
.pdot.active { border: 1.5px solid var(--van-primary-color); color: var(--van-primary-color); }
.pdot.pending { border: 1.5px solid #d8dce4; }
.pline { flex: 1; width: 2px; background: #e8ecf2; margin: 2px 0; }
.pline.done { background: var(--van-primary-color); }
.plabel { align-self: center; margin-left: 12px; font-size: 13px; color: #c2c7cf; padding-bottom: 14px; }
.pstep:last-child .plabel { padding-bottom: 0; }
.plabel.active { color: var(--tt-text); font-weight: 600; }
.pstep:last-child .plabel.active { color: var(--van-primary-color); }
.plan-actions { display: flex; gap: 10px; margin-top: 14px; }
.plan-actions .van-button { flex: 1; }
</style>
