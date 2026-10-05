import type { ObjectDirective } from 'vue'
const cleanup = new WeakMap<HTMLElement, () => void>()
export const sectionLinks: ObjectDirective<HTMLElement> = {
  mounted(root) {
    let frame = 0
    const refresh = () => {
      frame = 0
      root.querySelectorAll<HTMLElement>('h2, h3').forEach(heading => {
        if (heading.querySelector('a, button, input') || heading.closest('.modal-card, .lab-row')) return
        const label = heading.textContent?.trim()
        if (!label) return
        if (!heading.id) {
          const base = 'section-' + label.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-|-$/g, '')
          let id = base, n = 2
          while (document.getElementById(id)) id = `${base}-${n++}`
          heading.id = id
        }
        heading.classList.add('reading-heading')
        const anchor = document.createElement('a')
        anchor.className = 'heading-anchor'
        anchor.href = '#' + encodeURIComponent(heading.id)
        anchor.setAttribute('aria-label', `定位到${label}`)
        anchor.textContent = '#'
        heading.prepend(anchor)
      })
    }
    const observer = new MutationObserver(() => { if (!frame) frame = requestAnimationFrame(refresh) })
    observer.observe(root, { childList: true, subtree: true })
    refresh()
    cleanup.set(root, () => { observer.disconnect(); cancelAnimationFrame(frame) })
  },
  beforeUnmount(root) { cleanup.get(root)?.(); cleanup.delete(root) },
}

