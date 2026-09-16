<script setup lang="ts">
import { ArrowDownRight, ArrowUpRight, Blocks, CircleAlert, CircleCheck, LoaderCircle } from '../lib/icons'

withDefaults(defineProps<{
  label: string
  value: string | number
  helper: string
  trend?: string
  direction?: 'up' | 'down' | 'neutral'
  kind?: 'running' | 'completed' | 'approval' | 'template'
}>(), { direction: 'neutral' })
</script>

<template>
  <article class="metric-card">
    <div class="metric-card__top"><span>{{ label }}</span><span class="metric-card__icon" :class="`metric-card__icon--${kind ?? 'running'}`"><LoaderCircle v-if="kind === 'running'" :size="15" /><CircleCheck v-else-if="kind === 'completed'" :size="15" /><CircleAlert v-else-if="kind === 'approval'" :size="15" /><Blocks v-else :size="15" /></span></div>
    <div class="metric-card__value">{{ value }}</div>
    <div class="metric-card__footer">
      <span v-if="trend" class="trend" :class="`trend--${direction}`">
        <ArrowUpRight v-if="direction === 'up'" :size="13" /><ArrowDownRight v-if="direction === 'down'" :size="13" />{{ trend }}
      </span>
      <span>{{ helper }}</span>
    </div>
  </article>
</template>
