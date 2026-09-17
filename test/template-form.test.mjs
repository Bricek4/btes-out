import test from 'node:test'
import assert from 'node:assert/strict'
import { initializeTemplateParameters, mergeTemplateParameters, templateFields } from '../src/lib/template-form.ts'

test('normalizes a template schema into ordered, typed form fields', () => {
  const fields = templateFields({
    type: 'object',
    properties: {
      title: { type: 'string', title: '手册标题', default: '项目手册' },
      maxPages: { type: 'integer', title: '页数上限', default: 12 },
      includeScreenshots: { type: 'boolean', title: '包含截图' },
    },
    required: ['title', 'includeScreenshots'],
  })

  assert.deepEqual(fields, [
    { key: 'title', label: '手册标题', type: 'string', required: true, defaultValue: '项目手册' },
    { key: 'maxPages', label: '页数上限', type: 'number', required: false, defaultValue: 12 },
    { key: 'includeScreenshots', label: '包含截图', type: 'boolean', required: true, defaultValue: null },
  ])
})

test('initializes and merges typed template parameters without dropping draft values', () => {
  const fields = templateFields({
    properties: {
      title: { type: 'string', default: '默认标题' },
      retries: { type: 'number', default: 2 },
      publish: { type: 'boolean', default: false },
    },
    required: ['title'],
  })
  const initial = initializeTemplateParameters(fields, { title: '已有标题', goal: '生成手册' })
  assert.deepEqual(initial, { title: '已有标题', retries: 2, publish: false })

  const merged = mergeTemplateParameters({ goal: '生成手册' }, fields, {
    title: '新标题',
    retries: '4',
    publish: true,
  })
  assert.deepEqual(merged, { goal: '生成手册', title: '新标题', retries: 4, publish: true })
})
