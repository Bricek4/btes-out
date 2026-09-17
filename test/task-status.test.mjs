import test from 'node:test'
import assert from 'node:assert/strict'
import { isTerminalTaskStatus, mergeTaskSnapshot } from '../src/lib/task-status.ts'

const queuedTask = {
  taskId: 'task-1',
  projectId: 'project-1',
  type: 'PROJECT_DOCS',
  status: 'QUEUED',
  createdAt: '2026-09-17T03:48:05Z',
  updatedAt: '2026-09-17T03:48:05Z',
}

test('recognizes terminal task statuses', () => {
  assert.equal(isTerminalTaskStatus('SUCCEEDED'), true)
  assert.equal(isTerminalTaskStatus('FAILED'), true)
  assert.equal(isTerminalTaskStatus('CANCELED'), true)
  assert.equal(isTerminalTaskStatus('QUEUED'), false)
  assert.equal(isTerminalTaskStatus('WAITING_FOR_APPROVAL'), false)
})

test('replaces a stale task snapshot without duplicating the task row', () => {
  const failedTask = {
    ...queuedTask,
    status: 'FAILED',
    failureCode: 'SOURCE_ARCHIVE_TOO_MANY_ENTRIES',
    updatedAt: '2026-09-17T03:48:08Z',
  }

  assert.deepEqual(mergeTaskSnapshot([queuedTask], failedTask), [failedTask])
})

