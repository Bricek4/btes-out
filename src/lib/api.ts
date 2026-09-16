import type {
  Approval,
  ArtifactDetail,
  ArtifactNode,
  LoginProfile,
  Member,
  Project,
  ProjectRevision,
  Provider,
  ProviderModel,
  ShareGrant,
  Task,
  TaskDraft,
  TaskEvent,
  Template,
  TemplateVersionInput,
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
    const record = body as { code?: string; message?: string; detail?: string } | null
    throw new ApiError(record?.message ?? record?.detail ?? record?.code ?? `请求失败（${response.status}）`, response.status, record?.code)
  }
  return body as T
}

async function requestText(path: string, init: RequestInit = {}): Promise<string> {
  const headers = new Headers(init.headers)
  const session = token()
  if (session) headers.set('Authorization', `Bearer ${session}`)
  const response = await fetch(`${API_ROOT}${path}`, { ...init, headers })
  const body = await response.text()
  if (!response.ok) {
    let record: { code?: string; message?: string; detail?: string } | null = null
    try { record = JSON.parse(body) } catch { /* plain text error */ }
    throw new ApiError(record?.message ?? record?.detail ?? record?.code ?? `请求失败（${response.status}）`, response.status, record?.code)
  }
  return body
}

async function requestBlob(path: string, init: RequestInit = {}): Promise<Blob> {
  const headers = new Headers(init.headers)
  const session = token()
  if (session) headers.set('Authorization', `Bearer ${session}`)
  const response = await fetch(`${API_ROOT}${path}`, { ...init, headers })
  if (!response.ok) {
    const body = await response.text()
    let record: { code?: string; message?: string; detail?: string } | null = null
    try { record = JSON.parse(body) } catch { /* plain text error */ }
    throw new ApiError(record?.message ?? record?.detail ?? record?.code ?? `请求失败（${response.status}）`, response.status, record?.code)
  }
  return response.blob()
}

function parseTaskEvents(stream: string): TaskEvent[] {
  return stream
    .split(/\r?\n\r?\n/)
    .map((block) => block.split(/\r?\n/).filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trimStart()).join('\n'))
    .filter(Boolean)
    .map((data) => JSON.parse(data) as TaskEvent)
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
    const result = await request<{ accessToken: string; expiresAt: string }>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
    if (!result.accessToken) throw new ApiError('登录响应缺少会话凭证', 502)
    this.setSession(result.accessToken)
    return result
  },
  async setupFirstAdmin(value: { setupToken: string; organizationName: string; email: string; password: string }) {
    const result = await request<{ accessToken: string; expiresAt: string }>('/api/v1/setup/first-admin', {
      method: 'POST',
      body: JSON.stringify(value),
    })
    if (!result.accessToken) throw new ApiError('初始化响应缺少会话凭证', 502)
    this.setSession(result.accessToken)
    return result
  },
  async logout() {
    try {
      await request('/api/v1/auth/logout', { method: 'POST' })
    } finally {
      this.clearSession()
    }
  },
  async projects() {
    const values = await request<Array<Omit<Project, 'projectId'> & { id: string }>>('/api/v1/projects')
    return values.map(({ id, ...project }) => ({ ...project, projectId: id }))
  },
  tasks: () => request<Task[]>('/api/v1/tasks'),
  templates: () => request<Template[]>('/api/v1/templates'),
  async providers() {
    const values = await request<Array<Omit<Provider, 'isDefault'> & { defaultProfile: boolean }>>('/api/v1/providers')
    return values.map(({ defaultProfile, ...provider }) => ({ ...provider, isDefault: defaultProfile }))
  },
  loginProfiles: () => request<LoginProfile[]>('/api/v1/login-profiles'),
  async parseChat(text: string) {
    const result = await request<{ type: TaskDraft['workflowType']; parameters: TaskDraft['parameters'] }>('/api/v1/chat/parse', {
      method: 'POST',
      body: JSON.stringify({ text }),
    })
    return { workflowType: result.type, parameters: result.parameters, summary: text, taskReference: null } satisfies TaskDraft
  },
  async createProject(name: string) {
    const { id, ...project } = await request<Omit<Project, 'projectId'> & { id: string }>('/api/v1/projects', {
      method: 'POST',
      body: JSON.stringify({ name }),
    })
    return { ...project, projectId: id }
  },
  projectRevisions: (projectId: string) => request<ProjectRevision[]>(`/api/v1/projects/${projectId}/revisions`),
  testGit: (projectId: string, value: { url: string; token?: string }) => request<{ ok: boolean; branches: string[] }>(`/api/v1/projects/${projectId}/git/test`, {
    method: 'POST',
    body: JSON.stringify(value),
  }),
  importGit: (projectId: string, value: { url: string; branch?: string; token?: string }) => request<ProjectRevision>(`/api/v1/projects/${projectId}/imports/git`, {
    method: 'POST',
    body: JSON.stringify(value),
  }),
  createTask: (payload: Record<string, unknown>, idempotencyKey: string) => request<Task>('/api/v1/tasks', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
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
    const body = await requestText(`/api/v1/tasks/${taskId}/events`, {
      headers: { Accept: 'text/event-stream', 'Last-Event-ID': String(after) },
    })
    return parseTaskEvents(body)
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
  createTemplate: (name: string, skillId: string, publicTemplate = false) => request<Template>('/api/v1/templates', {
    method: 'POST',
    body: JSON.stringify({ name, skillId, publicTemplate }),
  }),
  createTemplateVersion: (templateId: string, value: TemplateVersionInput) => request<{ id: string; ordinal: number }>(`/api/v1/templates/${templateId}/versions`, {
    method: 'POST',
    body: JSON.stringify(value),
  }),
  copyTemplate: (templateId: string) => request<Template>(`/api/v1/templates/${templateId}/copy`, { method: 'POST' }),
  createProvider: async (value: { name: string; providerType: string; baseUrl: string; apiKey: string; defaultProfile: boolean }) => {
    const result = await request<Omit<Provider, 'isDefault'> & { defaultProfile: boolean }>('/api/v1/providers', {
      method: 'POST',
      body: JSON.stringify({ ...value, options: {} }),
    })
    const { defaultProfile, ...provider } = result
    return { ...provider, isDefault: defaultProfile } satisfies Provider
  },
  providerModels: (providerId: string) => request<ProviderModel[]>(`/api/v1/providers/${providerId}/models`),
  addProviderModel: (providerId: string, value: { modelId: string; displayName: string; defaultModel: boolean }) => request<ProviderModel>(`/api/v1/providers/${providerId}/models`, {
    method: 'POST',
    body: JSON.stringify({ ...value, capabilities: {} }),
  }),
  updateProvider: (providerId: string, value: Partial<{ name: string; providerType: string; baseUrl: string; options: Record<string, unknown>; defaultProfile: boolean }>) => request<Provider>(`/api/v1/providers/${providerId}`, {
    method: 'PATCH',
    body: JSON.stringify(value),
  }),
  rotateProviderCredential: (providerId: string, apiKey: string) => request<void>(`/api/v1/providers/${providerId}/credentials/rotate`, {
    method: 'POST',
    body: JSON.stringify({ apiKey }),
  }),
  testProvider: (providerId: string) => request<{ ok: boolean; statusCode: number; latencyMillis: number; modelCount: number; code?: string }>(`/api/v1/providers/${providerId}/test`, { method: 'POST' }),
  discoverProviderModels: (providerId: string) => request<Array<{ modelId: string; displayName: string }>>(`/api/v1/providers/${providerId}/models/discover`, { method: 'POST' }),
  deleteProviderModel: (providerId: string, modelId: string) => request<void>(`/api/v1/providers/${providerId}/models/${encodeURIComponent(modelId)}`, { method: 'DELETE' }),
  taskArtifacts: (taskId: string) => request<ArtifactNode[]>(`/api/v1/tasks/${taskId}/artifacts`),
  artifact: (artifactId: string) => request<ArtifactDetail>(`/api/v1/artifacts/${artifactId}`),
  previewArtifact: (artifactId: string, version?: number | null) => requestBlob(`/api/v1/artifacts/${artifactId}/preview${version ? `?version=${version}` : ''}`),
  downloadArtifact: (artifactId: string, version?: number | null) => requestBlob(`/api/v1/artifacts/${artifactId}/download${version ? `?version=${version}` : ''}`),
  exportTaskArtifacts: (taskId: string) => requestBlob(`/api/v1/tasks/${taskId}/artifacts/export.zip`),
  members: (query: string) => request<Member[]>(`/api/v1/members?query=${encodeURIComponent(query)}`),
  shares: (resourceType: 'PROJECT' | 'ARTIFACT', resourceId: string) => request<ShareGrant[]>(`/api/v1/shares?resourceType=${resourceType}&resourceId=${encodeURIComponent(resourceId)}`),
  createShare: (memberId: string, resourceType: 'PROJECT' | 'ARTIFACT', resourceId: string) => request<ShareGrant>('/api/v1/shares', {
    method: 'POST',
    body: JSON.stringify({ memberId, resourceType, resourceId }),
  }),
  deleteShare: (shareId: string) => request<void>(`/api/v1/shares/${shareId}`, { method: 'DELETE' }),
  createLoginProfile: (value: {
    reference: string
    name: string
    loginUrl: string
    loginPath?: string
    usernameLocator: LoginProfile['usernameLocator']
    passwordLocator: LoginProfile['passwordLocator']
    submitLocator: LoginProfile['submitLocator']
    username: string
    password: string
  }) => request<LoginProfile>('/api/v1/login-profiles', { method: 'POST', body: JSON.stringify(value) }),
}
