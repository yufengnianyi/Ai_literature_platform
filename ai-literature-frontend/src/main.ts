import { createApp } from 'vue';
import Antd from 'ant-design-vue';

import App from './App.vue';
import router from './router';
import { pinia } from './stores';
import 'ant-design-vue/dist/reset.css';
import './assets/base.css';

const app = createApp(App);

app.use(pinia);
app.use(router);
app.use(Antd);

app.mount('#app');
