import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'

// 引入组件库
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'

const app = createApp(App)

// app 使用组件
app.use(createPinia())
app.use(router)
app.use(Antd)

app.mount('#app')
