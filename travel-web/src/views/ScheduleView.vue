<template>
  <div class="page">
    <van-nav-bar title="日程" right-text="＋ 新建" @click-right="openAdd" />

    <!-- auto-locate：面板定位改按最近包含块实测（theme.css PC 机身布局给 #app 加了 transform，
         dropdown-item 虽是 fixed 却以机身画布为包含块，Vant 默认用视口坐标会恒差机身顶部留白 24px；
         手机端无 transformed 祖先，此属性无行为变化） -->
    <van-dropdown-menu auto-locate>
      <van-dropdown-item v-model="status" :options="statusOptions" @change="load" />
    </van-dropdown-menu>

    <!-- 工具栏：历史折叠 + 一键清理（契约 §3.4a 只清过期的 FAILED，DONE 由折叠承担视觉清理） -->
    <div class="toolbar">
      <span class="history-toggle" @click="toggleHistory">
        <van-icon :name="showHistory ? 'arrow-up' : 'arrow-down'" /> 历史日程({{ historyCount }})
      </span>
      <span class="cleanup-btn" @click="cleanup"><van-icon name="delete-o" /> 清理过期记录</span>
    </div>

    <!-- 即将到来：日期块 → 上午/下午/晚上分段 → 日程卡片 -->
    <div v-for="g in upcomingGroups" :key="g.date" class="day-block">
      <div class="day-head">
        <span class="day-bar"></span>
        <span class="day-title">{{ g.dateCN }}</span>
        <span class="day-count">共 {{ g.items.length }} 项</span>
      </div>
      <div v-for="p in g.periods" :key="p.name" class="period">
        <div class="period-head">
          <span class="period-dot"></span>
          <span class="period-name">{{ p.name }}</span>
          <span class="period-count">{{ p.items.length }} 项</span>
        </div>
        <div v-for="s in p.items" :key="s.id" class="card" :class="'st-' + s.status">
          <div class="card-top">
            <span class="card-title" :class="{ done: s.status === 'DONE' }">{{ s.title }}</span>
            <van-tag plain :color="typeTag(s.type).color">{{ typeTag(s.type).text }}</van-tag>
          </div>
          <div class="card-time"><van-icon name="clock-o" /> {{ s.startTime.slice(11, 16) }}
            <span class="remind">· {{ remindText(s.remindMinutesBefore) }}</span>
          </div>
          <div v-if="s.location" class="card-meta"><van-icon name="location-o" /> {{ s.location }}</div>
          <div v-if="s.note" class="card-meta"><van-icon name="notes-o" /> {{ s.note }}</div>
          <div class="card-ops">
            <div class="ops-left">
              <van-tag :type="tagType(s.status)" v-if="s.status !== 'PENDING'">{{ statusText(s.status) }}</van-tag>
              <van-checkbox v-if="s.status !== 'CANCELLED' && s.status !== 'FAILED'"
                :model-value="s.status === 'DONE'" @update:model-value="toggleDone(s)" />
            </div>
            <div class="ops-right">
              <!-- 编辑（F16 补齐半成品）：预填表单，改期后按契约重新提醒 -->
              <van-icon v-if="s.status !== 'CANCELLED'" name="edit" class="edit-btn" @click="openEdit(s)" />
              <van-icon name="delete-o" class="del-btn" @click="remove(s)" />
            </div>
          </div>
        </div>
      </div>
    </div>
    <van-empty v-if="!upcomingGroups.length && !historyGroups.length" description="没有日程，点右上角新建" />

    <!-- 历史日程（开始时间早于今天，默认折叠，弱化展示）。
         F16 修复「点了打不开」：历史区渲染在页面最底部，展开后自动滚动到锚点，
         否则内容出现在视口外、用户毫无感知 -->
    <template v-if="showHistory">
      <div ref="historyBlock" class="day-block history">
        <div class="day-head">
          <span class="day-bar" style="background:#c8c9cc"></span>
          <span class="day-title">历史日程</span>
          <span class="day-count">共 {{ historyCount }} 项</span>
        </div>
        <div class="history-hint">开始时间早于今天的日程自动归档在这里：已完成/已提醒供回看，失败或错过可逐条删除，或用上方「清理过期记录」一键清走。</div>
        <div v-for="g in historyGroups" :key="'h' + g.date" class="period">
          <div class="period-head">
            <span class="period-dot" style="background:#c8c9cc"></span>
            <span class="period-name">{{ g.dateCN }}</span>
          </div>
          <div v-for="s in g.items" :key="s.id" class="card st-CANCELLED">
            <div class="card-top">
              <span class="card-title">{{ s.title }}</span>
              <van-tag type="default">{{ statusText(s.status) }}</van-tag>
            </div>
            <div class="card-time history-time"><van-icon name="clock-o" /> {{ s.startTime.slice(0, 16) }}</div>
            <div class="card-ops">
              <div class="ops-left">
                <!-- 历史 FAILED/SENT 改期编辑会按契约重新置 PENDING 并照常提醒 -->
                <van-icon v-if="s.status !== 'CANCELLED'" name="edit" class="edit-btn" @click="openEdit(s)" />
              </div>
              <van-icon name="delete-o" class="del-btn" @click="remove(s)" />
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 新建/编辑日程（F4-1：类型中文下拉 / 年月日时分选择器 / 提醒预设，提交值仍为契约英文枚举） -->
    <van-popup v-model:show="showForm" position="bottom" round style="padding-bottom:14px">
      <div class="form-head">
        <span class="form-title">{{ editingId ? '编辑日程' : '新建日程' }}</span>
        <van-icon name="cross" class="form-close" @click="showForm = false" />
      </div>
      <van-form @submit="submitForm">
        <van-cell-group inset>
          <van-field v-model="form.title" label="标题" placeholder="如：故宫博物院" clearable
            :rules="[{ required: true, message: '请填写标题' }]" />
          <van-field :model-value="typeLabel" label="类型" readonly is-link placeholder="请选择类型"
            @click="showTypePicker = true" />
          <van-field :model-value="startLabel" label="开始时间" readonly is-link placeholder="请选择开始时间"
            :rules="[{ required: true, message: '请选择开始时间' }]"
            @click="openTimePicker('start')" />
          <van-field :model-value="endLabel" label="结束时间" readonly is-link placeholder="可选"
            @click="openTimePicker('end')" />
          <van-field v-model="form.location" label="地点" placeholder="可选" clearable />
          <van-field v-model="form.note" label="备注" placeholder="可选" clearable />
          <van-field :model-value="remindLabel" label="提醒" readonly is-link placeholder="默认提前 30 分钟"
            @click="showRemindPicker = true" />
        </van-cell-group>
        <div style="padding:14px 16px 0">
          <van-button type="primary" block round native-type="submit" :loading="saving">保存</van-button>
        </div>
      </van-form>
    </van-popup>

    <!-- 类型下拉（底部弹层选择器，替代横排单选）。
         v-if + :model-value：弹层内容常驻会保留上次滚动位置，每次打开重建并以当前表单值初始化，
         避免重开表单后直接点确认把上次的旧值悄悄提交回去（review P2） -->
    <van-popup v-model:show="showTypePicker" position="bottom" round>
      <van-picker v-if="showTypePicker" title="日程类型" :columns="TYPE_OPTIONS" :model-value="[form.type]"
        @confirm="onTypeConfirm" @cancel="showTypePicker = false" />
    </van-popup>

    <!-- 提醒预设（契约 §3.4 取值 0~1440，默认 30；未选过时高亮默认 30） -->
    <van-popup v-model:show="showRemindPicker" position="bottom" round>
      <van-picker v-if="showRemindPicker" title="提前提醒" :columns="REMIND_OPTIONS"
        :model-value="[remindMinutes ?? 30]"
        @confirm="onRemindConfirm" @cancel="showRemindPicker = false" />
    </van-popup>

    <!-- 日期+时间选择：Vant 4 无单组件五列（DatePicker 不支持 hour 列），按官方模式
         用 DatePicker(年月日) + TimePicker(时分) 并排组合、共用一个工具栏。
         弹层内容会被 teleport，scoped 样式不跟随，因此这里用内联样式定布局 -->
    <van-popup v-model:show="showTimePicker" position="bottom" round>
      <div style="display:flex;align-items:center;justify-content:space-between;padding:14px 16px">
        <button type="button" style="background:none;border:none;font-size:14px;color:#969799;cursor:pointer"
          @click="showTimePicker = false">取消</button>
        <span style="font-size:16px;font-weight:600;color:#323233">选择时间</span>
        <button type="button" style="background:none;border:none;font-size:14px;color:var(--van-primary-color);cursor:pointer"
          @click="onTimeConfirm">确认</button>
      </div>
      <div v-if="showTimePicker" style="display:flex">
        <van-date-picker style="flex:1" v-model="dateValue" :columns-type="['year', 'month', 'day']"
          :min-date="timeMinDate" :show-toolbar="false" />
        <van-time-picker style="flex:1" v-model="minuteValue" :columns-type="['hour', 'minute']" :show-toolbar="false" />
      </div>
    </van-popup>
  </div>
</template>

<script setup>
defineOptions({ name: 'ScheduleView' })
import { ref, computed, nextTick, onMounted, onActivated } from 'vue'
import { showToast, showConfirmDialog, showSuccessToast } from 'vant'
import http from '../api/http'
import { onNotify } from '../api/sse'

const items = ref([])
const status = ref('')
const showHistory = ref(false)
const historyBlock = ref(null)  // 展开时滚动锚点（修「点了没反应」：历史区在页面底部，视口外）
const showForm = ref(false)
const saving = ref(false)
const editingId = ref(null)
const origStartTime = ref('')   // 编辑时的原开始时间：未改时间的历史项跳过「不得早于当前」校验
const form = ref({ title: '', type: 'TODO', startTime: '', endTime: '', location: '', note: '' })

// 状态单一映射（F16 去重：下拉选项与卡片标签此前各维护一份、文案还不一致）
const STATUS_TEXT = { PENDING: '待提醒', DISPATCHED: '进行中', SENT: '已提醒', FAILED: '失败或错过', CANCELLED: '已取消', DONE: '已完成' }
const statusText = (s) => STATUS_TEXT[s] || s
const statusOptions = [
  { text: '未取消（默认）', value: '' },
  ...Object.entries(STATUS_TEXT).filter(([v]) => v !== 'DISPATCHED').map(([value, text]) => ({ text, value }))
]
const tagType = (s) => ({ DISPATCHED: 'primary', SENT: 'success', FAILED: 'danger' }[s] || 'default')

// ============ F4-1 表单选择器 ============
// 卡片小标签：短词 + 分色（一眼区分类型），提交给后端的仍是契约英文枚举
const TYPE_TAG = {
  TODO: { text: '待办', color: '#888f9c' },
  SPOT: { text: '景点', color: '#0aa870' },
  FOOD: { text: '餐饮', color: '#ff8f3c' },
  TRANSPORT: { text: '交通', color: '#2a6bf3' },
  DEPART: { text: '行前', color: '#8a5cf6' }
}
const typeTag = (t) => TYPE_TAG[t] || { text: t, color: '#888f9c' }

// 新建表单类型下拉：中文长名给用户看，value 是契约枚举
const TYPE_OPTIONS = [
  { text: '待办事项', value: 'TODO' },
  { text: '景点游玩', value: 'SPOT' },
  { text: '餐饮美食', value: 'FOOD' },
  { text: '交通出行', value: 'TRANSPORT' },
  { text: '行前准备', value: 'DEPART' }
]
const typeLabel = computed(() => (TYPE_OPTIONS.find((o) => o.value === form.value.type) || {}).text || form.value.type)
const showTypePicker = ref(false)
const onTypeConfirm = ({ selectedOptions }) => {
  form.value.type = selectedOptions[0].value
  showTypePicker.value = false
}

// 提醒预设：契约取值 0~1440（0=准点提醒）。注意 0 是合法值，不能做 truthy 判断
// 卡片/详情/下拉统一走 remindText 单一事实源（F16 去重：此前选项文案与卡片文案两份维护）
const remindText = (m) => {
  if (m === 0) return '准点提醒'
  if (m === 1440) return '提前 1 天提醒'
  if (m >= 60 && m % 60 === 0) return `提前 ${m / 60} 小时提醒`
  return `提前 ${m} 分钟提醒`
}
const REMIND_OPTIONS = [0, 10, 30, 60, 120, 180, 1440].map((value) => ({ text: remindText(value), value }))
const remindMinutes = ref(null) // null = 未选择 → 提交不带该字段，后端默认 30
const remindLabel = computed(() => (REMIND_OPTIONS.find((o) => o.value === remindMinutes.value) || {}).text || '')
const showRemindPicker = ref(false)
const onRemindConfirm = ({ selectedOptions }) => {
  remindMinutes.value = selectedOptions[0].value
  showRemindPicker.value = false
}

// 日期+时间选择：DatePicker(年月日) 与 TimePicker(时分) 并排，确认时拼回契约格式
// 替换原生 datetime-local：只读字段 + 弹层，移动端不再弹键盘，样式也统一
const showTimePicker = ref(false)
const timeTarget = ref('start') // 'start' | 'end'，确认时写回对应字段
const dateValue = ref([])
const minuteValue = ref([])
const timeMinDate = ref(new Date()) // 每次打开选择器时刷新（keep-alive 页面跨天后 setup 常量会过期，review P2）
const pad2 = (n) => String(n).padStart(2, '0')
const startLabel = computed(() => (form.value.startTime || '').slice(0, 16))
const endLabel = computed(() => (form.value.endTime || '').slice(0, 16))

function openTimePicker(target) {
  timeTarget.value = target
  timeMinDate.value = new Date()
  // 默认值：结束时间带出开始时间；都没选过则用当前时刻
  const base = (target === 'end' ? form.value.endTime || form.value.startTime : form.value.startTime) || ''
  const [d, t] = base ? base.split(' ') : []
  const now = new Date()
  const [y, mo, dd] = d ? d.split('-') : [String(now.getFullYear()), pad2(now.getMonth() + 1), pad2(now.getDate())]
  const [h, mi] = t ? t.split(':') : [pad2(now.getHours()), pad2(now.getMinutes())]
  dateValue.value = [y, mo, dd]
  minuteValue.value = [h, mi]
  showTimePicker.value = true
}

const onTimeConfirm = () => {
  const [y, mo, d] = dateValue.value
  const [h, mi] = minuteValue.value
  // timeTarget 是 'start'/'end'，实际字段名是 startTime/endTime，必须做映射（真机验证发现的坑）
  form.value[timeTarget.value === 'start' ? 'startTime' : 'endTime'] = `${y}-${mo}-${d} ${h}:${mi}:00`
  showTimePicker.value = false
}

// "今天"与后端 cleanup 的 Asia/Shanghai 同口径（浏览器本地即东八区场景）
const todayStr = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// 日程按开始时间自动归段：上午(<12) / 下午(12-18) / 晚上(≥18)
const PERIOD_ORDER = ['上午', '下午', '晚上']
const periodOf = (startTime) => {
  const h = parseInt((startTime || '').slice(11, 13)) || 0
  if (h < 12) return '上午'
  if (h < 18) return '下午'
  return '晚上'
}

const dateCN = (date) => {
  const d = new Date(date.replace(/-/g, '/'))
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()]
  const [, m, dd] = date.split('-')
  const today = todayStr()
  const prefix = date === today ? '今天 · ' : ''
  return `${prefix}${+m}月${+dd}日 ${week}`
}

// computed 派生（评审 P2）：load/onNotify/onActivated 多路刷新下分段与计数不漂移
const upcomingGroups = computed(() => {
  const today = todayStr()
  const map = {}
  for (const it of items.value) {
    const date = (it.startTime || '').slice(0, 10)
    if (date < today) continue  // 已开始的进历史折叠区
    ;(map[date] ||= []).push(it)
  }
  return Object.keys(map).sort().map((date) => {
    const byPeriod = { 上午: [], 下午: [], 晚上: [] }
    // 同一时段内按开始时间排序，形成天然的时间轴顺序
    for (const it of map[date]) byPeriod[periodOf(it.startTime)].push(it)
    Object.values(byPeriod).forEach((list) => list.sort((a, b) => a.startTime.localeCompare(b.startTime)))
    const periods = PERIOD_ORDER.filter((name) => byPeriod[name].length > 0)
      .map((name) => ({ name, items: byPeriod[name] }))
    return { date, dateCN: dateCN(date), items: map[date], periods }
  })
})
const historyGroups = computed(() => {
  const today = todayStr()
  const map = {}
  for (const it of items.value) {
    const date = (it.startTime || '').slice(0, 10)
    if (date >= today) continue
    ;(map[date] ||= []).push(it)
  }
  return Object.keys(map).sort().reverse().map((date) => ({ date, dateCN: dateCN(date), items: map[date] }))
})
const historyCount = computed(() => historyGroups.value.reduce((n, g) => n + g.items.length, 0))

async function load() {
  const data = await http.get('/api/schedules', { params: { status: status.value || undefined } })
  items.value = data.data
}

// 一键清理（契约 §3.4a）：后端只清过期的 FAILED，DONE 由折叠承担视觉清理
async function cleanup() {
  try {
    await showConfirmDialog({ title: '清理过期记录', message: '将清理"失败/错过"且日期已过的日程（已完成的历史不受影响）。确定清理吗？' })
  } catch { return }
  const data = await http.post('/api/schedules/cleanup')
  showToast(data.data.cleaned > 0 ? `已清理 ${data.data.cleaned} 条` : '没有可清理的记录')
  load()
}

function openAdd() {
  editingId.value = null
  form.value = { title: '', type: 'TODO', startTime: '', endTime: '', location: '', note: '' }
  remindMinutes.value = null
  showForm.value = true
}

// 编辑（F16 补齐半成品：editingId/PUT 分支此前无任何入口触发，属死代码）：
// 预填表单；历史 FAILED/SENT 改期后契约语义为重新置 PENDING 照常提醒
function openEdit(s) {
  editingId.value = s.id
  origStartTime.value = s.startTime
  form.value = {
    title: s.title,
    type: s.type || 'TODO',
    startTime: (s.startTime || '').slice(0, 16) + ':00',
    endTime: s.endTime ? s.endTime.slice(0, 16) + ':00' : '',
    location: s.location || '',
    note: s.note || ''
  }
  // 提醒预填为该项当前值（0 是合法值，?? 判空；undefined 才回退未选择）
  remindMinutes.value = s.remindMinutesBefore ?? null
  showForm.value = true
}

// 展开/收起历史（F16 修「点了打不开」感知 bug：历史区在页面最底部，
// 展开后内容在视口外——展开时平滑滚到锚点，给用户明确反馈）
function toggleHistory() {
  showHistory.value = !showHistory.value
  if (showHistory.value) {
    nextTick(() => historyBlock.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
  }
}

async function submitForm() {
  if (form.value.endTime && form.value.endTime < form.value.startTime) {
    showToast('结束时间不能早于开始时间')
    return
  }
  // 双选择器组合没有「日期+时刻」联动边界（今天的小时列仍可选过去时刻），
  // 提交前统一兜底：开始时间不得早于当前时间，否则下一轮扫描直接 MISSED。
  // 编辑历史日程且未改时间时跳过该校验（否则历史项改个标题都保存不了——F16）
  if ((!editingId.value || form.value.startTime !== origStartTime.value)
    && new Date(form.value.startTime.replace(' ', 'T')) < new Date()) {
    showToast('开始时间不能早于当前时间')
    return
  }
  saving.value = true
  try {
    const payload = {
      title: form.value.title,
      type: form.value.type,
      startTime: form.value.startTime
    }
    // 0（开始时提醒）是合法值，必须用 null 判断而不是 truthy
    if (remindMinutes.value !== null) payload.remindMinutesBefore = remindMinutes.value
    if (form.value.endTime) payload.endTime = form.value.endTime
    if (form.value.location) payload.location = form.value.location
    if (form.value.note) payload.note = form.value.note
    if (editingId.value) {
      await http.put(`/api/schedules/${editingId.value}`, payload)
      showSuccessToast('已修改')
    } else {
      await http.post('/api/schedules', payload)
      showSuccessToast('已创建')
    }
    showForm.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function toggleDone(s) {
  if (s.status === 'DONE') {
    await reopen(s)   // 误操作恢复：DONE → PENDING（契约 §3.9）
  } else {
    await http.post(`/api/schedules/${s.id}/complete`)
    s.status = 'DONE'
  }
}

async function remove(s) {
  try {
    await showConfirmDialog({ title: '删除日程', message: `确定删除「${s.title}」吗？` })
  } catch { return }
  await http.delete(`/api/schedules/${s.id}`)
  showToast('已删除')
  load()
}

// 误操作恢复：已完成 → 未完成（契约 §3.9 reopen）
async function reopen(s) {
  const missed = new Date(s.remindAt.replace(' ', 'T')) < new Date()
  const tip = missed
    ? '提醒时间已过，恢复后不再提醒，仅把状态改回"待办"。确定恢复吗？'
    : '恢复后将按原定时间照常提醒。确定恢复吗？'
  try {
    await showConfirmDialog({ title: '恢复日程', message: tip })
  } catch { return }
  await http.post(`/api/schedules/${s.id}/reopen`)
  showSuccessToast('已恢复为待办')
  load()
}

// keep-alive：激活时刷新列表（状态以服务端为准，契约 §4.4 对账语义），并订阅提醒推送刷新
onActivated(load)
onMounted(() => {
  load()
  onNotify(() => load())
})
</script>

<style scoped>
/* 工具栏：白底胶囊卡，与下方日程卡同一视觉语言 */
.toolbar {
  display: flex; justify-content: space-between; align-items: center;
  margin: 10px 12px 0; padding: 9px 14px; font-size: 12px;
  background: #fff; border-radius: 12px; box-shadow: var(--tt-shadow);
}
.history-toggle, .cleanup-btn { display: flex; align-items: center; gap: 2px; cursor: pointer; }
.history-toggle { color: var(--van-primary-color); }
.cleanup-btn { color: #ee0a24; }
/* 历史区说明行（F16：用户不知道这块干嘛用的）+ 卡片编辑按钮 */
.history-hint {
  margin: 0 0 8px; padding: 8px 10px; font-size: 11px; line-height: 1.6;
  color: #969799; background: #f5f6f8; border-radius: 8px;
}
.edit-btn { font-size: 16px; color: var(--van-primary-color); padding: 6px; cursor: pointer; }
.ops-right { display: flex; align-items: center; gap: 4px; }

/* 弹层表单头 */
.form-head { display: flex; align-items: center; justify-content: space-between; padding: 16px 18px 8px; }
.form-title { font-size: 16px; font-weight: 700; color: #323233; }
.form-close { color: #969799; font-size: 18px; padding: 4px; cursor: pointer; }

/* 日期头：主题色竖条 + 中文日期 + 当日数量 */
.day-block { margin: 12px 12px 0; }
.day-head { display: flex; align-items: baseline; gap: 8px; padding: 2px 2px 8px; }
.day-bar { width: 4px; height: 16px; border-radius: 2px; background: var(--van-primary-color); align-self: center; }
.day-title { font-size: 16px; font-weight: 700; color: #323233; }
.day-count { margin-left: auto; font-size: 12px; color: #969799; }

/* 时段分段头：上午 / 下午 / 晚上 */
.period { margin-bottom: 6px; }
.period-head { display: flex; align-items: center; gap: 6px; padding: 6px 4px 6px 8px; }
.period-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--van-primary-color); }
.period-name { font-size: 13px; font-weight: 600; color: #646566; }
.period-count { font-size: 11px; color: #c8c9cc; }

/* 日程卡片：白卡圆角 + 左侧状态色竖条，一眼看出状态 */
.card {
  background: #fff; border-radius: var(--tt-radius); padding: 10px 12px; margin-bottom: 8px;
  border-left: 4px solid var(--van-primary-color);
  box-shadow: var(--tt-shadow);
}
.card.st-DONE { border-left-color: #07c160; }
.card.st-SENT { border-left-color: #07c160; }
.card.st-FAILED { border-left-color: #ee0a24; }
.card.st-CANCELLED { border-left-color: #c8c9cc; opacity: 0.72; }
.card-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.card-title { font-size: 15px; font-weight: 600; color: #323233; }
.card-title.done { text-decoration: line-through; color: #bbb; }
.card-time { margin-top: 4px; font-size: 13px; font-weight: 600; color: var(--van-primary-color); display: flex; align-items: center; gap: 4px; }
.remind { color: #969799; font-weight: 400; font-size: 12px; }
.card-meta { margin-top: 3px; font-size: 12px; color: #969799; display: flex; align-items: center; gap: 4px; }
.card-ops { margin-top: 8px; display: flex; align-items: center; justify-content: space-between; }
.ops-left { display: flex; align-items: center; gap: 10px; }
.del-btn { color: #ee0a24; font-size: 17px; cursor: pointer; }

/* 历史区弱化 */
.day-block.history .card { background: #fafafa; box-shadow: none; }
.history-time { color: #969799; font-weight: 400; }
</style>
