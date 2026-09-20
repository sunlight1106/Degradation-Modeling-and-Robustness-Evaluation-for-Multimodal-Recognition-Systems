<script setup lang="ts">
/**
 * 右下角常驻圆形入口徽章。
 * 视觉参照弹丸论破官网的黄色圆环按钮：旋转黄虚线环 + 黑色网点圆盘 + 高对比黄字。
 * 未登录显示"登录 / CLICK"，已登录显示用户名与"进入空间"。
 * 素材与配色均为代码绘制，不含第三方图片。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { authStore } from '@/stores/auth'

const router = useRouter()
const user = computed(() => authStore.state.user)
const mainLabel = computed(() => user.value ? user.value.displayName : '登录')
const subLabel = computed(() => user.value ? '进入空间' : 'CLICK')

function go() {
  router.push(user.value ? '/app/home' : '/login')
}
</script>

<template>
  <button
    class="feb"
    :class="{ 'feb--user': !!user }"
    type="button"
    @click="go"
    :aria-label="user ? `进入个人空间，当前账号 ${user.displayName}` : '前往登录'"
    :title="user ? `进入个人空间（${user.displayName}）` : '登录'"
  >
    <span class="feb-ring" aria-hidden="true" />
    <span class="feb-halo" aria-hidden="true" />
    <span class="feb-disc" aria-hidden="true" />
    <span class="feb-core">
      <strong>{{ mainLabel }}</strong>
      <small>{{ subLabel }}</small>
    </span>
  </button>
</template>

<style scoped>
.feb {
  position: fixed; right: 26px; bottom: 26px; z-index: 95;
  width: 118px; height: 118px; padding: 0; border: 0; background: transparent;
  cursor: pointer; isolation: isolate;
  transition: transform .22s cubic-bezier(.2, .7, .2, 1);
}
.feb:hover { transform: scale(1.06) rotate(1.5deg); }
.feb:active { transform: scale(.98); }
.feb:focus-visible { outline: 3px solid #ffd21e; outline-offset: 4px; border-radius: 50%; }

/* 外圈：旋转黄色虚线环 */
.feb-ring {
  position: absolute; inset: 0; border-radius: 50%;
  background: repeating-conic-gradient(from 0deg, #ffd21e 0deg 9deg, transparent 9deg 13deg);
  -webkit-mask-image: radial-gradient(circle, transparent 66%, #000 67%);
  mask-image: radial-gradient(circle, transparent 66%, #000 67%);
  animation: febSpin 16s linear infinite;
  filter: drop-shadow(0 0 10px rgba(255, 210, 30, .55));
}
/* 内侧细白环 */
.feb-halo {
  position: absolute; inset: 9px; border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, .85);
}
/* 黑色网点圆盘 */
.feb-disc {
  position: absolute; inset: 13px; border-radius: 50%;
  background:
    radial-gradient(rgba(255, 255, 255, .12) 1px, transparent 1px) 0 0 / 6px 6px,
    radial-gradient(circle at 32% 26%, #23292b 0%, #101416 62%, #0a0d0e 100%);
  box-shadow: 0 10px 28px rgba(0, 0, 0, .45), inset 0 1px 0 rgba(255, 255, 255, .14);
}
/* 中央文字 */
.feb-core {
  position: absolute; inset: 0; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 3px;
}
.feb-core strong {
  max-width: 80%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: #ffd21e; font-size: 21px; font-weight: 900; letter-spacing: .06em;
  text-shadow: 2px 2px 0 rgba(0, 0, 0, .8);
}
.feb--user .feb-core strong { font-size: 17px; }
.feb-core small {
  color: #f2f4f2; font-size: 9px; font-weight: 700; letter-spacing: .22em;
}
.feb:hover .feb-ring { animation-duration: 6s; }

@keyframes febSpin { to { transform: rotate(360deg); } }

@media (prefers-reduced-motion: reduce) {
  .feb-ring { animation: none; }
  .feb:hover { transform: none; }
}
@media (max-width: 620px) {
  .feb { width: 92px; height: 92px; right: 16px; bottom: 16px; }
  .feb-core strong { font-size: 16px; }
  .feb--user .feb-core strong { font-size: 13px; }
}
</style>
