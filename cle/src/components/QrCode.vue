<script setup lang="ts">
import { computed } from 'vue'
import { qrSvg } from '@/vendor/qr'
const props = defineProps<{ value: string }>()
const source = computed(() => `data:image/svg+xml;charset=utf-8,${encodeURIComponent(qrSvg(props.value))}`)
</script>
<template><img class="local-qr" :src="source" alt="本地支付二维码" /></template>
