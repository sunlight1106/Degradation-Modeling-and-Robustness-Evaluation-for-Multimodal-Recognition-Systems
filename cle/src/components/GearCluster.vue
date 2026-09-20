<script setup lang="ts">
/**
 * 旋转齿轮组（原创绘制，纯 SVG + CSS）。
 *
 * 视觉语言：黄色机械齿轮 + 硬黑描边 + 无渐变，配合平台的品红/黑/白撞色体系。
 * 齿轮为通用机械图形，由代码按齿数生成，不含任何第三方素材或注册角色形象。
 */
withDefaults(defineProps<{
  /** 齿数 */
  teeth?: number
  /** 直径，单位 em */
  size?: number
  /** 转一圈的秒数，越大越慢 */
  duration?: number
  /** 是否逆时针 */
  reverse?: boolean
  /** 主色 */
  color?: string
  /** 描边色 */
  stroke?: string
  /** 是否静止（供 prefers-reduced-motion 使用） */
  still?: boolean
}>(), {
  teeth: 12,
  size: 6,
  duration: 18,
  reverse: false,
  color: '#ffd21e',
  stroke: '#11151a',
  still: false,
})
</script>

<template>
  <svg class="gear" :class="{ 'gear--reverse': reverse, 'gear--still': still }"
       :style="{ width: `${size}em`, height: `${size}em`, '--gear-duration': `${duration}s`, '--gear-color': color, '--gear-stroke': stroke }"
       viewBox="0 0 100 100" aria-hidden="true" focusable="false">
    <g class="gear-spin">
      <!-- 齿：按齿数均分旋转 -->
      <rect v-for="n in teeth" :key="n" x="45.5" y="-4" width="9" height="18" rx="1.5"
            :transform="`rotate(${(360 / teeth) * (n - 1)} 50 50)`"
            fill="var(--gear-color)" stroke="var(--gear-stroke)" stroke-width="3" />
      <!-- 轮体 -->
      <circle cx="50" cy="50" r="34" fill="var(--gear-color)" stroke="var(--gear-stroke)" stroke-width="4" />
      <!-- 轮辐孔 -->
      <circle v-for="n in 6" :key="`h${n}`" :cx="50 + 19 * Math.cos((Math.PI / 3) * (n - 1))"
              :cy="50 + 19 * Math.sin((Math.PI / 3) * (n - 1))" r="5.5"
              fill="#11151a" opacity=".82" />
      <!-- 轴心 -->
      <circle cx="50" cy="50" r="9" fill="#11151a" />
      <circle cx="50" cy="50" r="3.5" fill="var(--gear-color)" />
    </g>
  </svg>
</template>

<style scoped>
.gear { display: block; overflow: visible; }
.gear-spin { transform-origin: 50px 50px; animation: gearSpin var(--gear-duration, 18s) linear infinite; }
.gear--reverse .gear-spin { animation-direction: reverse; }
.gear--still .gear-spin { animation: none; }

@keyframes gearSpin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .gear-spin { animation: none; }
}
</style>
