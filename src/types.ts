export type TaskType = 'PROJECT_DOCS' | 'USER_GUIDE' | 'HTML' | 'SCREENSHOT'
export type TaskStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'PAUSED'
  | 'WAITING_FOR_APPROVAL'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELED'

export interface Project {
  projectId: string
  name: string
  ownerId?: string
  createdAt?: string
  hasRevision?: boolean
}

export interface Task {
  taskId: string
  projectId: string
  type: TaskType
  status: TaskStatus
  createdAt: string
  updatedAt: string
  resultReference?: string | null
  failureCode?: string | null
}

export interface TaskEvent {
  sequence: number
  status: TaskStatus
  type: string
  progress?: number | null
  message?: string | null
  occurredAt?: string
}

export interface Template {
  id: string
  name: string
  skillId: string
  visibility: 'PUBLIC' | 'PERSONAL'
  latestVersion?: number | null
}

export interface Provider {
  id: string
  name: string
  providerType: string
  baseUrl: string
  maskedApiKey?: string
  isDefault?: boolean
}

export interface LoginLocator {
  kind: 'role' | 'label' | 'test-id'
  role?: string | null
  name: string
}

export interface LoginProfile {
  id: string
  reference: string
  name: string
  loginUrl: string
  loginPath?: string
  usernameLocator: LoginLocator
  passwordLocator: LoginLocator
  submitLocator: LoginLocator
}

export interface TaskDraft {
  workflowType: TaskType
  parameters: Record<string, string>
  summary: string
  taskReference?: string | null
}

export interface Artifact {
  id: string
  name: string
  kind: string
  mediaType: string
  sizeBytes?: number
  version?: number
  previewUrl?: string
}

export interface Approval {
  id: string
  kind: 'CHOICE' | 'TEXT'
  prompt: string
  choices?: string[]
  expiresAt?: string
}
