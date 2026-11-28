<template>
  <!-- 登录/注册页不显示底部导航，也不参与 keep-alive 缓存（include 白名单按组件名过滤）。
       注意：keep-alive 内的动态组件不能绑 :key（会触发 deactivate is not a function 渲染错误），
       换账号清缓存靠登出时的整页跳转（SettingsView.logout）。 -->
  <router-view v-slot="{ Component }">
    <keep-alive :include="cachedViews">
      <component :is="Component" />
    </keep-alive>
  </router-view>
  <van-tabbar v-if="!$route.meta.hideTab" route fixed placeholder>
    <!-- 行程 tab 的 dot：推荐后台生成中（F15）常亮小圆点，引导用户回来看结果 -->
    <van-tabbar-item to="/chat" icon="chat-o">对话</van-tabbar-item>
    <van-tabbar-item to="/travel" icon="location-o" :dot="travelBusy">行程</van-tabbar-item>
    <van-tabbar-item to="/schedule" icon="todo-list-o">日程</van-tabbar-item>
    <van-tabbar-item to="/settings" icon="setting-o">设置</van-tabbar-item>
  </van-tabbar>
  <!-- 函数式弹层（toast/对话框/通知，main.js 里 teleport 到此）的统一挂载层：
       不进任何视图的布局流，PC 机身布局下弹层才不会混进视图滚动容器（theme.css ≥768px）。
       空容器零高度，手机端无感知。 -->
  <div id="phone-layer"></div>
</template>

<script setup>
import { recommending as travelBusy } from './store/travel'
import { registerGlobalNotify } from './globalNotify'

// 提醒长连接全应用单点持有：App 启动即建（有 token 时），各页面只用 onNotify 订阅回调
const cachedViews = ['ChatView', 'TravelView', 'ScheduleView', 'SettingsView']
// 准点提醒全局弹窗（F15 用户反馈：提醒不应只在对话页出现）。
// 注册收口到 globalNotify（幂等）：这里无条件调、内部自判 token——
// 评审 P0 修复：App setup 只跑一次，无 token 打开 /login 再登录是 SPA 跳转不重载页面，
// boot 期判定覆盖不了新登录会话；LoginView.onLogin 会再调本函数补上注册与建连。
registerGlobalNotify()
window.addEventListener('beforeunload', () => {
  import('./api/sse').then((m) => m.disconnectNotify())
})
</script>

<style>
/* 全局视觉令牌与基础样式自 F4 起统一放在 src/styles/theme.css */
</style>
