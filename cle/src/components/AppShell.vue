<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import AppLogo from './AppLogo.vue'
import AppIcon from './AppIcon.vue'
import { authStore } from '@/stores/auth'
import { themeStore } from '@/stores/theme'

interface NavItem {
  label: string
  to: string
  icon: string
  permission?: string
  any?: string[]
}

const route = useRoute()
const router = useRouter()
const mobileOpen = ref(false)
const mobileQuery = window.matchMedia('(max-width: 900px)')
const isMobile = ref(mobileQuery.matches)
const sidebar = ref<HTMLElement | null>(null)
const menuButton = ref<HTMLButtonElement | null>(null)
function syncViewport() { isMobile.value = mobileQuery.matches; if (!isMobile.value) mobileOpen.value = false }
onMounted(() => mobileQuery.addEventListener('change', syncViewport))
onBeforeUnmount(() => mobileQuery.removeEventListener('change', syncViewport))
watch(mobileOpen, async open => {
  await nextTick()
  if (open) sidebar.value?.querySelector<HTMLElement>('a, button')?.focus()
  else if (isMobile.value) menuButton.value?.focus()
})
function menuKeydown(event: KeyboardEvent) {
  if (!isMobile.value || !mobileOpen.value) return
  if (event.key === 'Escape') { mobileOpen.value = false; event.preventDefault() }
  if (event.key !== 'Tab') return
  const items = sidebar.value?.querySelectorAll<HTMLElement>('a[href], button:not([disabled])')
  if (!items?.length) return
  const first = items[0], last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) { last.focus(); event.preventDefault() }
  else if (!event.shiftKey && document.activeElement === last) { first.focus(); event.preventDefault() }
}
watch(() => route.fullPath, () => { mobileOpen.value = false })

const mainItems: NavItem[] = [
  { label: '首页', to: '/app/home', icon: 'home', permission: 'dashboard:read' },
  { label: '知识库', to: '/app/knowledge', icon: 'book', permission: 'knowledge:read' },
  { label: '我的笔记', to: '/app/notes', icon: 'note', permission: 'note:read' },
  { label: '实验台', to: '/app/upload', icon: 'spark', permission: 'experiment:run' },
  { label: '模型', to: '/app/models', icon: 'model', permission: 'model:read' },
  { label: '素材库', to: '/app/images', icon: 'images', any: ['file:read', 'file:read:any'] },
  { label: '调用日志', to: '/app/logs', icon: 'logs', any: ['experiment:read', 'experiment:read:any'] },
  { label: '优化对比', to: '/app/comparisons', icon: 'compare', any: ['experiment:read', 'experiment:read:any'] },
  { label: '下载中心', to: '/app/downloads', icon: 'download', any: ['file:read', 'file:read:any', 'experiment:read', 'experiment:read:any'] },
  { label: '余额与充值', to: '/app/billing', icon: 'wallet', permission: 'billing:read' },
  { label: '站内信箱', to: '/app/mail', icon: 'mail', permission: 'message:read' },
]

const adminItems: NavItem[] = [
  { label: '用户管理', to: '/app/users', icon: 'users', permission: 'user:read' },
  { label: '权限管理', to: '/app/roles', icon: 'shield', permission: 'role:read' },
]

const supportItems: NavItem[] = [
  { label: '操作文档', to: '/docs', icon: 'docs' },
  { label: '设置', to: '/app/settings', icon: 'settings' },
]

function visible(items: NavItem[]) {
  return items.filter(item => !item.permission && !item.any
    ? true
    : item.permission ? authStore.has(item.permission) : authStore.hasAny(...(item.any || [])))
}

const title = computed(() => {
  const all = [...mainItems, ...adminItems, ...supportItems]
  return all.find(item => route.path === item.to)?.label || '控制台'
})

const initials = computed(() => authStore.state.user?.displayName?.slice(0, 1).toUpperCase() || 'U')

function logout() {
  authStore.logout()
  router.push('/')
}
</script>

<template>
  <div class="app-layout portal-app">
    <a class="portal-skip" href="#workspace-content">跳转到内容</a>
    <div v-if="mobileOpen" class="sidebar-backdrop" @click="mobileOpen = false" />
    <aside ref="sidebar" :inert="isMobile && !mobileOpen" @keydown="menuKeydown" id="workspace-navigation" class="sidebar" :class="{ 'sidebar--open': mobileOpen }">
      <RouterLink to="/" class="sidebar-brand" aria-label="返回首页"><AppLogo light /></RouterLink>

      <nav class="sidebar-nav" aria-label="控制台导航">
        <p class="nav-caption">工作空间</p>
        <RouterLink v-for="item in visible(mainItems)" :key="item.to" :to="item.to" class="nav-item" @click="mobileOpen = false">
          <AppIcon :name="item.icon" :size="19" />
          <span>{{ item.label }}</span>
        </RouterLink>

        <template v-if="visible(adminItems).length">
          <p class="nav-caption nav-caption--spaced">系统管理</p>
          <RouterLink v-for="item in visible(adminItems)" :key="item.to" :to="item.to" class="nav-item" @click="mobileOpen = false">
            <AppIcon :name="item.icon" :size="19" />
            <span>{{ item.label }}</span>
          </RouterLink>
        </template>

        <p class="nav-caption nav-caption--spaced">帮助</p>
        <RouterLink v-for="item in supportItems" :key="item.to" :to="item.to" class="nav-item" @click="mobileOpen = false">
          <AppIcon :name="item.icon" :size="19" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar-user">
        <div class="avatar">{{ initials }}</div>
        <div class="sidebar-user-copy">
          <strong>{{ authStore.state.user?.displayName }}</strong>
          <span>@{{ authStore.state.user?.username }} · {{ authStore.state.user?.roleName }}</span>
        </div>
        <button class="icon-button" title="退出登录" @click="logout"><AppIcon name="logout" :size="19" /></button>
      </div>
    </aside>

    <main class="app-main">
      <header class="app-topbar">
        <button ref="menuButton" class="icon-button mobile-menu" aria-label="打开菜单" :aria-expanded="mobileOpen" aria-controls="workspace-navigation" @click="mobileOpen = true"><AppIcon name="menu" /></button>
        <div>
          <span class="topbar-eyebrow">RECOGNITION / RESEARCH LAB</span>
          <h1>{{ title }}</h1>
        </div>
        <div class="topbar-tools">
          <span class="topbar-user"><i>{{ initials }}</i>{{ authStore.state.user?.displayName }}</span>
          <button class="topbar-tool" type="button" :title="themeStore.isDark() ? '切换到浅色' : '切换到暗色'" @click="themeStore.toggle()">
            <AppIcon :name="themeStore.isDark() ? 'sun' : 'moon'" :size="18" />
          </button>
          <span class="topbar-build">WORKSPACE 01</span>
          <RouterLink to="/docs" class="topbar-tool" title="查看文档"><AppIcon name="docs" :size="18" /></RouterLink>
          <RouterLink to="/app/settings" class="topbar-tool" title="设置"><AppIcon name="settings" :size="18" /></RouterLink>
          <span class="topbar-status"><span class="live-dot" /> 私人工作空间</span>
        </div>
      </header>
      <div id="workspace-content" class="app-content" tabindex="-1"><RouterView /></div>
    </main>
  </div>
</template>
