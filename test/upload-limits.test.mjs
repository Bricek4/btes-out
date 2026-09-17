import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

test('proxy and multipart configuration allow the documented ZIP size', () => {
  const application = readFileSync('platform-api/src/main/resources/application.yml', 'utf8')
  const nginx = readFileSync('nginx.conf', 'utf8')

  assert.match(application, /max-file-size:\s*100MB/)
  assert.match(application, /max-request-size:\s*110MB/)
  assert.match(nginx, /client_max_body_size\s+110m/)
})
