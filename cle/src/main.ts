import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { themeStore } from './stores/theme'
import './styles/main.css'
import './styles/portal.css'

themeStore.init()

createApp(App).use(router).mount('#app')
