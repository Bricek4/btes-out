import type {
  Approval,
  LoginProfile,
  Project,
  Provider,
  Task,
  TaskDraft,
  TaskEvent,
  Template,
} from '../types'

const API_ROOT = import.meta.env.VITE_API_BASE_URL ?? ''
const TOKEN_KEY = 'agent-studio.session'

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message)
  }
}

function token(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const session = token()
  if (session) headers.set('Authorization', `Bearer ${session}`)
  const response = await fetch(`${API_ROOT}${path}`, { ...init, headers })
  const text = await response.text()
  let body: unknown = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = text
  }
  if (!response.ok) {
    const record = body as { code?: string; message?: string } | null
    throw new ApiError(record?.message ?? record?.code ?? `请求失败（${response.status}）`, response.status, record?.code)
  }
  return body as T
}

export const api = {
  getSession(): string | null {
    return token()
  },
  setSession(value: string) {
    localStorage.setItem(TOKEN_KEY, value)
  },
  clearSession() {
    localStorage.removeItem(TOKEN_KEY)
  },
  async login(email: string, password: string) {
    const result = await request<{ token?: string; sessionToken?: string }>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
    const session = result.token ?? result.sessionToken
    if (!session) throw new ApiError('登录响应缺少会话凭证', 502)
    this.setSession(session)
    return result
  },
  async logout() {
    try {
      await request('/api/v1/auth/logout', { method: 'POST' })
    } finally {
      this.clearSession()
    }
  },
  projects: () => request<Project[]>('/api/v1/projects'),
  tasks: () => request<Task[]>('/api/v1/tasks'),
  templates: () => request<Template[]>('/api/v1/templates'),
  providers: () => request<Provider[]>('/api/v1/providers'),
  loginProfiles: () => request<LoginProfile[]>('/api/v1/login-profiles'),
  parseChat: (text: string) => request<TaskDraft>('/api/v1/chat/parse', {
    method: 'POST',
    body: JSON.stringify({ text }),
  }),
  createProject: (name: string) => request<Project>('/api/v1/projects', {
    method: 'POST',
    body: JSON.stringify({ name }),
  }),
  createTask: (payload: Record<string, unknown>) => request<Task>('/api/v1/tasks', {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(payload),
  }),
  taskAction: (taskId: string, action: 'pause' | 'resume' | 'cancel') => request<Task>(`/api/v1/tasks/${taskId}/${action}`, {
    method: 'POST',
  }),
  decideApproval: (taskId: string, approvalId: string, decision: string, text = '') => request<void>(
    `/api/v1/tasks/${taskId}/approvals/${approvalId}/decision`,
    { method: 'POST', body: JSON.stringify({ decision, text }) },
  ),
  async taskEvents(taskId: string, after = 0): Promise<TaskEvent[]> {
    return request<TaskEvent[]>(`/api/v1/tasks/${taskId}/events?after=${after}`)
  },
  async createApproval(taskId: string, approval: Omit<Approval, 'id'>) {
    return request<Approval>(`/api/v1/tasks/${taskId}/approvals`, {
      method: 'POST',
      body: JSON.stringify(approval),
    })
  },
  async importZip(projectId: string, file: File) {
    const form = new FormData()
    form.append('file', file)
    const headers = new Headers()
    const session = token()
    if (session) headers.set('Authorization', `Bearer ${session}`)
    const response = await fetch(`${API_ROOT}/api/v1/projects/${projectId}/imports/zip`, { method: 'POST', headers, body: form })
    if (!response.ok) throw new ApiError('ZIP 导入失败', response.status)
    return response.json()
  },
}
