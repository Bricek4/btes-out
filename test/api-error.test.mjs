import test from 'node:test'
import assert from 'node:assert/strict'
import { apiErrorMessage } from '../src/lib/api-error.ts'

test('prefers structured server error details for upload failures', () => {
  assert.equal(apiErrorMessage(404, '{"code":"PROJECT_NOT_FOUND","message":"项目不存在或无权访问"}', 'ZIP 导入失败'), '项目不存在或无权访问')
  assert.equal(apiErrorMessage(400, '{"code":"ZIP_INVALID"}', 'ZIP 导入失败'), 'ZIP_INVALID')
})

test('falls back to a status message for an empty or non-json response', () => {
  assert.equal(apiErrorMessage(413, '', 'ZIP 导入失败'), '请求失败（413）')
  assert.equal(apiErrorMessage(500, 'gateway error', 'ZIP 导入失败'), '请求失败（500）')
})
