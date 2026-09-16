<script setup lang="ts">
import { ArrowDownRight, ArrowUpRight } from 'lucide-vue-next'

withDefaults(defineProps<{
  label: string
  value: string | number
  helper: string
  trend?: string
  direction?: 'up' | 'down' | 'neutral'
}>(), { direction: 'neutral' })
</script>

<template>
  <article class="metric-card">
    <div class="metric-card__top"><span>{{ label }}</span><span class="metric-card__spark" /></div>
    <div class="metric-card__value">{{ value }}</div>
    <div class="metric-card__footer">
      <span v-if="trend" class="trend" :class="`trend--${direction}`">
        <ArrowUpRight v-if="direction === 'up'" :size="13" /><ArrowDownRight v-if="direction === 'down'" :size="13" />{{ trend }}
      </span>
      <span>{{ helper }}</span>
    </div>
  </article>
</template>
