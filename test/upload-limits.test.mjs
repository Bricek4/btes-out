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

test('the HTML entrypoint is revalidated after a frontend deployment', () => {
  const nginx = readFileSync('nginx.conf', 'utf8')

  assert.match(nginx, /location\s*=\s*\/index\.html\s*\{[\s\S]*?add_header\s+Cache-Control\s+"no-cache, no-store, must-revalidate"\s+always;/)
})
