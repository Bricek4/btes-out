<script setup lang="ts">
import { ArrowUpRight, Clock3, Ellipsis, Pause, Play, XCircle } from 'lucide-vue-next'
import type { Task, TaskStatus } from '../types'

defineProps<{ tasks: Task[] }>()
const emit = defineEmits<{ open: [task: Task]; action: [task: Task, action: 'pause' | 'resume' | 'cancel'] }>()

const labels: Record<TaskStatus, string> = {
  QUEUED: '排队中', RUNNING: '运行中', PAUSED: '已暂停', WAITING_FOR_APPROVAL: '待确认', SUCCEEDED: '已完成', FAILED: '失败', CANCELED: '已取消',
}
const typeLabels: Record<Task['type'], string> = { PROJECT_DOCS: '项目文档', USER_GUIDE: '用户手册', HTML: 'HTML 页面', SCREENSHOT: '自动截图' }
function relative(value: string) {
  const date = new Date(value).getTime()
  if (!Number.isFinite(date)) return '刚刚'
  const minutes = Math.max(0, Math.round((Date.now() - date) / 60000))
  return minutes < 1 ? '刚刚' : minutes < 60 ? `${minutes} 分钟前` : `${Math.round(minutes / 60)} 小时前`
}
</script>

<template>
  <div class="task-table-wrap">
    <table class="task-table">
      <thead><tr><th>任务</th><th>状态</th><th>更新时间</th><th class="task-table__actions" /></tr></thead>
      <tbody>
        <tr v-for="task in tasks" :key="task.taskId" class="task-row" @click="emit('open', task)">
          <td><div class="task-name"><div class="task-type-dot" :class="`task-type-dot--${task.type.toLowerCase()}`" /><div><strong>{{ typeLabels[task.type] }}</strong><span>{{ task.taskId.slice(0, 8) }} · {{ task.projectId.slice(0, 8) }}</span></div></div></td>
          <td><span class="status-pill" :class="`status-pill--${task.status.toLowerCase()}`"><span class="status-pill__dot" />{{ labels[task.status] }}</span></td>
          <td><span class="task-time"><Clock3 :size="14" />{{ relative(task.updatedAt) }}</span></td>
          <td class="task-table__actions"><button class="icon-button" type="button" aria-label="打开任务详情" @click.stop="emit('open', task)"><ArrowUpRight :size="16" /></button><button class="icon-button" type="button" aria-label="任务操作" @click.stop><Ellipsis :size="17" /></button></td>
        </tr>
        <tr v-if="tasks.length === 0"><td colspan="4"><div class="table-empty"><div class="table-empty__icon"><Clock3 :size="20" /></div><strong>还没有任务</strong><span>从右上角发起一个工作流，结果会显示在这里</span></div></td></tr>
      </tbody>
    </table>
  </div>
</template>
