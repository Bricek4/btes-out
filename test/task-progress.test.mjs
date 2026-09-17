import test from 'node:test'
import assert from 'node:assert/strict'
import { taskProgress, taskStageLabel, translateTaskEvent } from '../src/lib/task-progress.ts'

test('uses the latest reported percentage while keeping terminal states complete', () => {
  assert.equal(taskProgress('RUNNING', [{ status: 'RUNNING', progress: 42 }]), 42)
  assert.equal(taskProgress('SUCCEEDED', [{ status: 'RUNNING', progress: 42 }]), 100)
  assert.equal(taskProgress('QUEUED', []), 0)
})

test('translates worker progress messages into Chinese display text', () => {
  assert.equal(translateTaskEvent({ status: 'RUNNING', message: 'Analyzed src/App.vue chunk 2' }), '正在分析：src/App.vue · 分块 2')
  assert.equal(translateTaskEvent({ status: 'SUCCEEDED', message: null }), '任务已完成')
  assert.equal(taskStageLabel('RUNNING', [{ status: 'RUNNING', message: 'Analyzed src/App.vue chunk 2' }]), '正在分析：src/App.vue · 分块 2')
})
