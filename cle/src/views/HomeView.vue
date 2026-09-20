<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import HomeExperiment from '@/components/HomeExperiment.vue'
import { authStore } from '@/stores/auth'

const art = '/art/home/'
const root = ref<HTMLElement>()
const menu = ref<HTMLDialogElement>()
const rail = ref<HTMLElement>()
const menuOpen = ref(false)
const ready = ref(false)
const progress = ref(0)
const activeSlide = ref(0)
const reduced = ref(false)
const motionChoice = ref<'play' | 'pause' | null>(null)
const motionDisabled = computed(() => motionChoice.value ? motionChoice.value === 'pause' : reduced.value)
const tv = ref<HTMLElement>()
const tvContent = ref<HTMLElement>()
const about = ref<HTMLElement>()
const gallery = ref<HTMLElement>()
let tvWidth = 850
let tvHeight = 478
let tvResize: ResizeObserver | undefined
const activeSection = ref('top')
const mosaicTiles = ref(560)
const mosaicColumns = ref(28)
const mosaicDelays = computed(() => {
  let seed = 20260808
  return Array.from({ length: mosaicTiles.value }, () => {
    seed = (Math.imul(1664525, seed) + 1013904223) >>> 0
    return seed / 4294967296
  })
})
const enterTarget = computed(() => authStore.state.user ? '/app/home' : '/login')
const slides = [
  { title: '知识的另一种可能', sub: 'KNOWLEDGE / 把好奇心收藏起来', to: '/app/knowledge', image: 'scene-1.webp' },
  { title: '每一个想法，都有后续', sub: 'NOTES / 记录 · 连接 · 再发现', to: '/app/notes', image: 'scene-3.webp' },
  { title: '让证据，回应直觉', sub: 'EXPERIMENT / 基线 × 优化', to: '/app/comparisons', image: 'scene-2.webp' },
  { title: '连接你的思考伙伴', sub: 'MODELS / DeepSeek · Kimi · Qwen', to: '/models', image: 'scene-4.webp' },
]
const navigation = [
  { label: 'TOP', text: '首页', href: '#top' },
  { label: 'OVERVIEW', text: '空间概览', href: '#overview' },
  { label: 'EXPERIMENT', text: '实验现场', href: '#experiment' },
  { label: 'ABOUT', text: '探索日常', href: '#capabilities' },
  { label: 'PRODUCT', text: '全部入口', href: '#product' },
]
const entries = [
  { name: '素材空间', english: 'MEDIA', to: '/app/images', desc: '图片与视频的起点' },
  { name: '知识库', english: 'KNOWLEDGE', to: '/app/knowledge', desc: '让零散知识彼此相遇' },
  { name: '我的笔记', english: 'NOTES', to: '/app/notes', desc: '记录值得继续的想法' },
  { name: '模型中心', english: 'MODELS', to: '/models', desc: '连接不同的思考方式' },
  { name: '优化对比', english: 'COMPARE', to: '/app/comparisons', desc: '同一输入，两条路径' },
  { name: '使用文档', english: 'GUIDE', to: '/docs', desc: '从这里了解你的空间' },
]
let observer: IntersectionObserver | undefined
let videoObserver: IntersectionObserver | undefined
let frame = 0
let safetyTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let oldOverflow = ''
let mediaQuery: MediaQueryList | undefined
let focusBeforeMenu: HTMLElement | null = null
let pinnedSections: HTMLElement[] = []
let navSections: HTMLElement[] = []

function closeMenu() { menu.value?.close() }
function menuClosed() {
  menuOpen.value = false
  menu.value?.querySelector('video')?.pause()
  document.body.style.overflow = oldOverflow
  focusBeforeMenu?.focus({ preventScroll: true })
}
async function toggleMenu() {
  if (menuOpen.value) { closeMenu(); return }
  oldOverflow = document.body.style.overflow
  focusBeforeMenu = document.activeElement as HTMLElement
  menuOpen.value = true
  document.body.style.overflow = 'hidden'
  menu.value?.showModal()
  await nextTick()
  const video = menu.value?.querySelector('video')
  if (video && !motionDisabled.value) {
    if (!video.src) video.src = `${art}sky.mp4`
    void video.play().catch(() => {})
  }
}
function selectSlide(index: number) {
  const card = rail.value?.children[index] as HTMLElement | undefined
  if (!card || !rail.value) return
  rail.value.scrollTo({ left: card.offsetLeft - (rail.value.clientWidth - card.clientWidth) / 2, behavior: motionDisabled.value ? 'instant' : 'smooth' })
}
function trackSlide() {
  if (!rail.value) return
  const center = rail.value.scrollLeft + rail.value.clientWidth / 2
  let nearest = 0, distance = Infinity
  Array.from(rail.value.children).forEach((el, i) => {
    const card = el as HTMLElement
    const d = Math.abs(card.offsetLeft + card.clientWidth / 2 - center)
    if (d < distance) { nearest = i; distance = d }
  })
  activeSlide.value = nearest
}
function updateScroll() {
  frame = 0
  if (!root.value) return
  const disabled = motionDisabled.value
  root.value.style.setProperty('--hero-shift', `${disabled ? 0 : Math.min(window.scrollY * .14, 110)}px`)
  pinnedSections.forEach(section => {
    const rect = section.getBoundingClientRect()
    const p = disabled ? 0 : Math.min(1, Math.max(0, -rect.top / Math.max(1, rect.height - innerHeight)))
    section.style.setProperty('--progress', p.toFixed(3))
    section.classList.toggle('is-transitioning', !disabled && p > .55)
    section.classList.toggle('is-entered', disabled || p > (section.id === 'workflow' ? .32 : .12))
    if (section.id === 'workflow' && gallery.value) {
      const tileHeight = innerHeight / (mosaicTiles.value / mosaicColumns.value)
      section.style.setProperty('--gallery-bottom-row', String(Math.floor((gallery.value.getBoundingClientRect().bottom - Math.max(0, rect.top)) / tileHeight)))
    }
    if (section.id === 'experiment' && tv.value) {
      // Match the reference: 0.9 viewport of growth, followed by a 0.45 viewport hold.
      const zoom = disabled ? 0 : Math.min(1, Math.max(0, -rect.top / (innerHeight * .9)))
      tv.value.style.width = `${tvWidth + ((root.value?.clientWidth ?? innerWidth) - tvWidth) * zoom}px`
      tv.value.style.height = `${tvHeight + (innerHeight - tvHeight) * zoom}px`
      tv.value.style.borderRadius = `${32 * (1 - zoom)}px`
      section.style.setProperty('--zoom', zoom.toFixed(4))
      section.classList.toggle('is-noise', zoom >= .25)
      section.classList.toggle('is-blackout', zoom >= .8)
      about.value?.classList.toggle('is-arrived', disabled || zoom >= .7)
      if (tvContent.value) tvContent.value.inert = zoom > .25
    }
  })
  const current = [...navSections].reverse().find(section => section.getBoundingClientRect().top < innerHeight * .45)
  activeSection.value = current?.id ?? 'top'
  root.value.classList.toggle('is-scrolled', window.scrollY > innerHeight * .6)
}
function queueScroll() { if (!frame) frame = requestAnimationFrame(updateScroll) }
function syncVideos() {
  root.value?.querySelectorAll('video').forEach(video => {
    const visible = video.getBoundingClientRect().bottom > 0 && video.getBoundingClientRect().top < innerHeight
    if (motionDisabled.value || !visible) video.pause()
    else {
      if (!video.src && video.dataset.src) video.src = video.dataset.src
      if (video.src) void video.play().catch(() => {})
    }
  })
  queueScroll()
}
function toggleMotion() {
  motionChoice.value = motionDisabled.value ? 'play' : 'pause'
  try { localStorage.setItem('personal-home-motion', motionChoice.value) } catch { /* Private browsing may disable storage. */ }
  void nextTick().then(() => { measureTV(); syncVideos() })
}
function measureTV() {
  if (!root.value || !tvContent.value) return
  const columns = innerWidth >= 767 ? 28 : 14
  mosaicColumns.value = columns
  const rows = Math.ceil(innerHeight / (root.value.clientWidth / columns))
  mosaicTiles.value = columns * rows
  root.value.style.setProperty('--mosaic-cols', String(columns))
  root.value.style.setProperty('--mosaic-rows', String(rows))
  tvWidth = Math.min(850, root.value.clientWidth * (innerWidth <= 600 ? .936 : .76))
  tvContent.value.style.width = `${tvWidth}px`
  tvHeight = Math.min(innerHeight * .76, Math.max(tvWidth * .565, tvContent.value.scrollHeight))
  root.value.style.setProperty('--tv-base-height', `${tvHeight}px`)
  queueScroll()
}
function preferenceChanged() { reduced.value = !!mediaQuery?.matches; syncVideos() }

onMounted(() => {
  void authStore.ensureUser()
  mediaQuery = matchMedia('(prefers-reduced-motion: reduce)')
  reduced.value = mediaQuery.matches
  try {
    const saved = localStorage.getItem('personal-home-motion')
    if (saved === 'play' || saved === 'pause') motionChoice.value = saved
  } catch { /* Retain the system preference when storage is unavailable. */ }
  pinnedSections = Array.from(root.value?.querySelectorAll<HTMLElement>('[data-pin]') ?? [])
  navSections = navigation.map(item => document.querySelector<HTMLElement>(item.href)).filter((el): el is HTMLElement => !!el)
  mediaQuery.addEventListener('change', preferenceChanged)
  const heroImages = Array.from(root.value?.querySelectorAll<HTMLImageElement>('.dr-keyvisual img') ?? [])
  let loaded = 0
  const done = () => { if (!disposed) { progress.value = 100; ready.value = true } }
  safetyTimer = setTimeout(done, 2400)
  Promise.all(heroImages.map(img => img.decode().catch(() => {}).finally(() => {
    if (!disposed) progress.value = Math.round(++loaded / heroImages.length * 100)
  }))).then(done)
  observer = new IntersectionObserver(items => items.forEach(item => {
    if (item.isIntersecting) { item.target.classList.add('is-in'); observer?.unobserve(item.target) }
  }), { threshold: .08 })
  root.value?.querySelectorAll('.dr-reveal').forEach(el => observer?.observe(el))
  videoObserver = new IntersectionObserver(items => items.forEach(item => {
    const video = item.target as HTMLVideoElement
    if (item.isIntersecting && !motionDisabled.value) {
      if (!video.src && video.dataset.src) video.src = video.dataset.src
      void video.play().catch(() => {})
    } else video.pause()
  }), { threshold: .05 })
  root.value?.querySelectorAll('video[data-src]').forEach(el => videoObserver?.observe(el))
  tvResize = new ResizeObserver(measureTV)
  if (tvContent.value) tvResize.observe(tvContent.value)
  window.addEventListener('resize', measureTV, { passive: true })
  window.addEventListener('scroll', queueScroll, { passive: true })
  window.addEventListener('resize', queueScroll, { passive: true })
  measureTV(); updateScroll()
})
onBeforeUnmount(() => {
  disposed = true
  clearTimeout(safetyTimer)
  observer?.disconnect(); videoObserver?.disconnect()
  tvResize?.disconnect()
  window.removeEventListener('resize', measureTV)
  cancelAnimationFrame(frame)
  window.removeEventListener('scroll', queueScroll)
  window.removeEventListener('resize', queueScroll)
  mediaQuery?.removeEventListener('change', preferenceChanged)
  if (menuOpen.value) document.body.style.overflow = oldOverflow
})
</script>

<template>
  <div ref="root" class="dr-home" :class="{ 'is-ready': ready, 'motion-paused': motionDisabled, 'motion-forced': motionChoice === 'play' }">
    <a class="dr-skip" href="#overview">跳过首屏，查看内容</a>
    <Transition name="dr-load"><div v-if="!ready" class="dr-loading" role="status" aria-label="正在载入首页">
      <div><strong>LOADING</strong><div class="dr-load-row"><i><b :style="{ width: progress + '%' }" /></i><span>{{ progress }}%</span></div><small>STATUS: {{ progress === 100 ? 'READY' : 'INITIALIZING' }} ▮</small></div>
    </div></Transition>
    <header class="dr-header">
      <a href="#top" class="dr-brand" aria-label="个人空间首页"><small>超高校级的</small><strong>个人空间<span>×</span></strong><em>PERSONAL PLATFORM</em></a>
      <div class="dr-header-controls"><button type="button" class="dr-motion" :aria-pressed="motionDisabled" :title="motionDisabled && !motionChoice ? '系统开启了减少动态效果，点击可播放首页动画' : undefined" @click="toggleMotion">{{ motionDisabled ? '播放动画' : '暂停动画' }}</button><RouterLink :to="enterTarget" class="dr-account">{{ authStore.state.user ? '我的空间' : '登录空间' }} <span>⌄</span></RouterLink><button type="button" class="dr-menu-toggle" aria-label="打开导航菜单" aria-controls="home-menu" :aria-expanded="menuOpen" @click="toggleMenu"><i /><i /><i /></button></div>
    </header>
    <dialog id="home-menu" ref="menu" class="dr-menu" aria-label="首页导航菜单" @close="menuClosed">
      <video muted loop playsinline preload="none" :poster="art + 'menu-bg.png'" aria-hidden="true" />
      <div class="dr-menu-grain" aria-hidden="true" /><img class="dr-menu-cylinder" :src="art + 'cylinder.webp'" alt="" /><img class="dr-menu-circle" :src="art + 'menu-circle.webp'" alt="" /><span class="dr-menu-word" aria-hidden="true">MENU</span>
      <button type="button" class="dr-menu-toggle is-close" aria-label="关闭导航菜单" @click="closeMenu"><i /><i /></button>
      <nav><a v-for="(item, i) in navigation" :key="item.href" :href="item.href" :style="{ '--i': i }" :aria-current="activeSection === item.href.slice(1) ? 'location' : undefined" @click="closeMenu"><b>{{ item.label }}</b><small>{{ item.text }}</small></a></nav>
      <p class="dr-menu-foot">YOUR WORLD. YOUR POSSIBILITY.</p>
    </dialog>
    <main>
      <section id="top" class="dr-keyvisual" aria-labelledby="home-title">
        <div class="dr-kv-art" aria-hidden="true">
          <div class="dr-kv-sunset" /><img class="dr-kv-bear" src="/art/monokuma.webp" alt="" fetchpriority="high" />
          <img class="dr-kv-cross" :src="art + 'hero-cross.webp'" alt="" fetchpriority="high" />
          <img class="dr-kv-numbers" :src="art + 'hero-numbers.webp'" alt="" /><img class="dr-kv-hand" :src="art + 'hero-hand.webp'" alt="" />
          <img class="dr-kv-nanami" src="/art/nanami.webp" alt="" /><img class="dr-kv-sonia" :src="art + 'hero-sonia.webp'" alt="" />
          <img class="dr-kv-left" src="/art/hinata.webp" alt="" fetchpriority="high" /><img class="dr-kv-right" src="/art/komaeda.webp" alt="" fetchpriority="high" />
          <img class="dr-kv-cast-left" :src="art + 'hero-left.webp'" alt="" /><img class="dr-kv-cast-right" :src="art + 'hero-right.webp'" alt="" />
          <img class="dr-kv-rabbit" :src="art + 'hero-monomi.webp'" alt="" />
          <span class="dr-kv-shout dr-kv-shout-left">好奇<br>重启</span><span class="dr-kv-shout dr-kv-shout-right">未知<br><small>即将</small>展开</span>
        </div>
        <div class="dr-kv-title">
          <p>WELCOME TO MY PRIVATE WORLD</p>
          <h1 id="home-title"><small>超高校级的</small><span>个人空间<b>×</b></span></h1>
          <div class="dr-kv-subtitle">PERSONAL PLATFORM / KNOWLEDGE × EXPERIMENTS</div>
          <h2>属于你的探索，现在开始</h2>
          <RouterLink :to="enterTarget" class="dr-start">进入我的世界 <span>▶</span></RouterLink>
          <nav class="dr-kv-platforms" aria-label="空间快捷入口"><RouterLink to="/app/knowledge">知识库</RouterLink><RouterLink to="/app/notes">我的笔记</RouterLink><RouterLink to="/models">模型中心</RouterLink><RouterLink to="/app/comparisons">可信评测</RouterLink></nav>
        </div>
        <a href="#overview" class="dr-scroll">SCROLL<span>⌄</span></a>
      </section>
      <section class="dr-trailers dr-reveal" aria-label="精选空间">
        <div ref="rail" class="dr-trailer-rail" @scroll.passive="trackSlide">
          <RouterLink v-for="(slide, i) in slides" :key="slide.to" :to="slide.to" class="dr-trailer" :aria-label="slide.title"><img :src="art + slide.image" alt="" loading="lazy" /><span class="dr-trailer-scan" /><small>0{{ i + 1 }} / {{ slide.sub }}</small><strong>{{ slide.title }}</strong><span class="dr-trailer-play">↗</span></RouterLink>
        </div>
        <div class="dr-carousel-controls"><button aria-label="上一个入口" @click="selectSlide((activeSlide + 3) % 4)">‹</button><button v-for="(_, i) in slides" :key="i" :class="{ active: i === activeSlide }" :aria-label="`查看第 ${i + 1} 个入口`" :aria-pressed="i === activeSlide" @click="selectSlide(i)" /><button aria-label="下一个入口" @click="selectSlide((activeSlide + 1) % 4)">›</button></div>
      </section>
      <section id="overview" class="dr-overview">
        <div class="dr-cylinder-mask" aria-hidden="true"><div class="dr-cylinder"><img v-for="layer in 5" :key="layer" :src="art + (layer === 1 ? 'cylinder.webp' : `cylinder-${layer}.webp`)" alt="" loading="lazy" /></div></div>
        <header class="dr-section-label dr-reveal"><img :src="art + 'overview-title.webp'" alt="OVERVIEW" loading="lazy" /><span>空间概览</span></header>
        <div class="dr-overview-body dr-reveal">
          <div class="dr-orbit dr-orbit-left" aria-label="精选入口"><RouterLink v-for="(entry, i) in entries.slice(0, 3)" :key="entry.to" :to="entry.to"><img :src="art + `overview-left-${i + 1}.png`" alt="" loading="lazy" /><span>{{ entry.name }} / {{ entry.english }}</span></RouterLink></div>
          <div class="dr-overview-copy"><p class="dr-overline">熟悉的日常，全新的可能。</p><h2>你的世界<br><em>即将重启</em></h2><p>把一闪而过的灵感，变成值得收藏的知识。<br>把一个尚未解答的问题，变成可以验证的实验。</p><p>从笔记与素材开始，<br>连接不同的模型，探索不同的处理路径。<br>记录过程，也保留每一次发现。</p><p class="dr-overview-end">好奇，永不毕业。<br><strong>下一场探索，由你揭开序幕！</strong></p></div>
          <div class="dr-orbit dr-orbit-right" aria-label="更多入口"><RouterLink v-for="(entry, i) in entries.slice(3)" :key="entry.to" :to="entry.to"><img :src="art + `overview-right-${i + 1}.png`" alt="" loading="lazy" /><span>{{ entry.name }} / {{ entry.english }}</span></RouterLink></div>
        </div>
        <div id="experiment" class="dr-experiment-pin" data-pin><div class="dr-experiment-sticky"><div class="dr-experiment-caption"><span>LIVE EXPERIMENT</span><h3>直觉之外，让证据说话。</h3><p>同一份输入，从质量诊断到结果对比。</p></div><div ref="tv" class="dr-tv"><div ref="tvContent" class="dr-tv-content"><HomeExperiment /></div><div class="dr-tv-noise" aria-hidden="true"><div /></div></div><a href="#capabilities" class="dr-experiment-next">继续探索 ↓</a></div></div>
      </section>
      <section id="capabilities" ref="about" class="dr-about">
        <div class="dr-beach"><video :data-src="art + 'beach.mp4'" :poster="art + 'beach.png'" muted loop playsinline preload="none" aria-hidden="true" /><div class="dr-scanlines" aria-hidden="true" /><div class="dr-beach-front" aria-hidden="true" />
          <header class="dr-section-label dr-reveal"><img :src="art + 'about-title.webp'" alt="ABOUT" loading="lazy" /><span>探索日常</span></header>
          <div class="dr-beach-copy dr-reveal"><h2><span>在自己的世界里，</span><br><span>展开<em>无限可能</em>的日常！</span></h2><p>一些还没整理的想法，几张舍不得删的图片，<br>一个始终想弄明白的问题。</p><p>把它们带到这里。<br>记录、连接、尝试，然后发现新的线索。<br>每一次微小的好奇，都值得认真对待。</p><RouterLink to="/app/notes">开始记录我的想法 <span>↗</span></RouterLink></div>
          <img class="dr-beach-rabbit" :src="art + 'beach-monomi.webp'" alt="" loading="lazy" aria-hidden="true" />
        </div>
        <div ref="gallery" class="dr-gallery" aria-label="空间功能画廊"><div v-for="row in 7" :key="row" class="dr-gallery-row"><div v-for="copy in 2" :key="copy" class="dr-gallery-group" :aria-hidden="copy === 2 ? true : undefined"><RouterLink v-for="(entry, i) in entries" :key="entry.to" :to="entry.to" :tabindex="copy === 2 ? -1 : undefined"><img :src="art + `scene-${(i + row) % 5 + 1}.webp`" alt="" loading="lazy" /><span>{{ entry.name }}<small>{{ entry.english }}</small></span></RouterLink></div></div></div>
      </section>
      <section id="workflow" class="dr-verdict" data-pin><div class="dr-verdict-sticky"><div class="dr-verdict-background" aria-hidden="true" /><div class="dr-verdict-bear" aria-hidden="true" /><div class="dr-verdict-mosaic" aria-hidden="true"><i v-for="(delay, tile) in mosaicDelays" :key="tile" :style="{ '--delay': delay, '--row': Math.floor(tile / mosaicColumns) }" /></div><div class="dr-verdict-copy dr-reveal"><p>不过，一个漂亮的结果，<mark>还不是答案。</mark><br>当直觉遇到疑问，就让证据接管现场。</p><h2>从现在开始<br><strong>「属于你的真相追寻」！</strong></h2><p>上传素材，选择模型，运行实验。<br>在同一份输入下，比较基线与优化结果。<br>保留上下文，追踪每一次变化。</p><RouterLink to="/app/comparisons">进入实验现场 <span>↗</span></RouterLink></div><div class="dr-caution dr-caution-one" aria-hidden="true"><span v-for="i in 8" :key="i">KEEP OUT ◆ PRIVATE WORLD ◆ </span></div><div class="dr-caution dr-caution-two" aria-hidden="true"><span v-for="i in 8" :key="i">KEEP OUT ◆ PRIVATE WORLD ◆ </span></div></div></section>
      <section id="product" class="dr-product"><div class="dr-product-word" aria-hidden="true">PERSONAL PLATFORM</div><header class="dr-section-label dr-reveal"><img :src="art + 'product-title.webp'" alt="PRODUCT" loading="lazy" /><span>我的私人空间</span></header>
        <div class="dr-product-panel dr-reveal"><div class="dr-product-cover"><img src="/art/nanami.webp" alt="" loading="lazy" /><div><small>YOUR PRIVATE WORLD</small><strong>个人空间<span>×</span></strong><p>KNOWLEDGE × EXPERIMENTS</p></div></div><dl><div><dt>空间名称</dt><dd>Personal Platform · 个人空间</dd></div><div><dt>探索内容</dt><dd>知识库 / 笔记 / 素材 / 模型 / 实验</dd></div><div><dt>思考伙伴</dt><dd>DeepSeek · Kimi · Qwen</dd></div><div><dt>使用方式</dt><dd>登录后开启你的探索</dd></div><div><dt>探索人数</dt><dd>1 人，也有无限可能</dd></div></dl><RouterLink :to="enterTarget" class="dr-product-enter">进入我的世界 <span>▶</span></RouterLink></div>
        <div class="dr-entry-grid dr-reveal"><RouterLink v-for="(entry, i) in entries" :key="entry.to" :to="entry.to"><span>0{{ i + 1 }}</span><div><small>{{ entry.english }}</small><strong>{{ entry.name }}</strong><p>{{ entry.desc }}</p></div><b>↗</b></RouterLink></div>
        <a href="#top" class="dr-back-top">↑<span>PAGE TOP</span></a>
      </section>
    </main>
    <footer class="dr-footer"><strong>PERSONAL PLATFORM <span>×</span></strong><p>好奇，永不毕业。</p><nav><RouterLink to="/docs">使用文档</RouterLink><RouterLink to="/models">模型中心</RouterLink><RouterLink :to="enterTarget">我的空间</RouterLink></nav><small>PRIVATE SPACE / INFINITE POSSIBILITIES</small><small class="dr-art-credit">Fan-style personal homepage · Character artwork © Spike Chunsoft / respective rights holders.</small></footer>
  </div>
</template>
