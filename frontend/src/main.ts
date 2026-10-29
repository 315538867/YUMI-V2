import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'
import '@fontsource/noto-sans-sc/chinese-simplified-400.css'
import '@fontsource/noto-sans-sc/chinese-simplified-500.css'
import '@fontsource/noto-sans-sc/chinese-simplified-600.css'
import '@fontsource/noto-sans-sc/chinese-simplified-700.css'
import '@fontsource/noto-sans-sc/latin-400.css'
import '@fontsource/noto-sans-sc/latin-500.css'
import '@fontsource/noto-sans-sc/latin-600.css'
import '@fontsource/noto-sans-sc/latin-700.css'
import './platform/theme/tokens.css'
import './platform/theme/components.css'

import App from './app/App.vue'
import router from './router'
import { installElementPlus } from './platform/i18n/elementLocale'

const app = createApp(App)

app.use(createPinia())
app.use(router)
installElementPlus(app)

app.mount('#app')
