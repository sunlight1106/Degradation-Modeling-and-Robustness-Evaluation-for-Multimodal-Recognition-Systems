<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { sectionLinks as vSectionLinks } from '@/directives/sectionLinks'
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
const menu = ref<HTMLDetailsElement | null>(null)
function closeMenu() { menu.value?.removeAttribute('open') }
watch(() => route.fullPath, closeMenu)
const mainItems: NavItem[] = [
  { label: '工作台', to: '/app/home', icon: 'home', permission: 'dashboard:read' },
  { label: '知识库', to: '/app/knowledge', icon: 'book', permission: 'knowledge:read' },
  { label: '我的笔记', to: '/app/notes', icon: 'note', permission: 'note:read' },
  { label: '实验台', to: '/app/upload', icon: 'spark', permission: 'experiment:run' },
  { label: '模型中心', to: '/app/models', icon: 'model', permission: 'model:read' },
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

const navGroups = computed(() => [
  { label: '探索与创作', items: visible(mainItems.filter(item => ['/app/home', '/app/knowledge', '/app/notes'].includes(item.to))) },
  { label: '识别与评测', items: visible(mainItems.filter(item => ['/app/upload', '/app/models', '/app/images', '/app/comparisons'].includes(item.to))) },
  { label: '记录与账户', items: visible(mainItems.filter(item => ['/app/logs', '/app/downloads', '/app/billing', '/app/mail'].includes(item.to))) },
])
const title = computed(() => {
  const all = [...mainItems, ...adminItems, ...supportItems]
  return all.find(item => route.path === item.to)?.label || '控制台'
})

const quickItems = computed(() => visible(mainItems.filter(item => ['/app/home', '/app/upload', '/app/knowledge'].includes(item.to))))

function logout() {
  authStore.logout()
  router.push('/')
}
</script>

<template>
  <div class="portal-app">
    <a class="portal-skip" href="#workspace-content">跳转到内容</a>
    <header class="workspace-header">
      <nav class="workspace-breadcrumb" aria-label="当前位置"><RouterLink to="/">HOME</RouterLink><span>/</span><RouterLink to="/app/home">WORKSPACE</RouterLink><span>/</span><span>{{ title }}</span></nav>
      <nav class="workspace-quick" aria-label="常用页面">
        <RouterLink v-for="item in quickItems" :key="item.to" :to="item.to">{{ item.label }}</RouterLink>
        <RouterLink to="/docs">操作文档</RouterLink>
      </nav>
      <div class="workspace-tools">
        <button class="topbar-tool" :aria-label="themeStore.isDark() ? '切换到浅色' : '切换到暗色'" @click="themeStore.toggle()"><AppIcon :name="themeStore.isDark() ? 'sun' : 'moon'" :size="16" /></button>
        <details ref="menu" class="workspace-directory" @keydown.esc="closeMenu(); menu?.querySelector('summary')?.focus()">
          <summary>目录与账户</summary>
          <div class="workspace-directory-body">
            <nav aria-label="全部页面">
              <section v-for="group in [...navGroups, {label:'系统管理',items:visible(adminItems)}, {label:'帮助',items:supportItems}].filter(group => group.items.length)" :key="group.label">
                <p>{{ group.label }}</p>
                <RouterLink v-for="item in group.items" :key="item.to" :to="item.to">{{ item.label }}</RouterLink>
              </section>
            </nav>
            <footer><span>{{ authStore.state.user?.displayName }}</span><button type="button" @click="logout">退出登录</button></footer>
          </div>
        </details>
      </div>
    </header>
    <main id="workspace-content" v-section-links class="app-content" :class="{'app-content--reader':route.path === '/app/upload'}" tabindex="-1"><RouterView /></main>
  </div>
</template>