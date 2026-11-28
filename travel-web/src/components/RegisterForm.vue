<template>
  <!-- 外壳由 LoginView 的 login-card 统一承载（F15 卡片唯一化），这里只出字段与按钮 -->
  <van-form @submit="submit">
    <van-field v-model="form.username" label="用户名" placeholder="3~20 位字母/数字/下划线"
      :rules="[{ required: true, pattern: /[A-Za-z0-9_]{3,20}/, message: '3~20 位字母/数字/下划线' }]" />
    <van-field v-model="form.password" type="password" label="密码" placeholder="6~32 位"
      :rules="[{ required: true, message: '请填写密码' }]" />
    <van-field v-model="form.email" label="邮箱" placeholder="可选，用于邮件提醒"
      :rules="[{ pattern: /^$|^\S+@\S+\.\S+$/, message: '邮箱格式不正确' }]" />
    <div class="form-cta">
      <van-button round block type="primary" native-type="submit" :loading="loading">注册并登录</van-button>
    </div>
  </van-form>
</template>

<script setup>
import { reactive, ref } from 'vue'
import http, { setToken } from '../api/http'
import { showToast } from 'vant'

const emit = defineEmits(['done'])
const form = reactive({ username: '', password: '', email: '' })
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    await http.post('/api/auth/register', form)
    // 注册成功直接登录，省一步
    const data = await http.post('/api/auth/login', { username: form.username, password: form.password })
    setToken(data.data.token)
    showToast('注册成功')
    emit('done', data.data.token)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.form-cta { padding: 18px 16px 14px; }
</style>
