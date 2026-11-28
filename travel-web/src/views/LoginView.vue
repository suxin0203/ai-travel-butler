<template>
  <div class="login-page">
    <!-- 品牌区：白底居中 logo + 标语（真实产品惯例，无渐变装饰） -->
    <div class="login-brand">
      <div class="brand-badge"><van-icon name="guide-o" /></div>
      <div class="brand-title">AI 旅行管家</div>
      <div class="brand-slogan">对话式规划行程 · 到点自动提醒</div>
    </div>
    <!-- 唯一卡片承载「切换 + 表单」：分段控件滑动指示块（F15 用户反馈：van-tabs 下划线小气），
         两表单 v-show 并存——切回时已填内容不丢（等价原 tabs 的保活行为） -->
    <div class="login-card">
      <div class="mode-switch">
        <span class="switch-indicator" :style="{ transform: `translateX(${mode * 100}%)` }" />
        <button type="button" :class="{ on: mode === 0 }" @click="mode = 0">登录</button>
        <button type="button" :class="{ on: mode === 1 }" @click="mode = 1">注册</button>
      </div>
      <login-form v-show="mode === 0" @done="onLogin" />
      <register-form v-show="mode === 1" @done="onLogin" />
    </div>
    <div class="login-footer">AI 旅行管家 · 用 AI 安排你的每一次出行</div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { setToken } from '../api/http'
import { registerGlobalNotify } from '../globalNotify'
import LoginForm from '../components/LoginForm.vue'
import RegisterForm from '../components/RegisterForm.vue'

const router = useRouter()
const mode = ref(0)

function onLogin(token) {
  setToken(token)
  // 全局提醒弹窗 + 长连接在此补注册（评审 P0）：SPA 登录跳转不重载页面，
  // App boot 期的注册覆盖不到新登录会话（幂等，重复调用无副作用）
  registerGlobalNotify()
  // 提醒长连接由 App 层单点持有（onNotify 幂等），这里只管跳转
  router.replace('/chat')
}
</script>

<style scoped>
/* 全高白页 + flex：footer 推到机身底部，内容重心上移更舒展（F15：原布局偏小气） */
.login-page {
  min-height: 100vh;
  background: #fff;
  padding-top: 64px;
  display: flex;
  flex-direction: column;
}
/* 品牌区：白底居中，主题色仅用在 logo 徽标上 */
.login-brand { text-align: center; margin-bottom: 34px; }
.brand-badge {
  width: 68px; height: 68px; margin: 0 auto; border-radius: 20px;
  background: var(--van-primary-color); color: #fff;
  display: flex; align-items: center; justify-content: center; font-size: 34px;
  box-shadow: 0 10px 26px rgba(42, 107, 243, 0.26);
}
.brand-title { font-size: 23px; font-weight: 700; color: var(--tt-text); margin-top: 16px; letter-spacing: 1px; }
.brand-slogan { font-size: 12px; color: var(--tt-text-sub); margin-top: 7px; letter-spacing: 0.5px; }

/* 唯一卡片：切换 + 表单（替代 van-tabs，嵌套卡片合并为一张） */
.login-card {
  margin: 0 16px;
  background: #fff; border-radius: 16px;
  border: 1px solid #eef0f4;
  padding: 10px 0 6px;
}
/* 分段切换：浅灰槽 + 白色滑块平移，选中字重与颜色跟随（无下划线小气感） */
.mode-switch {
  position: relative; display: flex; margin: 4px 16px 14px;
  background: #eff2f7; border-radius: 12px; padding: 3px;
}
.switch-indicator {
  position: absolute; top: 3px; bottom: 3px; left: 3px; width: calc(50% - 3px);
  background: #fff; border-radius: 9px;
  box-shadow: 0 2px 8px rgba(23, 43, 99, 0.12);
  transition: transform 0.25s cubic-bezier(0.3, 0.8, 0.3, 1);
}
.mode-switch button {
  position: relative; z-index: 1; flex: 1; border: none; background: transparent;
  padding: 8px 0; font-size: 15px; color: var(--tt-text-sub); cursor: pointer;
  transition: color 0.2s;
}
.mode-switch button.on { color: var(--van-primary-color); font-weight: 700; }

.login-footer { text-align: center; font-size: 11px; color: #b0b6bf; padding: 22px 0 24px; margin-top: auto; }
</style>
