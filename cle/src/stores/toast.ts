import { reactive } from 'vue'

export interface Toast { id: number; kind: 'success' | 'error' | 'info'; message: string }
const state = reactive<{ items: Toast[] }>({ items: [] })
let nextId = 1

function push(kind: Toast['kind'], message: string) {
  const id = nextId++
  state.items.push({ id, kind, message })
  window.setTimeout(() => remove(id), 4200)
}

function remove(id: number) {
  const index = state.items.findIndex(item => item.id === id)
  if (index >= 0) state.items.splice(index, 1)
}

export const toastStore = {
  state,
  success: (message: string) => push('success', message),
  error: (message: string) => push('error', message),
  info: (message: string) => push('info', message),
  remove,
}

