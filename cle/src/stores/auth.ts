import { reactive } from 'vue'
import { api, tokenStorage, ApiClientError } from '@/api/client'
import { themeStore } from './theme'
import { toastStore } from './toast'
import { personalApi } from '@/api/personal'
import type { UserView } from '@/types/api'

const state = reactive<{
  user: UserView | null
  loading: boolean
  initialized: boolean
}>({ user: null, loading: false, initialized: false })

export const authStore = {
  state,
  get token() { return tokenStorage.get() },
  async login(username: string, password: string, otp?: string, reactivate = false) {
    let operationGeneration = tokenStorage.generation()
    state.loading = true
    try {
      const response = await (reactivate ? api.reactivate(username, password, otp) : api.login(username, password, otp))
      if (tokenStorage.generation() !== operationGeneration) throw new ApiClientError('SESSION_CHANGED', '登录状态已更改，请重试。', 401)
      tokenStorage.set(response.token)
      operationGeneration = tokenStorage.generation()
      state.user = response.user
      themeStore.useAccount(response.user.id)
      state.initialized = true
      return response.user
    } finally {
      if (tokenStorage.generation() === operationGeneration) state.loading = false
    }
  },
  async switchAccount(username: string, password: string, otp?: string) {
    if (state.loading) throw new ApiClientError('ACCOUNT_BUSY', '正在处理登录，请稍候。', 409)
    let generation = tokenStorage.generation()
    state.loading = true
    try {
      const response = await api.switchAccount(username, password, otp)
      if (tokenStorage.generation() !== generation) throw new ApiClientError('SESSION_CHANGED', '登录状态已更改，请重新登录。', 401)
      tokenStorage.set(response.token)
      generation = tokenStorage.generation()
      state.user = null
      toastStore.clear()
      themeStore.useAccount(response.user.id)
      state.user = response.user
      state.initialized = true
      return response.user
    } finally { if (tokenStorage.generation() === generation) state.loading = false }
  },
  async ensureUser() {
    if (state.user && tokenStorage.get()) return state.user
    if (state.user) authStore.clearSession()
    if (!tokenStorage.get()) {
      state.initialized = true
      return null
    }
    const expectedToken = tokenStorage.get()
    const expectedGeneration = tokenStorage.generation()
    const isCurrent = () => tokenStorage.get() === expectedToken && tokenStorage.generation() === expectedGeneration
    state.loading = true
    try {
      const user = await api.me()
      if (!isCurrent()) return state.user
      state.user = user
      themeStore.useAccount(user.id)
      return user
    } catch (reason) {
      if (!isCurrent()) return state.user
      if (reason instanceof ApiClientError && reason.status === 401) tokenStorage.clear()
      state.user = null
      return null
    } finally {
      if (isCurrent()) { state.loading = false; state.initialized = true }
    }
  },
  async logout() {
    // Revoke the server-side session before dropping the browser token.
    const generation = tokenStorage.generation()
    await personalApi.logout()
    if (generation !== tokenStorage.generation()) throw new ApiClientError('SESSION_CHANGED', '登录状态已更改，请重新确认当前账号。', 401)
    authStore.clearSession()
  },
  clearSession() {
    tokenStorage.clear()
    themeStore.useAccount()
    toastStore.clear()
    state.user = null
    state.loading = false
    state.initialized = true
  },
  has(permission: string) {
    return state.user?.roleCode === 'ADMIN' || (state.user?.permissions?.includes(permission) ?? false)
  },
  hasAny(...permissions: string[]) {
    return permissions.some(permission => authStore.has(permission))
  },
}


window.addEventListener('personal-platform:session-expired', () => authStore.clearSession())
