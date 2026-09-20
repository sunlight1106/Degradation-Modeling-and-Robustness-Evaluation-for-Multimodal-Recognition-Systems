// Minimal Markdown renderer for knowledge entries and notes.
//
// 安全模型：默认全量转义，只按白名单从 Markdown 语法生成标签，
// 不解析任何内联 HTML。因此用户输入的 <script>、<img onerror> 等
// 会以文本形式显示，不会成为活动节点。链接协议另做白名单校验。
//
// 覆盖语法：标题、段落、无序/有序列表（单层嵌套）、围栏代码块、
// 行内代码、引用、表格、分隔线、粗体、斜体、删除线、链接、图片。

const ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

export function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, char => ESCAPES[char])
}

/** 只允许安全协议；相对链接与锚点放行，其余一律拒绝。 */
function safeUrl(value: string): string | null {
  const url = value.trim()
  if (!url) return null
  if (/^[/#.]/.test(url)) return url
  const match = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(url)
  if (!match) return url
  const scheme = match[1].toLowerCase()
  if (scheme === 'http' || scheme === 'https' || scheme === 'mailto') return url
  return null
}

/** 由标题文本生成用于页内跳转的 id，去掉不安全字符。 */
function slugify(value: string, used: Set<string>): string {
  let base = stripInline(value)
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48)
  if (!base) base = 'section'
  let candidate = base
  let index = 2
  while (used.has(candidate)) candidate = `${base}-${index++}`
  used.add(candidate)
  return candidate
}

interface HeadingAnchor {
  level: number
  id: string
  text: string
}

export interface RenderResult {
  html: string
  headings: HeadingAnchor[]
}

// ---------------------------------------------------------------------------
// 行内解析
// ---------------------------------------------------------------------------

/** 去掉行内标记，返回纯文本（用于生成 id、摘要、目录）。 */
export function stripInline(value: string): string {
  return value
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/(\*\*|__)(.*?)\1/g, '$2')
    .replace(/(\*|_)(.*?)\1/g, '$2')
    .replace(/~~(.*?)~~/g, '$1')
}

/**
 * 解析行内标记。输入必须是尚未转义的原始文本，
 * 本函数对每段文本内容调用 escapeHtml，对 URL 调用 safeUrl。
 */
function renderInline(text: string): string {
  let source = text

  // 先抽出需要保护的结构（代码、链接、图片），用占位符替代，
  // 避免后续的粗体/斜体规则破坏它们的语法。
  const placeholders: string[] = []
  const reserve = (html: string): string => {
    placeholders.push(html)
    return `\u0000${placeholders.length - 1}\u0000`
  }

  // 图片
  source = source.replace(/!\[([^\]]*)\]\(\s*([^)\s]+)(?:\s+"[^"]*")?\s*\)/g, (_m, alt, href) => {
    const url = safeUrl(String(href))
    if (!url) return reserve(escapeHtml(String(alt)))
    return reserve(
      `<img src="${escapeHtml(url)}" alt="${escapeHtml(String(alt))}" loading="lazy" />`,
    )
  })

  // 链接
  source = source.replace(/\[([^\]]+)\]\(\s*([^)\s]+)(?:\s+"[^"]*")?\s*\)/g, (_m, label, href) => {
    const url = safeUrl(String(href))
    if (!url) return reserve(escapeHtml(stripInline(String(label))))
    const external = /^https?:/i.test(url)
    return reserve(
      `<a href="${escapeHtml(url)}"${external ? ' target="_blank" rel="noopener noreferrer"' : ''}>${renderEmphasis(String(label))}</a>`,
    )
  })

  // 行内代码
  source = source.replace(/`([^`]+)`/g, (_m, code) => reserve(`<code>${escapeHtml(String(code))}</code>`))

  // 其余部分做强调处理并转义
  source = renderEmphasis(source)

  // 还原占位符
  return source.replace(/\u0000(\d+)\u0000/g, (_m, index) => placeholders[Number(index)] ?? '')
}

/** 处理粗体、斜体、删除线。输入未转义，输出已转义。 */
function renderEmphasis(text: string): string {
  let result = escapeHtml(text)
  // 顺序重要：先双字符标记，再单字符标记
  result = result.replace(/\*\*\*([^*]+)\*\*\*/g, '<strong><em>$1</em></strong>')
  result = result.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  result = result.replace(/__([^_]+)__/g, '<strong>$1</strong>')
  result = result.replace(/(^|[^*\w])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>')
  result = result.replace(/(^|[^_\w])_([^_\n]+)_(?!_)/g, '$1<em>$2</em>')
  result = result.replace(/~~([^~]+)~~/g, '<del>$1</del>')
  return result
}

// ---------------------------------------------------------------------------
// 块级解析
// ---------------------------------------------------------------------------

function splitTableRow(line: string): string[] {
  let row = line.trim()
  if (row.startsWith('|')) row = row.slice(1)
  if (row.endsWith('|')) row = row.slice(0, -1)
  // 支持 \| 转义
  return row.split(/(?<!\\)\|/).map(cell => cell.trim().replace(/\\\|/g, '|'))
}

function isDelimiterRow(line: string): boolean {
  const trimmed = line.trim()
  if (!trimmed.includes('-') || !trimmed.includes('|')) return false
  return /^\|?[\s:|-]+\|?$/.test(trimmed)
}

export function renderMarkdown(markdown: string): RenderResult {
  const headings: HeadingAnchor[] = []
  const usedIds = new Set<string>()
  const output: string[] = []

  if (!markdown || !markdown.trim()) return { html: '', headings }

  const lines = markdown.replace(/\r\n?/g, '\n').split('\n')
  let paragraph: string[] = []
  let index = 0

  const flushParagraph = () => {
    if (paragraph.length) {
      output.push(`<p>${renderInline(paragraph.join(' '))}</p>`)
      paragraph = []
    }
  }

  while (index < lines.length) {
    const line = lines[index]
    const trimmed = line.trim()

    // 围栏代码块
    if (trimmed.startsWith('```')) {
      flushParagraph()
      const lang = trimmed.slice(3).trim()
      const code: string[] = []
      index++
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        code.push(lines[index])
        index++
      }
      index++ // 跳过结束围栏
      const langAttr = lang ? ` class="language-${escapeHtml(lang.replace(/[^\w-]/g, ''))}"` : ''
      output.push(
        `<pre><code${langAttr}>${escapeHtml(code.join('\n'))}</code></pre>`,
      )
      continue
    }

    // 空行
    if (!trimmed) {
      flushParagraph()
      index++
      continue
    }

    // 分隔线
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      flushParagraph()
      output.push('<hr />')
      index++
      continue
    }

    // 标题
    const heading = /^(#{1,6})\s+(.*)$/.exec(trimmed)
    if (heading) {
      flushParagraph()
      const level = heading[1].length
      const text = heading[2].trim()
      const id = slugify(text, usedIds)
      headings.push({ level, id, text: stripInline(text) })
      output.push(`<h${level} id="${escapeHtml(id)}">${renderInline(text)}</h${level}>`)
      index++
      continue
    }

    // 引用块
    if (trimmed.startsWith('>')) {
      flushParagraph()
      const quote: string[] = []
      while (index < lines.length && lines[index].trim().startsWith('>')) {
        quote.push(lines[index].trim().replace(/^>\s?/, ''))
        index++
      }
      // 引用内容递归渲染，支持多段落与列表
      const inner = renderMarkdown(quote.join('\n'))
      inner.headings.forEach(item => headings.push(item))
      output.push(`<blockquote>${inner.html}</blockquote>`)
      continue
    }

    // 表格
    if (trimmed.includes('|') && index + 1 < lines.length && isDelimiterRow(lines[index + 1])) {
      flushParagraph()
      const headerCells = splitTableRow(trimmed)
      const alignments = splitTableRow(lines[index + 1]).map(cell => {
        const left = cell.startsWith(':')
        const right = cell.endsWith(':')
        if (left && right) return 'center'
        if (right) return 'right'
        if (left) return 'left'
        return ''
      })
      index += 2

      const rows: string[][] = []
      while (index < lines.length && lines[index].trim().includes('|') && lines[index].trim()) {
        rows.push(splitTableRow(lines[index]))
        index++
      }

      const alignAttr = (i: number) => (alignments[i] ? ` style="text-align:${alignments[i]}"` : '')
      let table = '<table><thead><tr>'
      headerCells.forEach((cell, i) => {
        table += `<th${alignAttr(i)}>${renderInline(cell)}</th>`
      })
      table += '</tr></thead><tbody>'
      rows.forEach(row => {
        table += '<tr>'
        for (let i = 0; i < headerCells.length; i++) {
          table += `<td${alignAttr(i)}>${renderInline(row[i] ?? '')}</td>`
        }
        table += '</tr>'
      })
      table += '</tbody></table>'
      output.push(table)
      continue
    }

    // 无序列表
    if (/^[-*+]\s+/.test(trimmed)) {
      flushParagraph()
      const items: string[] = []
      while (index < lines.length && /^[-*+]\s+/.test(lines[index].trim())) {
        items.push(lines[index].trim().replace(/^[-*+]\s+/, ''))
        index++
      }
      output.push(`<ul>${items.map(item => `<li>${renderInline(item)}</li>`).join('')}</ul>`)
      continue
    }

    // 有序列表
    if (/^\d+[.)]\s+/.test(trimmed)) {
      flushParagraph()
      const items: string[] = []
      while (index < lines.length && /^\d+[.)]\s+/.test(lines[index].trim())) {
        items.push(lines[index].trim().replace(/^\d+[.)]\s+/, ''))
        index++
      }
      output.push(`<ol>${items.map(item => `<li>${renderInline(item)}</li>`).join('')}</ol>`)
      continue
    }

    // 普通段落行
    paragraph.push(trimmed)
    index++
  }

  flushParagraph()
  return { html: output.join('\n'), headings }
}
