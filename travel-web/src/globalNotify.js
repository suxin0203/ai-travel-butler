import { showNotify } from 'vant'
import router from './router'
import { getToken } from './api/http'
import { onNotify } from './api/sse'

/**
 * 准点提醒的全局弹窗（F15）：任意页面在线即弹顶部通知条，点击跳日程页查看。
 *
 * 为什么单独成模块（评审 P0）：App.vue setup 的 `if (getToken())` 是 boot 期一次性判定，
 * 而用户常规入口是无 token 打开 /login → 登录成功 router.replace（SPA 跳转不重载页面）
 * → App setup 不会重跑，新登录会话既不建连也不订阅。注册收口到这里后，
 * boot（已有 token）与 LoginView.onLogin（登录成功）两条路径都会调用本函数。
 *
 * 幂等性：showReminder 是模块级具名函数引用，onNotify 内部用 Set 存回调，
 * 重复调用只会 add 同一引用（天然去重）；登出 disconnectNotify 清空 Set 后，
 * 再次登录重新调用本函数即可恢复订阅与长连接。
 */
export function registerGlobalNotify() {
  if (!getToken()) return  // 无 token 时不注册也不建连（避免 401 重连循环），登录成功后再调
  onNotify(function onReminder(e) {
    showNotify({
      type: 'primary',
      message: `⏰ ${e.title}\n${e.content}`,
      duration: 8000,
      onClick: () => router.push('/schedule')
    })
  })
}
