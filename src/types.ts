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
  shared?: boolean
}

export interface ProjectRevision {
  id: string
  ordinal: number
  sourceType: 'GIT' | 'ZIP'
  branch?: string | null
  commit?: string | null
  sha256: string
  createdAt: string
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
  latestVersionId?: string | null
}

export interface TemplateVersion {
  id: string
  ordinal: number
  outputFormat: string
  parameterSchema?: unknown
  formLayout?: unknown
  allowedSections?: unknown
  markdownTemplate?: string | null
  htmlTemplate?: string | null
  css?: string | null
  validationRules?: unknown
}

export interface Provider {
  id: string
  name: string
  providerType: string
  baseUrl: string
  maskedApiKey?: string
  credentialConfigured?: boolean
  isDefault?: boolean
}

export interface ProviderModel {
  modelId: string
  displayName: string
  capabilities: string
  defaultModel: boolean
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
  parameters: Record<string, string | number | boolean | null>
  summary: string
  taskReference?: string | null
}

export interface ArtifactNode {
  name: string
  path: string
  type: 'FOLDER' | 'ARTIFACT'
  artifactId?: string | null
  kind?: string | null
  version?: number | null
  mediaType?: string | null
  sizeBytes?: number | null
  sha256?: string | null
  children: ArtifactNode[]
}

export interface ArtifactVersion {
  version: number
  mediaType: string
  sizeBytes: number
  sha256: string
  manifest?: unknown
  verificationReport?: unknown
  createdAt: string
}

export interface ArtifactDetail {
  artifactId: string
  taskId: string
  name: string
  kind: string
  currentVersion: number
  versions: ArtifactVersion[]
}

export interface Member {
  id: string
  email: string
}

export interface ShareGrant {
  id: string
  resourceType: 'PROJECT' | 'ARTIFACT'
  resourceId: string
  member: Member
  access: 'READ'
  createdAt: string
}

export interface Approval {
  id: string
  kind: 'CHOICE' | 'TEXT'
  prompt: string
  choices?: string[]
  expiresAt?: string
  evidenceReference?: string | null
}

export interface TemplateVersionInput {
  outputFormat: 'MARKDOWN' | 'HTML'
  parameterSchema: Record<string, unknown>
  formLayout: Record<string, unknown>
  allowedSections: string[]
  markdownTemplate?: string | null
  htmlTemplate?: string | null
  css?: string | null
  validationRules: Record<string, unknown>
}
