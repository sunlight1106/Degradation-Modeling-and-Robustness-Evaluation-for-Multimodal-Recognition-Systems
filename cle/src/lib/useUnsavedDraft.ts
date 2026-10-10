import { onBeforeUnmount, onMounted, type ComputedRef } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { toastStore } from '@/stores/toast'

/** Keep unfinished submissions visible and ask before discarding an in-memory draft. */
export function useUnsavedDraft(dirty: ComputedRef<boolean>, submitting: ComputedRef<boolean>) {
  onBeforeRouteLeave(() => {
    if (submitting.value) { toastStore.error('正在提交，请等结果确认后再离开。'); return false }
    return !dirty.value || window.confirm('有尚未提交的内容。离开此页面会丢弃草稿，是否离开？')
  })
  function warnUnload(event: BeforeUnloadEvent) {
    if (dirty.value || submitting.value) { event.preventDefault(); event.returnValue = '' }
  }
  onMounted(() => window.addEventListener('beforeunload', warnUnload))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', warnUnload))
}
