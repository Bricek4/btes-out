import type { Task, TaskStatus } from '../types'

const TERMINAL_TASK_STATUSES: ReadonlySet<TaskStatus> = new Set(['SUCCEEDED', 'FAILED', 'CANCELED'])

export function isTerminalTaskStatus(status: TaskStatus): boolean {
  return TERMINAL_TASK_STATUSES.has(status)
}

export function mergeTaskSnapshot(tasks: readonly Task[], snapshot: Task): Task[] {
  const index = tasks.findIndex((task) => task.taskId === snapshot.taskId)
  if (index < 0) return [snapshot, ...tasks]
  return tasks.map((task, taskIndex) => taskIndex === index ? snapshot : task)
}

