import DOMPurify, { type Config } from 'dompurify'
import { marked } from 'marked'

/**
 * Markdown 渲染工具。
 *
 * 安全策略：marked 只做语法转换，输出必须经 DOMPurify 消毒后才能插入 DOM。
 * 笔记内容来自用户输入，未消毒直接 v-html 会导致 XSS。
 * 消毒配置显式禁止 script、iframe、表单与事件属性。
 */

marked.setOptions({
  gfm: true,
  breaks: true,
})

const SANITIZE_CONFIG: Config = {
  USE_PROFILES: { html: true },
  FORBID_TAGS: ['style', 'form', 'input', 'button', 'select', 'textarea', 'iframe', 'object', 'embed'],
  FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover', 'onfocus', 'formaction', 'srcdoc'],
  ALLOW_DATA_ATTR: false,
}

/** 把 Markdown 渲染为消毒后的 HTML 字符串。 */
export function renderMarkdown(source: string | null | undefined): string {
  if (!source || !source.trim()) return ''
  const raw = marked.parse(source, { async: false }) as string
  return DOMPurify.sanitize(raw, SANITIZE_CONFIG)
}

/**
 * 提取纯文本，用于卡片摘要与搜索高亮。
 * 复用后端同一套降级思路：先去代码块，再剥离标记。
 */
export function markdownToText(source: string | null | undefined, limit = 160): string {
  if (!source) return ''
  const plain = source
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/!\[([^]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^]]*)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s*/gm, '')
    .replace(/^\s*>\s?/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*\d+[.)]\s+/gm, '')
    .replace(/[*_~|]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return plain.length <= limit ? plain : `${plain.slice(0, limit)}…`
}

/** 统计正文字数：中文按字符计，英文按词计。 */
export function countWords(source: string | null | undefined): number {
  if (!source) return 0
  const text = markdownToText(source, Number.MAX_SAFE_INTEGER)
  const cjk = text.match(/[\u4e00-\u9fff]/g)?.length ?? 0
  const latin = text.match(/[A-Za-z0-9]+/g)?.length ?? 0
  return cjk + latin
}

/** 从 Markdown 标题中提取大纲，用于编辑器侧边导航。 */
export interface OutlineItem {
  level: number
  text: string
  anchor: string
}

export function extractOutline(source: string | null | undefined): OutlineItem[] {
  if (!source) return []
  const items: OutlineItem[] = []
  let inCodeBlock = false
  for (const rawLine of source.replace(/\r\n/g, '\n').split('\n')) {
    const line = rawLine.trim()
    if (line.startsWith('```')) {
      inCodeBlock = !inCodeBlock
      continue
    }
    if (inCodeBlock || !line.startsWith('#')) continue

    let level = 0
    while (level < line.length && level < 6 && line[level] === '#') level++
    if (level === 0 || line[level] !== ' ') continue

    const text = markdownToText(line.slice(level).trim(), 60)
    if (!text) continue
    items.push({ level, text, anchor: slugify(text) })
    if (items.length >= 80) break
  }
  return items
}

/** 生成锚点 id，需与 marked 输出的标题 id 规则保持一致。 */
function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^\w\u4e00-\u9fff\s-]/g, '')
    .replace(/\s+/g, '-')
    .slice(0, 60)
}

/** 估算阅读时长（分钟），按中文 300 字/分钟。 */
export function readingMinutes(source: string | null | undefined): number {
  return Math.max(1, Math.round(countWords(source) / 300))
}
