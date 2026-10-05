import Prism from 'prismjs'
import 'prismjs/components/prism-java'
import 'prismjs/components/prism-python'
import 'prismjs/components/prism-typescript'
import 'prismjs/components/prism-c'
import 'prismjs/components/prism-cpp'
import 'prismjs/components/prism-csharp'
import 'prismjs/components/prism-go'
import 'prismjs/components/prism-rust'
import 'prismjs/components/prism-sql'
import 'prismjs/components/prism-bash'
import 'prismjs/components/prism-json'
import 'prismjs/components/prism-yaml'

Prism.manual = true
export const codeLanguages = [
  ['java', 'Java'], ['python', 'Python'], ['javascript', 'JavaScript'], ['typescript', 'TypeScript'],
  ['c', 'C'], ['cpp', 'C++'], ['csharp', 'C#'], ['go', 'Go'], ['rust', 'Rust'], ['sql', 'SQL'],
  ['bash', 'Shell'], ['html', 'HTML'], ['css', 'CSS'], ['json', 'JSON / JSONC'], ['yaml', 'YAML'],
  ['pseudocode', '伪代码'], ['text', '纯文本'],
] as const
export function normalizeLanguage(value: string): string {
  const aliases: Record<string, string> = { js: 'javascript', ts: 'typescript', py: 'python', sh: 'bash', shell: 'bash', 'c++': 'cpp', cs: 'csharp', 'c#': 'csharp', markup: 'html', jsonc: 'json', yml: 'yaml', plain: 'text', plaintext: 'text' }
  const name = value.trim().toLowerCase()
  return aliases[name] || name || 'text'
}
export interface CodeFence { start: number; end: number; codeStart: number; codeEnd: number; code: string; language: string; fence: string }
/** Offsets are retained so changing one block cannot rewrite other note content. */
export function codeFences(source: string): CodeFence[] {
  const blocks: CodeFence[] = []
  const lines = [...source.matchAll(/[^\n]*(?:\n|$)/g)].filter(match => match[0])
  for (let i = 0; i < lines.length; i++) {
    const first = lines[i]!, opening = /^ {0,3}(`{3,}|~{3,})([^\n]*)\r?\n$/.exec(first[0])
    if (!opening) continue
    const fence = opening[1]!, language = normalizeLanguage(opening[2]!.trim().split(/\s+/)[0] || 'text')
    const closePattern = new RegExp(`^ {0,3}${fence[0]}{${fence.length},}\\s*$`)
    for (let j = i + 1; j < lines.length; j++) {
      const closing = lines[j]!
      if (!closePattern.test(closing[0])) continue
      const codeStart = first.index! + first[0].length
      const codeEnd = closing.index!
      blocks.push({ start: first.index!, end: closing.index! + closing[0].length, codeStart, codeEnd, code: source.slice(codeStart, codeEnd).replace(/\r?\n$/, ''), language, fence })
      i = j; break
    }
  }
  return blocks
}
export function fencedCode(code: string, language: string): string {
  const longest = Math.max(2, ...[...code.matchAll(/`+/g)].map(match => match[0].length))
  const fence = '`'.repeat(longest + 1)
  return `${fence}${language}\n${code}\n${fence}\n`
}
export function replaceFence(source: string, block: CodeFence, code: string, language = block.language): string {
  return source.slice(0, block.start) + fencedCode(code, language) + source.slice(block.end)
}
export const escapeCode = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
/** Lexical same-name highlighting, not cross-scope compiler symbol resolution. */
export function highlightCode(code: string, language: string): string {
  if (code.length > 30000) return escapeCode(code)
  const grammar = Prism.languages[language === 'html' ? 'markup' : language]
  if (!grammar) return escapeCode(code)
  const plain = (text: string, identifiers: boolean) => identifiers
    ? text.split(/([\p{L}_$][\p{L}\p{N}_$]*)/u).map((part, index) => index % 2 ? `<span class="code-symbol" tabindex="0" data-symbol="${escapeCode(part)}">${escapeCode(part)}</span>` : escapeCode(part)).join('')
    : escapeCode(text)
  const render = (value: string | Prism.Token | Array<string | Prism.Token>, identifiers = true): string => {
    if (typeof value === 'string') return plain(value, identifiers)
    if (Array.isArray(value)) return value.map(item => render(item, identifiers)).join('')
    const eligible = identifiers && !/comment|string|char|keyword|boolean|number|regex|tag|attr|url/.test(value.type)
    return `<span class="token ${escapeCode(value.type)}">${render(value.content, eligible)}</span>`
  }
  return render(Prism.tokenize(code, grammar))
}
