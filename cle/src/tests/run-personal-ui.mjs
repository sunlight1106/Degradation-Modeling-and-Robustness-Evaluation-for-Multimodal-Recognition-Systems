// npm run test:ui (all HTTP requests are synthetic; no real keys or providers).
// Run one suite: node src/tests/run-personal-ui.mjs docs-ui.mjs
// Optional JSDOM_MODULE=/absolute/path/to/jsdom/lib/api.js overrides the installed runtime.
// Browser version: serve cle and open /src/tests/personal-ui.html.
import path from 'node:path'
import fs from 'node:fs/promises'
import os from 'node:os'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { build } from 'esbuild'
import { parse, compileScript } from '@vue/compiler-sfc'
const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '../..')
const suite = process.argv[2] || 'personal-ui.mjs'
if (!/^[a-z][a-z0-9-]*-ui\.mjs$/.test(suite)) throw new Error('Expected a UI test filename in src/tests')
const { JSDOM } = process.env.JSDOM_MODULE
  ? await import(pathToFileURL(process.env.JSDOM_MODULE).href)
  : await import('jsdom')
const { window } = new JSDOM('', { url: 'http://synthetic.test/', pretendToBeVisual: true })
for (const name of ['window','document','navigator','localStorage','history','location','File','FormData','Element','HTMLElement','HTMLInputElement','SVGElement','Node','Event','StorageEvent','CustomEvent','MutationObserver']) {
  Object.defineProperty(globalThis, name, { value: name === 'window' ? window : window[name], configurable: true })
}
globalThis.fetch = (...args) => window.fetch(...args)
window.matchMedia = () => ({ matches:false,addEventListener(){},removeEventListener(){} })
window.document.body.innerHTML = '<pre id="results">RUNNING</pre><div id="fixture"></div>'
// Unresolved synthetic transports must not keep Node alive after a suite finishes.
const nativeSetTimeout = globalThis.setTimeout, nativeClearTimeout = globalThis.clearTimeout, pendingTimers = new Set()
globalThis.setTimeout = (callback, delay, ...args) => {
  const timer = nativeSetTimeout(() => { pendingTimers.delete(timer); callback(...args) }, delay)
  pendingTimers.add(timer); return timer
}
globalThis.clearTimeout = timer => { pendingTimers.delete(timer); nativeClearTimeout(timer) }
const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'personal-ui-regression-'))
const output = path.join(tempDir, 'bundle.mjs')
try {
await build({
  entryPoints:[path.join(here,suite)], outfile:output, bundle:true, format:'esm', platform:'browser',
  alias:{'@':path.join(root,'src')}, define:{'import.meta.env':'{}','__VUE_OPTIONS_API__':'true','__VUE_PROD_DEVTOOLS__':'false','__VUE_PROD_HYDRATION_MISMATCH_DETAILS__':'false'},
  plugins:[{name:'vue-sfc-test',setup(api){api.onLoad({filter:/\.vue$/},async ({path:filename})=>{
    const text=await fs.readFile(filename,'utf8');const {descriptor}=parse(text,{filename})
    const compiled=compileScript(descriptor,{id:filename,inlineTemplate:true})
    return {contents:compiled.content,loader:'ts',resolveDir:path.dirname(filename)}
  })}}],
})
await import(pathToFileURL(output).href)
const result = window.document.getElementById('results').textContent
console.log(`${suite}: ${result}`)
const passed = window.document.documentElement.dataset.result === 'passed'
if (!passed) process.exitCode=1
} finally {
  for (const timer of pendingTimers) nativeClearTimeout(timer)
  globalThis.setTimeout = nativeSetTimeout; globalThis.clearTimeout = nativeClearTimeout
  window.close()
  await fs.rm(tempDir, { recursive: true, force: true })
}
