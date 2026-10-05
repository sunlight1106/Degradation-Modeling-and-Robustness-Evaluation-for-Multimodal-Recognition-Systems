import { reactive } from 'vue'

const KEY = 'personal_platform_account_names'
interface AccountName { username: string; displayName: string }
function read(): AccountName[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(KEY) || '[]')
    if (!Array.isArray(parsed)) return []
    const seen = new Set<string>()
    return parsed.filter(item => {
      if (!item || typeof item.username !== 'string' || !/^[A-Za-z0-9._-]{3,60}$/.test(item.username)
        || typeof item.displayName !== 'string' || item.displayName.length > 80 || seen.has(item.username.toLowerCase())) return false
      seen.add(item.username.toLowerCase()); return true
    }).slice(0, 5).map(item => ({ username: item.username, displayName: item.displayName }))
  } catch { return [] }
}
const state = reactive({ items: read() })
function save(items: AccountName[]) {
  try {
    if (items.length) localStorage.setItem(KEY, JSON.stringify(items)); else localStorage.removeItem(KEY)
    state.items = items
    return true
  } catch { return false }
}
export const rememberedAccounts = {
  state,
  remember: (user: AccountName) => save([{ username: user.username, displayName: user.displayName }, ...state.items.filter(item => item.username.toLowerCase() !== user.username.toLowerCase())].slice(0, 5)),
  forget: (username: string) => save(state.items.filter(item => item.username !== username)),
  clear: () => save([]),
}
window.addEventListener('storage', event => { if (event.key === KEY || event.key === null) state.items = read() })
