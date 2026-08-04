import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin } from '@tanstack/vue-query'
import ElementPlus from 'element-plus'

import 'element-plus/dist/index.css'
import './shared/styles/tokens.css'
import './shared/styles/base.css'
import './shared/styles/element-plus-overrides.css'

import App from './App.vue'
import { router } from './app/router'

const app = createApp(App)

app.use(createPinia())
app.use(VueQueryPlugin)
app.use(router)
app.use(ElementPlus)

app.mount('#app')
