import { getToken, handleUnauthorized } from './http'

// 401 跳登录只触发一次（提醒流重连循环会反复 401，防重复 toast 与多次 assign）
let unauthorizedHandled = false
function handleUnauthorizedOnce() {
  if (unauthorizedHandled) return
  unauthorizedHandled = true
  handleUnauthorized()
}

/**
 * SSE 封装（契约 §1.6/§3.0/§4.4）：统一用 fetch + ReadableStream，
 * 绝不使用 EventSource——它带不了 Authorization 头。
 * 手动按行解析 SSE 帧，处理跨 chunk 的半包缓冲（data: 行可能被网络切在任意字节处）。
 */

// 通用流读取：按 \n 切行回调，内部维护跨 chunk 缓冲
async function readLines(response, onLine, signal, onByte) {
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    if (signal?.aborted) { reader.cancel(); return 'aborted' }
    const { done, value } = await reader.read()
    if (done) return 'closed'
    onByte?.()
    buffer += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).replace(/\r$/, '')
      buffer = buffer.slice(idx + 1)
      onLine(line)
    }
  }
}

/** 对话流内部的停止意图：'user'=手动停止（静默）；'timeout'=看门狗超时（要提示） */
let chatStopReason = null

/**
 * 对话流（POST /api/chat）。
 * 契约 §3.0 双形态：非 text/event-stream 的响应按 JSON 错误处理。
 * 事件：start(conversationId) → notice(至多一条) → stage(tool=工具名)×N → chunk×N → done / error
 * 内置 60s 看门狗：连续 60s 无任何字节视为卡死，主动断开并回调 onError。
 * stopChatStream() 手动停止则静默收场。
 * 本函数【绝不向调用方抛异常】：所有失败路径都收敛到 onError 回调——
 * 历史教训：onNotice 未声明导致 notice 事件一到达就抛 ReferenceError，
 * 上层 send() 又没有兜底，界面永久卡死在"思考中"（F5 真机抓虫）。
 */
export async function chatStream({ conversationId, message, onStage, onStart, onChunk, onDone, onError, onNotice }) {
  let failure = null
  let gotDone = false // §1.6：收到 done 才算正常收场；服务端静默断流视为异常中断
  let stopped = false
  chatStopReason = null
  const controller = new AbortController()
  chatStream._stop = (reason = 'user') => {
    if (stopped) return
    stopped = true
    chatStopReason = reason
    controller.abort()
  }

  // 60s 看门狗：长时间无字节（上游卡死/代理悬挂）主动断开，避免界面无限转圈
  let watchdog = setInterval(() => chatStream._stop('timeout'), 60000)

  let resp
  try {
    resp = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + getToken() },
      body: JSON.stringify({ conversationId: conversationId ?? null, message }),
      signal: controller.signal
    })
  } catch (e) {
    // 网络错误/请求阶段中止：手动停止静默收场，看门狗超时提示，其余当网络异常
    clearInterval(watchdog)
    if (chatStopReason === 'user') return
    onError?.(chatStopReason === 'timeout' ? '连接超时，请重试' : '网络异常，请检查网络后重试')
    return
  }

  clearInterval(watchdog)
  const contentType = resp.headers.get('content-type') || ''
  if (!contentType.includes('text/event-stream')) {
    // 被拦截：JSON 错误响应（400/401/404/429/500）
    const data = await resp.json().catch(() => ({}))
    if (resp.status === 401) { handleUnauthorizedOnce() }
    onError?.(data.message || `请求失败(${resp.status})`)
    return
  }

  let lastByte = Date.now()
  watchdog = setInterval(() => {
    if (Date.now() - lastByte > 60000) chatStream._stop('timeout')
  }, 5000)

  let outcome
  try {
    outcome = await readLines(resp, (line) => {
      lastByte = Date.now()
      if (!line.startsWith('data:')) return  // 心跳注释行(: ping)与空行忽略
      const payload = line.slice(5).trim()
      if (!payload) return
      let event
      try { event = JSON.parse(payload) } catch { return }
      switch (event.type) {
        case 'start': onStart?.(event.conversationId); break
        case 'stage': onStage?.(event); break
        case 'notice': onNotice?.(event.message); break
        case 'chunk': onChunk?.(event.content); break
        case 'done': gotDone = true; onDone?.(event.conversationId); break
        case 'error': failure = event.message; break
      }
    }, controller.signal, () => { lastByte = Date.now() })
  } catch (e) {
    // 流中途读取失败（网络断/代理重置/中止——中止的挂起 read 会 reject 走到这里）：
    // 用户手动停止静默收场，看门狗超时提示"超时"，其余提示"中断"；不上抛
    clearInterval(watchdog)
    if (chatStopReason === 'user') return
    onError?.(chatStopReason === 'timeout' ? '连接超时，请重试' : '连接中断，请重试')
    return
  }

  clearInterval(watchdog)
  if (outcome === 'aborted') {
    // 防御分支：真实浏览器里中止总走上面的 reject 路径，此处理论上不可达
    if (chatStopReason === 'timeout') onError?.('连接超时，请重试')
    return
  }
  if (failure) onError?.(failure)
  else if (!gotDone) onError?.('连接中断，请重试')  // done 之前流被服务端静默关闭 → 按 §1.6 异常中断
}

/** 主动停止当前对话流（reason: 'user' 静默 / 'timeout' 提示超时） */
export function stopChatStream(reason = 'user') { chatStream._stop?.(reason) }

// ---------- 提醒长连接：全应用单点持有（评审修复：此前三处各自建立连接互相覆盖） ----------

const notifyHandlers = new Set()
let notifyRunning = false
let notifyClosed = false
let notifyController = null

/**
 * 注册提醒事件监听器。幂等：已有连接则只追加回调，不会重复建连。
 * 返回解绑函数（组件卸载时调用）。
 */
export function onNotify(handler) {
  notifyHandlers.add(handler)
  ensureNotifyLoop()
  return () => notifyHandlers.delete(handler)
}

function ensureNotifyLoop() {
  if (notifyRunning) return
  notifyRunning = true
  notifyClosed = false
  notifyLoop()
}

async function notifyLoop() {
  let retryDelay = 1000
  let lastByteAt = Date.now()
  let watchdogTimer = null

  while (!notifyClosed) {
    lastByteAt = Date.now()
    notifyController = new AbortController()
    const controller = notifyController

    // 90s 看门狗：没有收到任何字节（含 : ping 心跳）即视为假在线，主动断开重连
    watchdogTimer = setInterval(() => {
      if (Date.now() - lastByteAt > 90000) controller.abort()
    }, 5000)

    let reconnect = true
    try {
      const resp = await fetch('/api/notify/stream', {
        headers: { Authorization: 'Bearer ' + getToken() },
        signal: controller.signal
      })
      if (!resp.ok || !(resp.headers.get('content-type') || '').includes('text/event-stream')) {
        if (resp.status === 401) { handleUnauthorizedOnce() }
        throw new Error('notify stream rejected')
      }
      retryDelay = 1000  // 连接成功，重置退避
      const outcome = await readLines(resp, (line) => {
        lastByteAt = Date.now()
        if (!line.startsWith('data:')) return
        const payload = line.slice(5).trim()
        if (!payload) return
        try {
          const event = JSON.parse(payload)
          if (event.type === 'reminder') {
            notifyHandlers.forEach((h) => { try { h(event) } catch (e) { console.error(e) } })
          }
        } catch { /* 忽略无法解析的行 */ }
      }, controller.signal)
      if (outcome === 'aborted' && notifyClosed) reconnect = false
    } catch { /* 网络异常走重连 */ } finally {
      clearInterval(watchdogTimer)
    }

    if (!notifyClosed && reconnect) {
      await new Promise((r) => setTimeout(r, retryDelay + Math.random() * 500))
      retryDelay = Math.min(retryDelay * 2, 30000)
    }
  }
}

/** 关闭提醒长连接并清空监听（登出时调用） */
export function disconnectNotify() {
  notifyRunning = false
  notifyClosed = true
  notifyController?.abort()
  notifyHandlers.clear()
}
