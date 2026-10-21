import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'

import App from './app/App.vue'
import router from './router'
import { installElementPlus } from './platform/i18n/elementLocale'

const app = createApp(App)

app.use(createPinia())
app.use(router)
installElementPlus(app)

app.mount('#app')
