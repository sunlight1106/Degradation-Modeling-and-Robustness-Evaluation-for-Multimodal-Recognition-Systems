/**
 * 轻量 Markdown 渲染器。
 *
 * 安全设计：不引入第三方库，改为「先转义、后渲染」——
 * 所有用户输入首先做 HTML 实体转义，之后只用受控规则生成标签，
 * 链接 URL 经协议白名单校验（仅 http/https/mailto），因此不存在注入面。
 *
 * 支持语法子集与后端 MarkdownDocument 保持一致：
 * 标题、段落、列表、有序列表、代码块、行内代码、引用、表格、分隔线、
 * 粗体、斜体、删除线、链接、图片（仅渲染 alt 文本，不加载外链图片）。
 */

/** HTML 实体转义，必须在任何拼接前调用。 */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

const ALLOWED_PROTOCOLS = ['http:', 'https:', 'mailto:']

/** 只放行安全协议的 URL，其余返回 null。 */
function safeUrl(raw: string): string | null {
  const decoded = raw.trim().replace(/&amp;/g, '&')
  if (!decoded) return null
  // 相对锚点与站内路径放行
  if (decoded.startsWith('#') || decoded.startsWith('/')) return decoded
  try {
    const url = new URL(decoded, 'http://local.invalid')
    if (!ALLOWED_PROTOCOLS.includes(url.protocol)) return null
    return decoded
  } catch {
    return null
  }
}

/** 渲染行内标记。输入必须已转义，或在此函数内转义。 */
function renderInline(text: string): string {
  let html = escapeHtml(text)

  // 行内代码：先处理，保护其内容不再被其他规则影响
  const codeSpans: string[] = []
  html = html.replace(/`([^`]+)`/g, (_match, code: string) => {
    codeSpans.push(`<code>${code}</code>`)
    return `\u0000CODE${codeSpans.length - 1}\u0000`
  })

  // 图片：只渲染为带说明的占位，不加载外部资源（避免跟踪像素与 SSRF）
  html = html.replace(/!\[([^\]]*)\]\(([^)\s]+)[^)]*\)/g, (_match, alt: string) => {
    return `<span class="md-image-note" title="图片引用未在预览中加载">🖼 ${alt || '图片'}</span>`
  })

  // 链接
  html = html.replace(/\[([^\]]+)\]\(([^)\s]+)[^)]*\)/g, (_match, label: string, href: string) => {
    const safe = safeUrl(href)
    if (!safe) return label
    return `<a href="${safe}" target="_blank" rel="noopener noreferrer nofollow">${label}</a>`
  })

  // 粗体、斜体、删除线
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/__([^_]+)__/g, '<strong>$1</strong>')
  html = html.replace(/(^|[^*\w])\*([^*\n]+)\*/g, '$1<em>$2</em>')
  html = html.replace(/(^|[^_\w])_([^_\n]+)_/g, '$1<em>$2</em>')
  html = html.replace(/~~([^~]+)~~/g, '<del>$1</del>')

  // 还原行内代码
  html = html.replace(/\u0000CODE(\d+)\u0000/g, (_match, index: string) => codeSpans[Number(index)] ?? '')

  return html
}

function splitTableRow(row: string): string[] {
  let cleaned = row.trim()
  if (cleaned.startsWith('|')) cleaned = cleaned.slice(1)
  if (cleaned.endsWith('|')) cleaned = cleaned.slice(0, -1)
  return cleaned.split('|').map(cell => cell.trim())
}

const DIVIDER_RE = /^(-{3,}|\*{3,}|_{3,})$/

/** 把 Markdown 渲染为受控 HTML 字符串。 */
export function renderMarkdown(markdown: string | null | undefined): string {
  if (!markdown) return ''
  const lines = markdown.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
  const out: string[] = []

  let paragraph: string[] = []
  let listItems: string[] = []
  let listType: 'ul' | 'ol' | null = null
  let quoteLines: string[] = []

  const flushParagraph = () => {
    if (paragraph.length) {
      out.push(`<p>${paragraph.map(renderInline).join('<br />')}</p>`)
      paragraph = []
    }
  }
  const flushList = () => {
    if (listType && listItems.length) {
      out.push(`<${listType}>${listItems.map(item => `<li>${renderInline(item)}</li>`).join('')}</${listType}>`)
    }
    listItems = []
    listType = null
  }
  const flushQuote = () => {
    if (quoteLines.length) {
      out.push(`<blockquote>${quoteLines.map(line => renderInline(line)).join('<br />')}</blockquote>`)
      quoteLines = []
    }
  }
  const flushAll = () => { flushParagraph(); flushList(); flushQuote() }

  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i]
    const line = raw.trimEnd()
    const trimmed = line.trim()

    // 代码块
    if (trimmed.startsWith('```')) {
      flushAll()
      const lang = trimmed.slice(3).trim()
      const code: string[] = []
      i++
      while (i < lines.length && !lines[i].trim().startsWith('```')) {
        code.push(lines[i])
        i++
      }
      const langClass = lang ? ` class="language-${escapeHtml(lang)}"` : ''
      out.push(`<pre><code${langClass}>${escapeHtml(code.join('\n'))}</code></pre>`)
      continue
    }

    if (!trimmed) { flushAll(); continue }

    // 分隔线
    if (DIVIDER_RE.test(trimmed)) { flushAll(); out.push('<hr />'); continue }

    // 标题
    const heading = /^(#{1,6})\s+(.*)$/.exec(trimmed)
    if (heading) {
      flushAll()
      const level = Math.min(6, heading[1].length)
      out.push(`<h${level}>${renderInline(heading[2])}</h${level}>`)
      continue
    }

    // 引用
    if (trimmed.startsWith('>')) {
      flushParagraph(); flushList()
      quoteLines.push(trimmed.replace(/^>\s?/, ''))
      continue
    }
    flushQuote()

    // 表格：当前行含 | 且下一行是分隔行
    const next = (lines[i + 1] ?? '').trim()
    if (trimmed.includes('|') && next.includes('-') && /^[\s:|-]+$/.test(next)) {
      flushAll()
      const header = splitTableRow(trimmed)
      const body: string[][] = []
      i += 2
      while (i < lines.length && lines[i].includes('|') && lines[i].trim()) {
        body.push(splitTableRow(lines[i].trim()))
        i++
      }
      i--
      const columns = header.length
      const thead = `<thead><tr>${header.map(cell => `<th>${renderInline(cell)}</th>`).join('')}</tr></thead>`
      const tbody = `<tbody>${body.map(row => {
        const cells: string[] = []
        for (let c = 0; c < columns; c++) cells.push(`<td>${renderInline(row[c] ?? '')}</td>`)
        return `<tr>${cells.join('')}</tr>`
      }).join('')}</tbody>`
      out.push(`<div class="md-table-wrap"><table>${thead}${tbody}</table></div>`)
      continue
    }

    // 无序列表
    const bullet = /^[-*+]\s+(.*)$/.exec(trimmed)
    if (bullet) {
      flushParagraph(); flushQuote()
      if (listType && listType !== 'ul') flushList()
      listType = 'ul'
      listItems.push(bullet[1])
      continue
    }

    // 有序列表
    const ordered = /^\d+[.)]\s+(.*)$/.exec(trimmed)
    if (ordered) {
      flushParagraph(); flushQuote()
      if (listType && listType !== 'ol') flushList()
      listType = 'ol'
      listItems.push(ordered[1])
      continue
    }

    flushList()
    paragraph.push(trimmed)
  }

  flushAll()
  return out.join('\n')
}

/** 纯文本化：去掉 Markdown 标记，用于摘要、字数统计与纯文本搜索。 */
export function markdownToPlainText(markdown: string | null | undefined): string {
  if (!markdown) return ''
  return markdown
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s*/gm, '')
    .replace(/^\s*>\s?/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*\d+[.)]\s+/gm, '')
    .replace(/[*_~|]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/** 统计正文字数：中文按字计，英文数字按词计。 */
export function countWords(markdown: string | null | undefined): number {
  const text = markdownToPlainText(markdown)
  if (!text) return 0
  const cjk = (text.match(/[\u4e00-\u9fff]/g) ?? []).length
  const latin = (text.match(/[A-Za-z0-9]+/g) ?? []).length
  return cjk + latin
}

/** 估算阅读时长（分钟），按中文 300 字/分钟。 */
export function readingMinutes(markdown: string | null | undefined): number {
  return Math.max(1, Math.round(countWords(markdown) / 300))
}

/** 提取 Markdown 标题层级，用于生成大纲导航。 */
export interface OutlineItem { level: number; text: string }

export function extractOutline(markdown: string | null | undefined): OutlineItem[] {
  if (!markdown) return []
  const items: OutlineItem[] = []
  let inCode = false
  for (const raw of markdown.replace(/\r\n/g, '\n').split('\n')) {
    const trimmed = raw.trim()
    if (trimmed.startsWith('```')) { inCode = !inCode; continue }
    if (inCode) continue
    const match = /^(#{1,6})\s+(.*)$/.exec(trimmed)
    if (!match) continue
    const text = markdownToPlainText(match[2])
    if (text) items.push({ level: match[1].length, text })
  }
  return items
}
