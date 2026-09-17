export function apiErrorMessage(status: number, body: unknown, fallback: string): string {
  const record = toRecord(body)
  if (record) {
    for (const key of ['message', 'detail', 'code']) {
      const value = record[key]
      if (typeof value === 'string' && value.trim()) return value.trim()
    }
  }
  return status > 0 ? `请求失败（${status}）` : fallback
}

function toRecord(body: unknown): Record<string, unknown> | null {
  if (body && typeof body === 'object' && !Array.isArray(body)) return body as Record<string, unknown>
  if (typeof body !== 'string' || !body.trim()) return null
  try {
    const parsed: unknown = JSON.parse(body)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, unknown> : null
  } catch {
    return null
  }
}
