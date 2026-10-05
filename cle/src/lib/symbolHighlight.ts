import { computed, ref, type InjectionKey } from 'vue'

export function createSymbolHighlight() {
  const hovered = ref('')
  const focused = ref('')

  return {
    activeTerm: computed(() => hovered.value || focused.value),
    enter(term: string) { hovered.value = term },
    leave() { hovered.value = '' },
    focus(event: FocusEvent, term: string) {
      focused.value = (event.currentTarget as HTMLElement).matches(':focus-visible') ? term : ''
    },
    blur() { focused.value = '' },
    pointerDown() { focused.value = '' },
  }
}

export const symbolHighlightKey: InjectionKey<ReturnType<typeof createSymbolHighlight>> = Symbol('symbol-highlight')
