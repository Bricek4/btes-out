<script setup lang="ts">
import { ArrowUpRight, GitBranch, PackageOpen } from 'lucide-vue-next'
import type { Project } from '../types'

defineProps<{ project: Project; accent: string }>()
const emit = defineEmits<{ open: [id: string] }>()
</script>

<template>
  <article class="project-card" @click="emit('open', project.projectId)">
    <div class="project-card__head"><div class="project-card__glyph" :style="{ '--accent': accent }"><PackageOpen :size="19" /></div><span v-if="project.shared" class="visibility-tag visibility-tag--public">已共享</span></div>
    <h3>{{ project.name }}</h3>
    <p class="muted">{{ project.hasRevision ? '最近一次导入已就绪' : '等待导入源码版本' }}</p>
    <div class="project-card__meta"><span><GitBranch :size="14" />{{ project.hasRevision ? 'revision ready' : 'no revision' }}</span><ArrowUpRight :size="15" /></div>
  </article>
</template>
