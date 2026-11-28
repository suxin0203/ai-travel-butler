import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import Vant, { setToastDefaultOptions, setDialogDefaultOptions, setNotifyDefaultOptions } from 'vant'
import 'vant/lib/index.css'
// 全局设计令牌放在 Vant 样式之后引入，保证变量覆盖生效
import './styles/theme.css'

// PC 机身布局（theme.css ≥768px）配套收口：toast/对话框/通知默认 teleport 到 body，
// 会跑到机身外面；统一挂到 App.vue 里的 #phone-layer（#app 内的独立弹层容器），
// 其 position:fixed 以机身画布为包含块（原理见 theme.css）。
// 手机端 body 即全屏，挂哪都一样，无行为差异。
setToastDefaultOptions({ teleport: '#phone-layer' })
setDialogDefaultOptions({ teleport: '#phone-layer' })
setNotifyDefaultOptions({ teleport: '#phone-layer' })

const app = createApp(App)
app.use(router)
// 全量注册 Vant 组件（项目体量下按需引入的收益不值复杂度）
app.use(Vant)
app.mount('#app')
