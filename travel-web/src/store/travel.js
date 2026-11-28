import { ref } from 'vue'

// 行程推荐的跨页共享状态（F15 后台运行）：TravelView 生成中置 true，
// App.vue 底部导航据此在「行程」tab 显示加载圆点；模块级 ref 即单例，无需引入状态库
export const recommending = ref(false)
