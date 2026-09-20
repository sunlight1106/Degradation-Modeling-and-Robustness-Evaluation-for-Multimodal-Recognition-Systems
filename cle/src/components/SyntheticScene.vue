<script setup lang="ts">
/**
 * 原创合成视觉组件（纯 CSS/SVG 绘制，零外部素材依赖）。
 *
 * 取代原先的 Unsplash 照片：照片是第三方版权素材，且画面内容与平台标注的
 * 识别文本并不对应（英文黄牌配中文苏C文本），属于图文不符的误导性展示。
 * 这里改为代码绘制——画面里的车牌号就是 props 传入的那个，图文严格一致。
 *
 * 视觉语言：品红/黑/白高对比撞色 + 网点纹理 + 倾斜切割 + 硬边偏移阴影，
 * 与平台整体的「审判/证据」母题一致。所有图形均为原创，不含任何第三方素材。
 */
withDefaults(defineProps<{
  /** 车牌识别场景 */
  variant?: 'plate' | 'receipt' | 'video'
  /** 车牌号文本，画面绘制与标注共用同一值，保证图文一致 */
  code?: string
  /** 票据场景的行数 */
  lines?: number
  /** 尺寸档位 */
  scale?: 'sm' | 'md' | 'lg'
  /** 是否绘制扫描线动画 */
  scanning?: boolean
}>(), {
  variant: 'plate',
  code: '苏C88R21',
  lines: 6,
  scale: 'md',
  scanning: false,
})
</script>

<template>
  <div class="syn-art" :class="[`syn-art--${variant}`, `syn-art--${scale}`]" role="img"
       :aria-label="variant === 'plate' ? `合成车牌场景：${code}` : variant === 'receipt' ? '合成票据场景' : '合成视频场景'">
    <!-- 共用底纹：夜场景 + 网点 -->
    <span class="syn-sky" aria-hidden="true" />
    <span class="syn-dots" aria-hidden="true" />
    <span class="syn-cut" aria-hidden="true" />

    <!-- 车牌场景 -->
    <template v-if="variant === 'plate'">
      <span class="syn-road" aria-hidden="true" />
      <span class="syn-glow" aria-hidden="true" />
      <span class="syn-car" aria-hidden="true" />
      <span class="syn-plate" aria-hidden="true">
        <i class="syn-plate-province">{{ code.slice(0, 1) }}</i><b>{{ code.slice(1) }}</b>
      </span>
    </template>

    <!-- 票据场景 -->
    <template v-else-if="variant === 'receipt'">
      <span class="syn-paper" aria-hidden="true">
        <i v-for="n in lines" :key="n" class="syn-line" :style="{ width: `${38 + ((n * 27) % 52)}%` }" />
        <b class="syn-total">¥ 128.00</b>
      </span>
    </template>

    <!-- 视频场景 -->
    <template v-else>
      <span class="syn-frame" aria-hidden="true">
        <i /><i /><i /><i />
      </span>
      <span class="syn-wave" aria-hidden="true">
        <i v-for="n in 18" :key="n" :style="{ height: `${18 + ((n * 37) % 60)}%` }" />
      </span>
      <span class="syn-play" aria-hidden="true">▶</span>
    </template>

    <span v-if="scanning" class="syn-scan" aria-hidden="true" />
  </div>
</template>

<style scoped>
.syn-art {
  position: relative; width: 100%; height: 100%; overflow: hidden;
  background: #0c0f0e; border-radius: inherit; isolation: isolate;
}
.syn-art--sm { font-size: 10px; }
.syn-art--md { font-size: 14px; }
.syn-art--lg { font-size: 18px; }

/* 夜色渐变底 */
.syn-sky {
  position: absolute; inset: 0; z-index: 0;
  background:
    radial-gradient(90% 70% at 78% 12%, rgba(230, 0, 103, .22), transparent 58%),
    radial-gradient(60% 50% at 16% 28%, rgba(30, 88, 78, .5), transparent 70%),
    linear-gradient(178deg, #131a18 0%, #0c0f0e 62%, #070908 100%);
}

/* 网点纹理（证据颗粒感） */
.syn-dots {
  position: absolute; inset: 0; z-index: 1; opacity: .5;
  background-image: radial-gradient(rgba(255, 255, 255, .16) .6px, transparent .6px);
  background-size: 5px 5px;
}

/* 品红斜切：判定线母题 */
.syn-cut {
  position: absolute; z-index: 5; left: -20%; right: -20%; top: 42%; height: .34em;
  background: #e60067; transform: rotate(-7deg);
  box-shadow: 0 0 1.1em rgba(230, 0, 103, .55);
  opacity: .9;
}

/* ---------- 车牌 ---------- */
.syn-road {
  position: absolute; z-index: 2; inset: 46% 0 0 0;
  background: linear-gradient(180deg, #1a2320 0%, #0a0d0c 82%);
}
.syn-road::after {
  content: ""; position: absolute; left: 50%; top: 12%; bottom: 10%; width: .18em;
  transform: translateX(-50%);
  background: repeating-linear-gradient(180deg, rgba(220, 228, 222, .5) 0 .7em, transparent .7em 1.5em);
}
.syn-glow {
  position: absolute; z-index: 2; left: 12%; right: 12%; top: 34%; height: 26%;
  background: radial-gradient(50% 60% at 50% 50%, rgba(230, 0, 103, .2), transparent 74%);
  filter: blur(.35em);
}
.syn-car {
  position: absolute; z-index: 3; left: 24%; right: 24%; top: 26%; bottom: 14%;
  background: linear-gradient(180deg, #1d2725 0%, #101614 100%);
  border-radius: .9em .9em .3em .3em;
  box-shadow: inset 0 .1em 0 rgba(255, 255, 255, .07);
}
.syn-car::before {
  content: ""; position: absolute; left: 9%; right: 9%; top: -32%; height: 38%;
  background: #161e1c; border-radius: .7em .7em 0 0;
}
.syn-plate {
  position: absolute; z-index: 4; left: 50%; bottom: 18%; transform: translateX(-50%);
  display: inline-flex; align-items: center; gap: .28em;
  padding: .4em .62em; border: .16em solid #fff; border-radius: .3em;
  background: linear-gradient(180deg, #1e63b0, #14447e);
  box-shadow: .32em .32em 0 rgba(230, 0, 103, .75), 0 .5em 1.4em rgba(0, 0, 0, .6);
}
.syn-plate i {
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 1.15em; font-weight: 700; font-style: normal; color: #fff; line-height: 1;
}
.syn-plate b {
  font-family: "SF Mono", Consolas, monospace;
  font-size: 1.2em; font-weight: 700; letter-spacing: .06em; color: #fff; line-height: 1;
}

/* ---------- 票据 ---------- */
.syn-paper {
  position: absolute; z-index: 3; left: 22%; right: 22%; top: 12%; bottom: 12%;
  padding: .9em .8em; display: flex; flex-direction: column; gap: .42em;
  background: #f4f1ea; border-radius: .18em;
  box-shadow: .4em .4em 0 rgba(230, 0, 103, .8), 0 .8em 2em rgba(0, 0, 0, .55);
  transform: rotate(-2.5deg);
}
.syn-line { height: .28em; border-radius: .14em; background: #b9b3a6; }
.syn-line:nth-child(3n) { background: #d3cec2; }
.syn-total {
  margin-top: auto; align-self: flex-end;
  font-family: "SF Mono", Consolas, monospace; font-size: .82em; font-weight: 700; color: #14181a;
}

/* ---------- 视频 ---------- */
.syn-frame { position: absolute; z-index: 2; inset: 14% 12% 34% 12%; }
.syn-frame i {
  position: absolute; width: 1.1em; height: 1.1em; border: .18em solid rgba(255, 255, 255, .8);
}
.syn-frame i:nth-child(1) { left: 0; top: 0; border-right: 0; border-bottom: 0; }
.syn-frame i:nth-child(2) { right: 0; top: 0; border-left: 0; border-bottom: 0; }
.syn-frame i:nth-child(3) { left: 0; bottom: 0; border-right: 0; border-top: 0; }
.syn-frame i:nth-child(4) { right: 0; bottom: 0; border-left: 0; border-top: 0; }
.syn-wave {
  position: absolute; z-index: 3; left: 12%; right: 12%; bottom: 14%; height: 18%;
  display: flex; align-items: flex-end; gap: .18em;
}
.syn-wave i { flex: 1; background: linear-gradient(180deg, #e60067, rgba(230, 0, 103, .35)); border-radius: .1em .1em 0 0; }
.syn-play {
  position: absolute; z-index: 4; left: 50%; top: 38%; transform: translate(-50%, -50%);
  width: 2.6em; height: 2.6em; display: grid; place-items: center;
  border-radius: 50%; background: rgba(12, 15, 14, .72); color: #fff;
  font-size: .9em; border: .12em solid rgba(255, 255, 255, .75);
}

/* ---------- 扫描线 ---------- */
.syn-scan {
  position: absolute; z-index: 6; left: 4%; right: 4%; height: .1em;
  background: #9af0c9; box-shadow: 0 0 .8em #9af0c9;
  animation: synScan 3.2s ease-in-out infinite;
}
@keyframes synScan {
  0%, 100% { top: 14%; opacity: .35; }
  50% { top: 82%; opacity: 1; }
}
</style>
