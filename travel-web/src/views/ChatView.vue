<template>
  <div class="chat-page">
    <van-nav-bar title="AI 旅行管家" left-text="会话" @click-left="showList = true" />
    <!-- 会话列表 -->
    <van-popup v-model:show="showList" position="left" style="width:70%;height:100%">
      <van-nav-bar title="历史会话" />
      <van-cell v-for="c in conversations" :key="c.id" :title="c.title"
        :label="c.updatedAt" clickable @click="openConversation(c)">
        <template #right-icon>
          <!-- 常显删除图标（左滑在 PC 上不可发现——F2 教训）；.stop 阻止冒泡避免误打开会话 -->
          <van-icon name="delete-o" class="conv-del" @click.stop="removeConversation(c)" />
        </template>
      </van-cell>
      <van-cell title="新对话" icon="plus" clickable @click="newConversation" />
    </van-popup>

    <!-- 消息区 -->
    <div class="messages" ref="msgBox">
      <!-- 空状态：问候 + 可点击建议标签，点击即发送（F4-2） -->
      <div v-if="!messages.length && !streaming" class="empty">
        <div class="hero-badge"><van-icon name="guide-o" /></div>
        <div class="hero-title">你好，我是你的 AI 旅行管家</div>
        <div class="hero-sub">一句话创建、查询日程，或聊聊你的旅行计划</div>
        <div class="sug-grid">
          <div v-for="s in suggestions" :key="s.text" class="sug-card" @click="send(s.text)">
            <span class="sug-icon" :style="{ background: s.bg, color: s.color }">
              <van-icon :name="s.icon" />
            </span>
            <span class="sug-text">{{ s.text }}</span>
            <span class="sug-desc">{{ s.desc }}</span>
          </div>
        </div>
      </div>
      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role === 'USER' ? 'me' : 'ai']">
        <!-- ASSISTANT 用 Markdown 渲染（marked + DOMPurify 防 XSS + 链接新开 tab），USER 保持纯文本转义；
             渲染结果走 renderedContents 缓存（流式期间不重算） -->
        <div v-if="m.role === 'ASSISTANT'" class="bubble md" v-html="renderedContents[i]"></div>
        <div v-else class="bubble">{{ m.content }}</div>
      </div>

      <!-- 流式中的气泡：纯文本 + pre-wrap，避免半截 Markdown 闪烁 -->
      <div v-if="streaming" class="msg ai">
        <div class="bubble streaming">{{ streamingText }}<span class="cursor">▌</span></div>
      </div>

      <!-- 进度时间线（契约 §1.6 stage 事件驱动；纵向窄行避免横向挤压，UI 技能：多步过程必须有步骤指示） -->
      <div v-if="streaming" class="steps-card">
        <div v-for="(s, i) in timelineSteps" :key="s.label" class="tl-row" :class="{ active: i === stepActive }">
          <span class="tl-icon">
            <!-- 当前步骤：旋转动画（用户点名要"旋转"而不是格子跳动） -->
            <van-loading v-if="i === stepActive" type="circular" size="14" color="var(--van-primary-color)" />
            <van-icon v-else-if="i < stepActive" name="passed" color="#07c160" />
            <span v-else class="tl-dot">{{ i + 1 }}</span>
          </span>
          <span class="tl-label">{{ s.label }}</span>
          <span v-if="i === 1 && toolName" class="tl-tool">{{ toolName }}</span>
        </div>
      </div>
    </div>

    <!-- 输入区（F15 重排）：灰胶囊输入框 + 圆形图标钮（发送 tosend / 流式中变红色停止） -->
    <div class="input-bar">
      <div class="chat-input">
        <van-field v-model="input" placeholder="问我行程、日程，或让我帮你建提醒…" :border="false"
          @keydown.enter="send" :disabled="streaming" />
      </div>
      <button v-if="!streaming" class="send-btn" type="button" :disabled="!input.trim()" @click="send"
        aria-label="发送">
        <van-icon name="tosend" />
      </button>
      <button v-else class="send-btn stop" type="button" @click="stopChatStream('user')" aria-label="停止生成">
        <van-icon name="stop" />
      </button>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'ChatView' })
import { ref, computed, nextTick, onMounted, onActivated, onDeactivated } from 'vue'
import { showToast, showConfirmDialog } from 'vant'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import http from '../api/http'
import { chatStream, stopChatStream } from '../api/sse'

const messages = ref([])      // 渲染用：[{role:'USER'|'ASSISTANT', content}]
const conversations = ref([])
const conversationId = ref(null)
const input = ref('')
const streaming = ref(false)
const streamingText = ref('')
const showList = ref(false)
const msgBox = ref(null)

// 进度时间线状态（契约 §1.6 stage 事件 + 首 chunk 自推进；stage 允许回退——多轮工具场景）
const timelineSteps = [{ label: '理解问题' }, { label: '调用工具' }, { label: '生成回答' }]
const stepActive = ref(0)
const toolName = ref('')
let firstChunkSeen = false

// DOMPurify 链接加固（稳健性轮 P1-10）：AI 回复里的 <a> 统一新开 tab，防止点击把整个 SPA 导航走
// afterSanitizeAttributes 全局 hook 只需注册一次；模块级执行，随首次 import 生效
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})
const renderMd = (t) => DOMPurify.sanitize(marked.parse(t || '', { breaks: true }))
// 历史消息 Markdown 渲染按数组缓存（稳健性轮 P0-4）：v-for 内联 renderMd 时每个 chunk、
// 每次按键都会对全部历史 ASSISTANT 消息重跑 marked+DOMPurify，长对话流式后期必卡——
// computed 依赖 messages 数组本身，流式期间（只变 streamingText）不重算
const renderedContents = computed(() => messages.value.map((m) => (m.role === 'ASSISTANT' ? renderMd(m.content) : null)))
const scrollBottom = () => nextTick(() => msgBox.value?.scrollTo({ top: msgBox.value.scrollHeight }))

// 空状态建议标签（F4-2）：短句、直接可测出工具调用/规划两类能力，点击即发送。
// 图标统一主题色淡底（一种色系一种样式，不做彩虹配色）
const suggestions = [
  { icon: 'clock-o', text: '明天上午9点提醒我去故宫', desc: '一句话创建日程', bg: 'rgba(42, 107, 243, 0.08)', color: 'var(--van-primary-color)' },
  { icon: 'todo-list-o', text: '查一下我的日程', desc: '看看最近的安排', bg: 'rgba(42, 107, 243, 0.08)', color: 'var(--van-primary-color)' },
  { icon: 'location-o', text: '北京两日游怎么安排？', desc: '让 AI 帮你规划', bg: 'rgba(42, 107, 243, 0.08)', color: 'var(--van-primary-color)' },
  { icon: 'delete-o', text: '帮我删掉故宫的日程', desc: '一句话删除日程', bg: 'rgba(42, 107, 243, 0.08)', color: 'var(--van-primary-color)' }
]

async function loadConversations() {
  const data = await http.get('/api/conversations', { params: { limit: 50 } })
  conversations.value = data.data
}

async function openConversation(c) {
  showList.value = false
  conversationId.value = c.id
  const data = await http.get(`/api/conversations/${c.id}/messages`)
  messages.value = data.data
  scrollBottom()
}

function newConversation() {
  showList.value = false
  conversationId.value = null
  messages.value = []
}

// 删除历史会话（契约 §3.2a）：删当前打开的会话前先按用户主动停止语义停流；
// 404（已不存在/双击竞态）按契约静默处理；当前会话被删则回到新对话态
async function removeConversation(c) {
  try {
    await showConfirmDialog({ title: '删除会话', message: `确定删除「${c.title}」吗？将一并删除其全部消息，不可恢复。` })
  } catch { return }
  if (streaming.value && conversationId.value === c.id) stopChatStream('user')
  try {
    await http.delete(`/api/conversations/${c.id}`)
  } catch { /* 404 = 已不存在，静默 */ }
  if (conversationId.value === c.id) {
    conversationId.value = null
    messages.value = []
    streamingText.value = ''
  }
  loadConversations()
  showToast('已删除')
}

// 支持两种调用：输入框回车/按钮（无参，读 input）与建议标签点击（传入预设文本）
async function send(presetText) {
  const text = (typeof presetText === 'string' ? presetText : input.value).trim()
  if (!text || streaming.value) return
  input.value = ''
  messages.value.push({ role: 'USER', content: text })
  streaming.value = true
  streamingText.value = ''
  stepActive.value = 0
  toolName.value = ''
  firstChunkSeen = false
  scrollBottom()

  let failed = false
  try {
    await chatStream({
      conversationId: conversationId.value,
      message: text,
      onStart: (id) => { conversationId.value = id },
      onNotice: (msg) => showToast(msg, { duration: 4000 }),
      onStage: (e) => {
        // 工具事件允许回退：多轮工具时 chunk 可能已把步骤推到第 3 步
        toolName.value = e.tool || ''
        stepActive.value = 1
      },
      onChunk: (chunk) => {
        if (!firstChunkSeen) {
          firstChunkSeen = true
          if (stepActive.value < 2) stepActive.value = 2
        }
        streamingText.value += chunk
        scrollBottom()
      },
      onDone: () => {},
      onError: (msg) => {
        failed = true
        showToast('对话失败：' + msg)
      }
    })

    if (!failed) {
      // 回合结束：把正在打字的气泡落成正式 Markdown 消息
      // （首个 chunk 前手动停止时无内容，不落空气泡——review P3）
      if (streamingText.value) messages.value.push({ role: 'ASSISTANT', content: streamingText.value })
      streamingText.value = ''
    }
  } catch (e) {
    // 双保险：sse.js 已约定不上抛，这里防未来回归——任何意外异常都不能把界面永久卡死在"思考中"
    console.error(e)
    failed = true
    showToast('对话异常，请重试')
  } finally {
    if (failed && conversationId.value) {
      // 异常中断对账：以服务端历史为准（契约 §1.6）
      const data = await http.get(`/api/conversations/${conversationId.value}/messages`).catch(() => null)
      if (data) messages.value = data.data
    }
    streaming.value = false
    streamingText.value = ''
    scrollBottom()
    loadConversations()
  }
}

// keep-alive：激活时刷新会话列表（聊天内容本身保留，不丢）
onActivated(loadConversations)
onMounted(loadConversations)

// 准点提醒的全局弹窗收敛到 App.vue（F15）：此处不再重复订阅弹 toast
onDeactivated(() => stopChatStream('user'))  // 切走页面时停止进行中的对话流
</script>

<style scoped>
.chat-page { display: flex; flex-direction: column; height: calc(100vh - 50px); }
.messages { flex: 1; overflow-y: auto; padding: 12px; }
/* 空状态：品牌问候 + 建议标签（F4-2 引入，F8 去渐变化） */
.empty { margin-top: 26px; }
.hero-badge {
  width: 56px; height: 56px; margin: 0 auto; border-radius: 18px;
  background: var(--van-primary-color); color: #fff; font-size: 28px;
  display: flex; align-items: center; justify-content: center;
}
.hero-title { text-align: center; font-size: 17px; font-weight: 700; color: #323233; margin-top: 14px; }
.hero-sub { text-align: center; font-size: 12px; color: var(--tt-text-sub); margin-top: 6px; }
.sug-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 22px; }
.sug-card {
  background: #fff; border-radius: var(--tt-radius); padding: 12px;
  border: 1px solid #eef0f4; cursor: pointer;
  transition: border-color 0.2s, background-color 0.2s;
}
.sug-card:active { border-color: var(--van-primary-color); background: #f7f9ff; }
.sug-icon { width: 30px; height: 30px; border-radius: 10px; display: flex; align-items: center; justify-content: center; font-size: 16px; margin-bottom: 8px; }
.sug-text { display: block; font-size: 13px; font-weight: 600; color: #323233; line-height: 1.4; }
.sug-desc { display: block; font-size: 11px; color: var(--tt-text-sub); margin-top: 3px; }

.msg { margin-bottom: 10px; display: flex; }
.msg.me { justify-content: flex-end; }
.bubble { max-width: 78%; padding: 8px 12px; border-radius: 10px; background: #fff; font-size: 14px; line-height: 1.6; word-break: break-word; }
.msg.me .bubble { background: var(--van-primary-color); color: #fff; }
.bubble.streaming { white-space: pre-wrap; }
.bubble.md :deep(h1), .bubble.md :deep(h2), .bubble.md :deep(h3) { font-size: 15px; margin: 8px 0 4px; }
.bubble.md :deep(p) { margin: 4px 0; }
.bubble.md :deep(ul), .bubble.md :deep(ol) { margin: 4px 0; padding-left: 18px; }
.cursor { animation: blink 1s infinite; }
@keyframes blink { 50% { opacity: 0; } }
.steps-card { background: #fff; border-radius: 10px; padding: 8px 12px; margin-top: 4px; }
.tl-row { display: flex; align-items: center; gap: 8px; padding: 4px 0; color: #c8c9cc; font-size: 13px; }
.tl-row.active { color: var(--van-primary-color); }
.tl-icon { width: 18px; display: flex; align-items: center; justify-content: center; }
.tl-dot { width: 14px; height: 14px; line-height: 14px; text-align: center; border: 1px solid #c8c9cc; border-radius: 50%; font-size: 10px; }
.tl-label { flex: 1; }
.tl-tool { max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: #969799; }
/* 输入区（F15）：白条 + 灰胶囊输入框 + 圆形图标钮；发丝线与 tabbar 分界 */
.input-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; background: #fff;
  border-top: 1px solid #f0f2f6;
}
.chat-input {
  flex: 1; min-width: 0;
  background: #f2f4f8; border-radius: 21px;
}
/* field 是 van-cell 实现：去自带白底/内边距/发丝线，融进胶囊 */
.chat-input :deep(.van-cell) { background: transparent; padding: 5px 14px; }
.chat-input :deep(.van-cell::after) { display: none; }
.chat-input :deep(.van-field__control) { font-size: 14px; }
/* 圆形图标钮：主题色发送 / 红色停止；空输入禁用降透明 */
.send-btn {
  width: 38px; height: 38px; border-radius: 50%; border: none; flex: none;
  background: var(--van-primary-color); color: #fff; font-size: 17px;
  display: flex; align-items: center; justify-content: center; cursor: pointer;
  transition: opacity 0.2s;
}
.send-btn:disabled { opacity: 0.35; cursor: default; }
.send-btn.stop { background: #ee6666; }
/* 会话行删除图标：常显红色 + 足够点击区（左滑在 PC 不可发现——F2 教训） */
.conv-del { font-size: 17px; color: #ee0a24; padding: 10px; cursor: pointer; }
</style>
