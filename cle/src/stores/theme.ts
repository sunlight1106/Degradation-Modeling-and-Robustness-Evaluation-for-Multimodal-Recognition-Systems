import { reactive } from 'vue'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'pp:theme'

const state = reactive<{ theme: Theme }>({ theme: 'light' })

function apply(theme: Theme) {
  state.theme = theme
  if (typeof document !== 'undefined') document.documentElement.dataset.theme = theme
  try {
    localStorage.setItem(STORAGE_KEY, theme)
  } catch {
    // localStorage 不可用时忽略，仅保留内存态
  }
}

export const themeStore = {
  state,
  /** 启动时调用：优先读本地存储，否则跟随系统偏好。 */
  init() {
    let theme: Theme = 'light'
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      if (stored === 'dark' || stored === 'light') {
        theme = stored
      } else if (window.matchMedia?.('(prefers-color-scheme: dark)').matches) {
        theme = 'dark'
      }
    } catch {
      // 忽略读取失败
    }
    apply(theme)
  },
  toggle() {
    apply(state.theme === 'dark' ? 'light' : 'dark')
  },
  isDark() {
    return state.theme === 'dark'
  },
}
