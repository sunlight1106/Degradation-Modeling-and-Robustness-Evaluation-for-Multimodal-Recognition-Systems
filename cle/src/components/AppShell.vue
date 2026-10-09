<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { sectionLinks as vSectionLinks } from '@/directives/sectionLinks'
import WorkspaceTools from './WorkspaceTools.vue'
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
function handleSessionExpired() { void router.replace('/login') }
onMounted(() => window.addEventListener('personal-platform:session-expired', handleSessionExpired))
onBeforeUnmount(() => window.removeEventListener('personal-platform:session-expired', handleSessionExpired))
const menu = ref<HTMLDetailsElement | null>(null)
function closeMenu() { menu.value?.removeAttribute('open') }
watch(() => route.fullPath, closeMenu)
const mainItems: NavItem[] = [
  { label: '学习中心', to: '/app/research', icon: 'book', permission: 'research:use' },
  { label: '工作台', to: '/app/home', icon: 'home', permission: 'dashboard:read' },
  { label: '知识库', to: '/app/knowledge', icon: 'book', permission: 'knowledge:read' },
  { label: '背单词', to: '/app/vocabulary', icon: 'book', permission: 'vocabulary:use' },
  { label: '我的笔记', to: '/app/notes', icon: 'note', permission: 'note:read' },
  { label: '实验台', to: '/app/upload', icon: 'spark', permission: 'experiment:run' },
  { label: '模型中心', to: '/app/models', icon: 'model', permission: 'model:read' },
  { label: '素材库', to: '/app/images', icon: 'images', any: ['file:read', 'file:read:any'] },
  { label: '调用日志', to: '/app/logs', icon: 'logs', any: ['experiment:read', 'experiment:read:any'] },
  { label: '优化对比', to: '/app/comparisons', icon: 'compare', any: ['experiment:read', 'experiment:read:any'] },
  { label: '下载中心', to: '/app/downloads', icon: 'download', any: ['file:read', 'file:read:any', 'experiment:read', 'experiment:read:any'] },
  { label: '余额与充值', to: '/app/billing', icon: 'wallet', permission: 'billing:read' },
  { label: '联系人与聊天', to: '/app/contacts', icon: 'users', permission: 'contacts:use' },
  { label: '站内信箱', to: '/app/mail', icon: 'mail', permission: 'message:read' },
  { label: '群组协作', to: '/app/groups', icon: 'users', permission: 'group:use' },
]

const adminItems: NavItem[] = [
  { label: '平台总览', to: '/app/admin', icon: 'logs', any: ['admin:stats', 'admin:audit'] },
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
  { label: '探索与创作', items: visible(mainItems.filter(item => ['/app/home', '/app/knowledge', '/app/vocabulary', '/app/notes', '/app/research'].includes(item.to))) },
  { label: '识别与评测', items: visible(mainItems.filter(item => ['/app/upload', '/app/models', '/app/images', '/app/comparisons'].includes(item.to))) },
  { label: '记录与账户', items: visible(mainItems.filter(item => ['/app/logs', '/app/downloads', '/app/billing', '/app/contacts', '/app/mail', '/app/groups'].includes(item.to))) },
])
const title = computed(() => {
  const all = [...mainItems, ...adminItems, ...supportItems]
  return all.find(item => route.path === item.to)?.label || '控制台'
})

const quickItems = computed(() => visible(mainItems.filter(item => ['/app/home', '/app/upload', '/app/knowledge'].includes(item.to))))

function outside(event: PointerEvent) { if (menu.value?.open && event.target instanceof Node && !menu.value.contains(event.target)) closeMenu() }
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
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
      <div class="workspace-tools"><WorkspaceTools v-if="authStore.state.user" :key="authStore.state.user.id" />
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
            <footer class="workspace-account">
              <div class="workspace-account-identity"><span class="account-monogram">{{ authStore.state.user?.displayName?.slice(0, 1) || 'U' }}</span><div><strong>{{ authStore.state.user?.displayName }}</strong><small>@{{ authStore.state.user?.username }}</small></div><span class="account-role">{{ authStore.state.user?.roleName }}</span></div>
              <nav class="workspace-account-links" aria-label="账户操作">
                <RouterLink to="/app/settings?section=profile"><AppIcon name="users" :size="15" />个人资料</RouterLink>
                <RouterLink to="/app/settings?section=security"><AppIcon name="shield" :size="15" />密码与登录设备</RouterLink>
                <RouterLink to="/app/settings?section=privacy"><AppIcon name="key" :size="15" />隐私与数据</RouterLink>
                <RouterLink to="/app/settings?section=appearance"><AppIcon name="eye" :size="15" />外观设置</RouterLink>
              </nav>
              <div class="workspace-account-exit"><RouterLink to="/account/switch"><AppIcon name="users" :size="15" />切换账号</RouterLink><RouterLink to="/account/logout"><AppIcon name="logout" :size="15" />退出登录</RouterLink></div>
            </footer>
          </div>
        </details>
      </div>
    </header>
    <main id="workspace-content" v-section-links class="app-content" :class="{'app-content--reader':route.path === '/app/upload'}" tabindex="-1"><RouterView v-if="authStore.state.user" :key="authStore.state.user.id" /></main>
  </div>
</template>
