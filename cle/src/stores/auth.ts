import { reactive } from 'vue'
import { api, tokenStorage, ApiClientError } from '@/api/client'
import type { UserView } from '@/types/api'

const state = reactive<{
  user: UserView | null
  loading: boolean
  initialized: boolean
}>({ user: null, loading: false, initialized: false })

export const authStore = {
  state,
  get token() { return tokenStorage.get() },
  async login(username: string, password: string) {
    state.loading = true
    try {
      const response = await api.login(username, password)
      tokenStorage.set(response.token)
      state.user = response.user
      state.initialized = true
      return response.user
    } finally {
      state.loading = false
    }
  },
  async ensureUser() {
    if (state.user) return state.user
    if (!tokenStorage.get()) {
      state.initialized = true
      return null
    }
    state.loading = true
    try {
      state.user = await api.me()
      return state.user
    } catch (reason) {
      if (reason instanceof ApiClientError && reason.status === 401) tokenStorage.clear()
      state.user = null
      return null
    } finally {
      state.loading = false
      state.initialized = true
    }
  },
  logout() {
    tokenStorage.clear()
    state.user = null
    state.initialized = true
  },
  has(permission: string) {
    return state.user?.permissions.includes(permission) ?? false
  },
  hasAny(...permissions: string[]) {
    return permissions.some(permission => authStore.has(permission))
  },
}

