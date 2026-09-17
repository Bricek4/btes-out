import type { TaskStatus } from '../types'

export interface TaskProgressEvent {
  status: TaskStatus | string
  progress?: number | null
  message?: string | null
}
export function taskProgress(status: TaskStatus | string, events: readonly TaskProgressEvent[]): number {
  if (status === 'SUCCEEDED') return 100
  const reported = [...events].reverse().find((event) => typeof event.progress === 'number')?.progress
  if (typeof reported === 'number') return Math.max(0, Math.min(100, reported))
  if (status === 'RUNNING') return 5
  if (status === 'WAITING_FOR_APPROVAL') return 80
  return 0
}

export function translateTaskEvent(event: TaskProgressEvent): string {
  const message = event.message?.trim() ?? ''
  const analyzed = /^Analyzed (.+) chunk (\d+)$/.exec(message)
  if (analyzed) return `正在分析：${analyzed[1]} · 分块 ${analyzed[2]}`
  if (message === 'Task queued') return '任务已排队'
  if (message === 'WORKER RUNNING') return 'Worker 执行中'
  if (message === 'WORKER SUCCEEDED') return 'Worker 已完成'
  if (message === 'WORKER FAILED') return 'Worker 失败'
  if (message === 'WORKER CANCELED') return 'Worker 已取消'
  if (message) return message
  return event.status === 'RUNNING' ? '任务执行中' : event.status === 'SUCCEEDED' ? '任务已完成' : event.status === 'FAILED' ? '任务失败' : '任务状态已更新'
}

export function taskStageLabel(status: TaskStatus | string, events: readonly TaskProgressEvent[]): string {
  const latest = events.at(-1)
  if (latest) return translateTaskEvent(latest)
  return status === 'QUEUED' ? '等待 Worker 接收' : status === 'RUNNING' ? '任务执行中' : status === 'WAITING_FOR_APPROVAL' ? '等待人工确认' : status === 'SUCCEEDED' ? '任务已完成' : status === 'FAILED' ? '任务失败' : status === 'CANCELED' ? '任务已取消' : '任务已暂停'
}
