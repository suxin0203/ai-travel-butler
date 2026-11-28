<template>
  <!-- 外壳由 LoginView 的 login-card 统一承载（F15 卡片唯一化），这里只出字段与按钮 -->
  <van-form @submit="submit">
    <van-field v-model="form.username" name="用户名" label="用户名" placeholder="用户名"
      :rules="[{ required: true, message: '请填写用户名' }]" />
    <van-field v-model="form.password" type="password" name="密码" label="密码" placeholder="密码"
      :rules="[{ required: true, message: '请填写密码' }]" />
    <div class="form-cta">
      <van-button round block type="primary" native-type="submit" :loading="loading">登录</van-button>
    </div>
  </van-form>
</template>

<script setup>
import { reactive, ref } from 'vue'
import http, { setToken } from '../api/http'
import { showToast } from 'vant'

const emit = defineEmits(['done'])
const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    const data = await http.post('/api/auth/login', form)
    setToken(data.data.token)
    showToast('登录成功')
    emit('done', data.data.token)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.form-cta { padding: 18px 16px 14px; }
</style>
