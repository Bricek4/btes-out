<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ArrowRight,
  ArrowUpRight,
  Archive,
  Check,
  ChevronDown,
  CircleHelp,
  Code2,
  Download,
  Eye,
  FileCode2,
  FileText,
  FolderPlus,
  Github,
  Image,
  LoaderCircle,
  LockKeyhole,
  LogOut,
  Menu,
  Plus,
  RefreshCw,
  Search,
  Send,
  Share2,
  Settings2,
  ShieldCheck,
  Sparkles,
  Upload,
  UserRound,
  X,
} from 'lucide-vue-next'
import SidebarNav from './components/SidebarNav.vue'
import MetricCard from './components/MetricCard.vue'
import ProjectCard from './components/ProjectCard.vue'
import TaskTable from './components/TaskTable.vue'
import ConfigManagerModal from './components/ConfigManagerModal.vue'
import { api, ApiError } from './lib/api'
import type { ArtifactNode, Member, Project, ShareGrant, Task, TaskDraft, TaskEvent, Template, Provider, ProviderModel, LoginProfile, TaskStatus } from './types'

const activeSection = ref('overview')
const sidebarCollapsed = ref(false)
const loading = ref(false)
const refreshing = ref(false)
const errorMessage = ref('')
const toastMessage = ref('')
const projects = ref<Project[]>([])
const tasks = ref<Task[]>([])
const templates = ref<Template[]>([])
const providers = ref<Provider[]>([])
const providerBusyId = ref('')
const profiles = ref<LoginProfile[]>([])
const selectedTask = ref<Task | null>(null)
const selectedTaskEvents = ref<TaskEvent[]>([])
const taskEventError = ref('')
const selectedTaskArtifacts = ref<ArtifactNode[]>([])
const artifactError = ref('')
const artifactBusy = ref(false)
const showShare = ref(false)
const shareArtifact = ref<ArtifactNode | null>(null)
const shareGrants = ref<ShareGrant[]>([])
const memberQuery = ref('')
const memberResults = ref<Member[]>([])
const shareBusy = ref(false)
const draft = ref<TaskDraft | null>(null)
const chatText = ref('')
const draftBusy = ref(false)
const taskBusy = ref(false)
const taskIdempotencyKey = ref(crypto.randomUUID())
const showLaunch = ref(false)
const showLogin = ref(false)
const showNewProject = ref(false)
const showGitImport = ref(false)
const configMode = ref<'templates' | 'providers' | 'profiles' | null>(null)
const configTemplateId = ref('')
const configProviderId = ref('')
const newProjectName = ref('')
const newProjectBusy = ref(false)
const email = ref('')
const password = ref('')
const authMode = ref<'login' | 'setup'>('login')
const organizationName = ref('')
const setupToken = ref('')
const loginBusy = ref(false)
const searchQuery = ref('')
const selectedProjectId = ref('')
const selectedTemplateVersionId = ref('')
const selectedProviderId = ref('')
const selectedModelId = ref('')
const selectableModels = ref<ProviderModel[]>([])
const importProjectId = ref('')
const importBusy = ref(false)
const gitBusy = ref(false)
const gitProjectId = ref('')
const gitUrl = ref('')
const gitBranch = ref('')
const gitToken = ref('')
const gitBranches = ref<string[]>([])
const fileInput = ref<HTMLInputElement | null>(null)

const sessionPresent = ref(Boolean(api.getSession()))
const signedIn = computed(() => sessionPresent.value)
const activeProject = computed(() => projects.value.find((project) => project.projectId === selectedProjectId.value) ?? projects.value[0])
const visibleTasks = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  if (!query) return tasks.value
  return tasks.value.filter((task) => `${task.type} ${task.status} ${task.taskId} ${task.projectId}`.toLowerCase().includes(query))
})
const runningCount = computed(() => tasks.value.filter((task) => task.status === 'RUNNING' || task.status === 'QUEUED').length)
const finishedCount = computed(() => tasks.value.filter((task) => task.status === 'SUCCEEDED').length)
const approvalCount = computed(() => tasks.value.filter((task) => task.status === 'WAITING_FOR_APPROVAL').length)
const flatArtifacts = computed(() => {
  const result: Array<{ node: ArtifactNode; depth: number }> = []
  const visit = (nodes: ArtifactNode[], depth: number) => nodes.forEach((node) => {
    result.push({ node, depth })
    visit(node.children ?? [], depth + 1)
  })
  visit(selectedTaskArtifacts.value, 0)
  return result
})
const pageTitle = computed(() => ({ overview: '工作台总览', projects: '项目空间', tasks: '任务队列', templates: '模板库', providers: '模型与 Provider', profiles: '登录档案', security: '权限与审计', settings: '工作区设置' }[activeSection.value] ?? '工作台总览'))
const pageSubtitle = computed(() => ({ overview: '把源码、智能生成和可验证产物放在同一个工作面里', projects: '每个项目都有独立的源码版本和任务上下文', tasks: '查看实时进度、人工确认和最终产物', templates: '使用公共模板，或维护自己的输出规范', providers: '按用户配置模型，任务可以单独选择 Provider', profiles: '为自动化截图保存可复用的登录上下文', security: '查看工作区边界和数据访问策略', settings: '管理品牌、默认参数和运行时连接' }[activeSection.value] ?? ''))

const typeLabels: Record<string, string> = { PROJECT_DOCS: '项目文档', USER_GUIDE: '用户手册', HTML: 'HTML 页面', SCREENSHOT: '自动截图' }
const statusLabels: Record<TaskStatus, string> = { QUEUED: '排队中', RUNNING: '运行中', PAUSED: '已暂停', WAITING_FOR_APPROVAL: '待确认', SUCCEEDED: '已完成', FAILED: '失败', CANCELED: '已取消' }

function notify(message: string) {
  toastMessage.value = message
  window.setTimeout(() => { if (toastMessage.value === message) toastMessage.value = '' }, 3200)
}

async function loadData(showSpinner = true) {
  if (!signedIn.value) {
    showLogin.value = true
    return
  }
  if (showSpinner) loading.value = true
  refreshing.value = true
  errorMessage.value = ''
  const results = await Promise.allSettled([api.projects(), api.tasks(), api.templates(), api.providers(), api.loginProfiles()])
  const [projectResult, taskResult, templateResult, providerResult, profileResult] = results
  if (projectResult.status === 'fulfilled') {
    const revisions = await Promise.allSettled(projectResult.value.map((project) => api.projectRevisions(project.projectId)))
    projects.value = projectResult.value.map((project, index) => ({
      ...project,
      hasRevision: revisions[index]?.status === 'fulfilled' && revisions[index].value.length > 0,
    }))
    if (!selectedProjectId.value && projects.value[0]) selectedProjectId.value = projects.value[0].projectId
  }
  if (taskResult.status === 'fulfilled') tasks.value = taskResult.value
  if (templateResult.status === 'fulfilled') templates.value = templateResult.value
  if (providerResult.status === 'fulfilled') providers.value = providerResult.value
  if (profileResult.status === 'fulfilled') profiles.value = profileResult.value
  const failed = results.find((result) => result.status === 'rejected')
  if (failed?.status === 'rejected' && failed.reason instanceof ApiError && failed.reason.status === 401) {
    api.clearSession()
    sessionPresent.value = false
    showLogin.value = true
    errorMessage.value = '登录已失效，请重新连接工作区'
  } else if (failed?.status === 'rejected' && results.every((result) => result.status === 'rejected')) {
    errorMessage.value = '暂时无法连接 Platform API，请确认后端服务已启动'
  }
  refreshing.value = false
  loading.value = false
}

async function login() {
  if (!email.value.trim() || !password.value) return
  loginBusy.value = true
  errorMessage.value = ''
  try {
    await api.login(email.value.trim(), password.value)
    sessionPresent.value = true
    showLogin.value = false
    password.value = ''
    await loadData()
    notify('工作区已连接')
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '登录失败，请检查账号和密码'
  } finally {
    loginBusy.value = false
  }
}

async function setupFirstAdmin() {
  if (!organizationName.value.trim() || !setupToken.value || !email.value.trim() || password.value.length < 12) return
  loginBusy.value = true
  errorMessage.value = ''
  try {
    await api.setupFirstAdmin({
      setupToken: setupToken.value,
      organizationName: organizationName.value.trim(),
      email: email.value.trim(),
      password: password.value,
    })
    sessionPresent.value = true
    setupToken.value = ''
    password.value = ''
    showLogin.value = false
    await loadData()
    notify('管理员与工作区已初始化')
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '初始化失败，请检查启动令牌与输入'
  } finally {
    loginBusy.value = false
  }
}

async function logout() {
  try {
    await api.logout()
  } finally {
    sessionPresent.value = false
    projects.value = []
    tasks.value = []
    showLogin.value = true
    notify('已安全退出')
  }
}

async function parseDraft() {
  if (!chatText.value.trim()) return
  draftBusy.value = true
  errorMessage.value = ''
  try {
    draft.value = await api.parseChat(chatText.value.trim())
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '无法解析任务意图'
  } finally {
    draftBusy.value = false
  }
}

async function launchTask() {
  if (!draft.value || !activeProject.value || !selectedTemplateVersionId.value.trim()) {
    errorMessage.value = !activeProject.value ? '请先创建或选择一个项目' : '请填写模板版本 ID'
    return
  }
  if (selectedProviderId.value && !selectedModelId.value) {
    errorMessage.value = '请选择该 Provider 下的模型'
    return
  }
  taskBusy.value = true
  try {
    const created = await api.createTask({
      projectId: activeProject.value.projectId,
      type: draft.value.workflowType,
      templateVersionId: selectedTemplateVersionId.value.trim(),
      parameters: draft.value.parameters,
      ...(selectedProviderId.value ? { providerProfileId: selectedProviderId.value, modelId: selectedModelId.value } : {}),
    }, taskIdempotencyKey.value)
    tasks.value = [created, ...tasks.value]
    showLaunch.value = false
    draft.value = null
    chatText.value = ''
    taskIdempotencyKey.value = crypto.randomUUID()
    selectedTask.value = created
    await refreshTaskEvents(created)
    notify('任务已进入队列')
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '任务创建失败'
  } finally {
    taskBusy.value = false
  }
}

async function refreshTaskEvents(task: Task) {
  taskEventError.value = ''
  try {
    selectedTaskEvents.value = await api.taskEvents(task.taskId)
  } catch (error) {
    selectedTaskEvents.value = []
    taskEventError.value = error instanceof ApiError ? error.message : '事件流读取失败'
  }
}

async function refreshTaskArtifacts(task: Task) {
  artifactError.value = ''
  try {
    selectedTaskArtifacts.value = await api.taskArtifacts(task.taskId)
  } catch (error) {
    selectedTaskArtifacts.value = []
    artifactError.value = error instanceof ApiError ? error.message : '产物列表读取失败'
  }
}

async function openTask(task: Task) {
  selectedTask.value = task
  await Promise.all([refreshTaskEvents(task), refreshTaskArtifacts(task)])
}

async function taskAction(task: Task, action: 'pause' | 'resume' | 'cancel') {
  try {
    const updated = await api.taskAction(task.taskId, action)
    tasks.value = tasks.value.map((item) => item.taskId === updated.taskId ? updated : item)
    if (selectedTask.value?.taskId === updated.taskId) selectedTask.value = updated
    await Promise.all([refreshTaskEvents(updated), refreshTaskArtifacts(updated)])
    notify(action === 'pause' ? '任务已暂停' : action === 'resume' ? '任务已恢复' : '任务已取消')
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '任务操作失败'
  }
}

async function createProject() {
  if (!newProjectName.value.trim()) return
  newProjectBusy.value = true
  try {
    const project = await api.createProject(newProjectName.value.trim())
    projects.value = [project, ...projects.value]
    selectedProjectId.value = project.projectId
    newProjectName.value = ''
    showNewProject.value = false
    notify('项目空间已创建，现在可以导入源码')
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '项目创建失败'
  } finally {
    newProjectBusy.value = false
  }
}

async function importZip(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || !importProjectId.value) return
  importBusy.value = true
  try {
    await api.importZip(importProjectId.value, file)
    await loadData(false)
    notify('源码版本已导入')
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : 'ZIP 导入失败'
  } finally {
    importBusy.value = false
    input.value = ''
  }
}

function pickImport(projectId: string) {
  importProjectId.value = projectId
  fileInput.value?.click()
}

function openGitImport(projectId?: string) {
  errorMessage.value = ''
  gitProjectId.value = projectId || activeProject.value?.projectId || ''
  gitBranches.value = []
  gitBranch.value = ''
  showGitImport.value = true
}

function closeGitImport() {
  showGitImport.value = false
  gitToken.value = ''
  gitUrl.value = ''
  gitBranch.value = ''
  gitBranches.value = []
}

watch([gitProjectId, gitUrl, gitToken], () => {
  gitBranches.value = []
  gitBranch.value = ''
})

watch(selectedProviderId, async (providerId) => {
  selectedModelId.value = ''
  selectableModels.value = []
  if (!providerId) return
  try {
    selectableModels.value = await api.providerModels(providerId)
    selectedModelId.value = selectableModels.value.find((model) => model.defaultModel)?.modelId ?? selectableModels.value[0]?.modelId ?? ''
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '模型列表加载失败'
  }
})

async function inspectGit() {
  if (!gitProjectId.value || !gitUrl.value.trim()) return
  gitBusy.value = true
  errorMessage.value = ''
  try {
    const result = await api.testGit(gitProjectId.value, { url: gitUrl.value.trim(), token: gitToken.value || undefined })
    gitBranches.value = result.branches
    gitBranch.value = result.branches[0] ?? ''
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : 'Git 仓库连接失败'
  } finally {
    gitBusy.value = false
  }
}

async function importGit() {
  if (!gitProjectId.value || !gitUrl.value.trim()) return
  gitBusy.value = true
  errorMessage.value = ''
  try {
    await api.importGit(gitProjectId.value, {
      url: gitUrl.value.trim(),
      branch: gitBranch.value || undefined,
      token: gitToken.value || undefined,
    })
    closeGitImport()
    await loadData(false)
    notify('Git 源码版本已导入')
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : 'Git 导入失败'
  } finally {
    gitBusy.value = false
  }
}

function openConfig(mode: 'templates' | 'providers' | 'profiles', resourceId = '') {
  configTemplateId.value = mode === 'templates' ? resourceId : ''
  configProviderId.value = mode === 'providers' ? resourceId : ''
  configMode.value = mode
}

async function configSaved(message: string) {
  configMode.value = null
  await loadData(false)
  notify(message)
}

async function copyTemplate(template: Template) {
  try {
    await api.copyTemplate(template.id)
    await loadData(false)
    notify('公共模板已复制到个人模板库')
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '模板复制失败'
  }
}

async function testProvider(provider: Provider) {
  providerBusyId.value = provider.id
  errorMessage.value = ''
  try {
    const result = await api.testProvider(provider.id)
    if (!result.ok) throw new ApiError(result.code ?? `Provider 返回 HTTP ${result.statusCode}`, result.statusCode, result.code)
    notify(`连接正常 · ${result.latencyMillis} ms · ${result.modelCount} 个模型`)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : 'Provider 连接测试失败'
  } finally {
    providerBusyId.value = ''
  }
}

function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

async function previewArtifact(node: ArtifactNode) {
  if (!node.artifactId) return
  const previewWindow = window.open('about:blank', '_blank')
  if (!previewWindow) {
    artifactError.value = '浏览器阻止了预览窗口，请允许弹窗后重试'
    return
  }
  previewWindow.opener = null
  artifactBusy.value = true
  artifactError.value = ''
  try {
    const blob = await api.previewArtifact(node.artifactId, node.version)
    const unsafeInline = /(?:text\/html|image\/svg\+xml|application\/(?:xhtml\+xml|xml))/i.test(node.mediaType ?? blob.type)
    const preview = unsafeInline ? new Blob([await blob.text()], { type: 'text/plain;charset=utf-8' }) : blob
    const url = URL.createObjectURL(preview)
    previewWindow.location.href = url
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } catch (error) {
    previewWindow.close()
    artifactError.value = error instanceof ApiError ? error.message : '产物预览失败'
  } finally {
    artifactBusy.value = false
  }
}

async function downloadArtifact(node: ArtifactNode) {
  if (!node.artifactId) return
  artifactBusy.value = true
  artifactError.value = ''
  try {
    saveBlob(await api.downloadArtifact(node.artifactId, node.version), node.name)
  } catch (error) {
    artifactError.value = error instanceof ApiError ? error.message : '产物下载失败'
  } finally {
    artifactBusy.value = false
  }
}

async function exportArtifacts() {
  if (!selectedTask.value) return
  artifactBusy.value = true
  artifactError.value = ''
  try {
    saveBlob(await api.exportTaskArtifacts(selectedTask.value.taskId), `task-${selectedTask.value.taskId}.zip`)
  } catch (error) {
    artifactError.value = error instanceof ApiError ? error.message : '产物导出失败'
  } finally {
    artifactBusy.value = false
  }
}

async function openArtifactShare(node: ArtifactNode) {
  if (!node.artifactId) return
  errorMessage.value = ''
  shareArtifact.value = node
  memberQuery.value = ''
  memberResults.value = []
  shareBusy.value = true
  showShare.value = true
  try {
    shareGrants.value = await api.shares('ARTIFACT', node.artifactId)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '分享列表读取失败'
  } finally {
    shareBusy.value = false
  }
}

async function searchMembers() {
  if (!memberQuery.value.trim()) return
  shareBusy.value = true
  try {
    const members = await api.members(memberQuery.value.trim())
    const existing = new Set(shareGrants.value.map((grant) => grant.member.id))
    memberResults.value = members.filter((member) => !existing.has(member.id))
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '成员搜索失败'
  } finally {
    shareBusy.value = false
  }
}

async function grantArtifact(member: Member) {
  if (!shareArtifact.value?.artifactId) return
  shareBusy.value = true
  try {
    const grant = await api.createShare(member.id, 'ARTIFACT', shareArtifact.value.artifactId)
    shareGrants.value = [...shareGrants.value.filter((item) => item.id !== grant.id), grant]
    memberResults.value = memberResults.value.filter((item) => item.id !== member.id)
    notify(`已向 ${member.email} 分享产物`)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '分享失败'
  } finally {
    shareBusy.value = false
  }
}

async function revokeShare(grant: ShareGrant) {
  shareBusy.value = true
  try {
    await api.deleteShare(grant.id)
    shareGrants.value = shareGrants.value.filter((item) => item.id !== grant.id)
    notify('分享权限已撤销')
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '撤销分享失败'
  } finally {
    shareBusy.value = false
  }
}

function selectSection(value: string) {
  activeSection.value = value
  selectedTask.value = null
}

function openLaunch(type?: TaskDraft['workflowType']) {
  if (!showLaunch.value) taskIdempotencyKey.value = crypto.randomUUID()
  if (type) draft.value = { workflowType: type, parameters: {}, summary: `${typeLabels[type]}任务`, taskReference: null }
  showLaunch.value = true
}

onMounted(() => loadData())
</script>

<template>
  <div class="app-root">
    <SidebarNav :active="activeSection" :collapsed="sidebarCollapsed" @select="selectSection" @toggle="sidebarCollapsed = !sidebarCollapsed" />

    <main class="app-main">
      <header class="topbar">
        <div class="breadcrumbs"><span>个人工作区</span><ChevronDown :size="14" /><span class="breadcrumbs__current">{{ pageTitle }}</span></div>
        <div class="topbar__actions">
          <button class="icon-button" type="button" aria-label="搜索尚未开放" title="全局搜索需要后端搜索契约" disabled><Search :size="18" /></button>
          <button class="icon-button" type="button" aria-label="帮助尚未开放" title="帮助中心尚未配置" disabled><CircleHelp :size="18" /></button>
          <span class="topbar__divider" />
          <div class="user-menu"><span class="avatar avatar--small">U</span><span class="user-menu__name">当前账号</span></div>
        </div>
      </header>

      <div class="page-scroll">
        <section class="page-heading">
          <div><div class="page-heading__kicker"><span class="live-pulse" />{{ signedIn ? 'WORKSPACE ONLINE' : 'WORKSPACE PREVIEW' }}</div><h1>{{ pageTitle }}</h1><p>{{ pageSubtitle }}</p></div>
          <div class="page-heading__actions"><button class="button button--quiet" type="button" :disabled="refreshing" @click="loadData(false)"><RefreshCw :class="{ spin: refreshing }" :size="16" />刷新</button><button class="button button--primary" type="button" @click="openLaunch()"><Plus :size="17" />新建工作流</button></div>
        </section>

        <div v-if="errorMessage" class="alert alert--error"><ShieldCheck :size="17" /><span>{{ errorMessage }}</span><button class="icon-button" type="button" aria-label="关闭提示" @click="errorMessage = ''"><X :size="15" /></button></div>

        <template v-if="activeSection === 'overview'">
          <section class="metric-grid">
            <MetricCard label="活跃任务" :value="runningCount" helper="当前工作区" trend="本周" direction="up" />
            <MetricCard label="已完成产物" :value="finishedCount" helper="包含所有工作流类型" trend="稳定" direction="neutral" />
            <MetricCard label="待人工确认" :value="approvalCount" helper="需要你的决定" trend="关注" direction="down" />
            <MetricCard label="可用模板" :value="templates.length" helper="公共与个人模板" trend="可编辑" direction="neutral" />
          </section>

          <section class="hero-grid">
            <article class="hero-card">
              <div class="hero-card__orb hero-card__orb--one" /><div class="hero-card__orb hero-card__orb--two" />
              <div class="hero-card__content"><div class="hero-card__eyebrow"><Sparkles :size="14" /> AUTOMATION WORKSPACE</div><h2>从源码到可交付产物，<br /><em>一次工作流完成。</em></h2><p>选择模板，描述目标。Agent Studio 会把文档、HTML 和截图组织成可审查的版本。</p><button class="button button--dark" type="button" @click="openLaunch()">开始一次生成 <ArrowRight :size="16" /></button></div>
              <div class="hero-card__diagram"><div class="diagram-node diagram-node--source"><Code2 :size="17" /><span>Source</span></div><div class="diagram-line" /><div class="diagram-node diagram-node--agent"><Sparkles :size="17" /><span>Agent</span></div><div class="diagram-line" /><div class="diagram-node diagram-node--artifact"><FileText :size="17" /><span>Artifact</span></div></div>
            </article>
            <article class="activity-card"><div class="section-head"><div><span class="section-head__eyebrow">NEXT UP</span><h3>需要你的注意</h3></div><button class="text-button" type="button" @click="activeSection = 'tasks'">查看全部 <ArrowRight :size="14" /></button></div><div v-if="approvalCount === 0 && tasks.length === 0" class="activity-empty"><div class="activity-empty__icon"><Check :size="18" /></div><strong>工作区很安静</strong><span>发起一个工作流后，进度和确认会出现在这里</span></div><div v-else class="attention-list"><button v-for="task in tasks.filter((item) => item.status === 'WAITING_FOR_APPROVAL').slice(0, 3)" :key="task.taskId" class="attention-item" type="button" @click="openTask(task)"><span class="attention-item__icon"><LockKeyhole :size="16" /></span><span><strong>{{ typeLabels[task.type] }}</strong><small>等待你的确认</small></span><ArrowUpRight :size="15" /></button><button v-for="task in tasks.filter((item) => item.status === 'FAILED').slice(0, 2)" :key="`failed-${task.taskId}`" class="attention-item attention-item--failed" type="button" @click="openTask(task)"><span class="attention-item__icon"><X :size="16" /></span><span><strong>{{ typeLabels[task.type] }}</strong><small>{{ task.failureCode ?? '执行失败' }}</small></span><ArrowUpRight :size="15" /></button></div></article>
          </section>

          <section class="content-section"><div class="section-head"><div><span class="section-head__eyebrow">RECENT RUNS</span><h3>最近任务</h3></div><div class="section-head__tools"><button class="text-button" type="button" @click="activeSection = 'tasks'">全部任务 <ArrowRight :size="14" /></button></div></div><TaskTable :tasks="tasks.slice(0, 6)" @open="openTask" @action="taskAction" /></section>
        </template>

        <template v-else-if="activeSection === 'projects'">
          <section class="toolbar-card"><div class="toolbar-card__copy"><div class="toolbar-card__icon"><FolderPlus :size="19" /></div><div><strong>导入一个项目</strong><span>支持 Git URL 或 ZIP，导入后会生成不可变源码版本</span></div></div><div class="toolbar-card__actions"><button class="button button--quiet" type="button" :disabled="!projects.length" @click="openGitImport()"><Github :size="16" />Git URL</button><button class="button button--quiet" type="button" :disabled="!projects.length" @click="pickImport(activeProject?.projectId ?? '')"><Upload :size="16" />上传 ZIP</button><button class="button button--primary" type="button" @click="showNewProject = true"><Plus :size="16" />新建项目</button><input ref="fileInput" class="visually-hidden" type="file" accept=".zip,application/zip" @change="importZip" /></div></section>
          <section class="content-section"><div class="section-head"><div><span class="section-head__eyebrow">PROJECT SPACES</span><h3>你的项目</h3></div><div class="search-box"><Search :size="15" /><input v-model="searchQuery" placeholder="搜索项目" /></div></div><div class="project-grid"><ProjectCard v-for="(project, index) in projects" :key="project.projectId" :project="project" :accent="['#7367f0', '#23b7a4', '#f09a55', '#db6c99'][index % 4]" @open="selectedProjectId = project.projectId; openLaunch()" /><div v-if="projects.length === 0" class="empty-card"><div class="empty-card__icon"><FolderPlus :size="22" /></div><strong>创建第一个项目空间</strong><span>导入源码后，文档和截图任务会绑定到具体 revision</span><button class="button button--primary" type="button" @click="showNewProject = true">新建项目</button></div></div></section>
        </template>

        <template v-else-if="activeSection === 'tasks'">
          <section class="content-section content-section--full"><div class="section-head"><div><span class="section-head__eyebrow">TASK HISTORY</span><h3>全部任务</h3></div><div class="section-head__tools"><div class="search-box"><Search :size="15" /><input v-model="searchQuery" placeholder="搜索任务" /></div><button class="button button--primary" type="button" @click="openLaunch()"><Plus :size="16" />新建任务</button></div></div><TaskTable :tasks="visibleTasks" @open="openTask" @action="taskAction" /></section>
          <section class="content-section"><div class="section-head"><div><span class="section-head__eyebrow">TASK STATES</span><h3>状态说明</h3></div></div><div class="status-guide"><div v-for="status in (['QUEUED', 'RUNNING', 'WAITING_FOR_APPROVAL', 'PAUSED', 'SUCCEEDED', 'FAILED'] as TaskStatus[])" :key="status"><span class="status-pill" :class="`status-pill--${status.toLowerCase()}`"><span class="status-pill__dot" />{{ statusLabels[status] }}</span><small>{{ status === 'WAITING_FOR_APPROVAL' ? '等待用户选择或文字确认' : status === 'SUCCEEDED' ? '产物已登记并可预览' : status === 'FAILED' ? '保留失败 marker 和错误码' : '任务生命周期状态' }}</small></div></div></section>
        </template>

        <template v-else-if="activeSection === 'templates' || activeSection === 'providers' || activeSection === 'profiles'">
          <section class="library-banner"><div class="library-banner__glow" /><div><span class="section-head__eyebrow">CONFIGURABLE LIBRARY</span><h2>{{ pageTitle }}</h2><p>把规范沉淀成可复用配置，任务只引用不可变版本。</p></div><button class="button button--dark" type="button" @click="openConfig(activeSection as 'templates' | 'providers' | 'profiles')"><Settings2 :size="16" />{{ activeSection === 'templates' ? '新建模板' : activeSection === 'providers' ? '添加 Provider' : '添加登录档案' }}</button></section>
          <section class="library-grid" v-if="activeSection === 'templates'"><article v-for="template in templates" :key="template.id" class="library-card"><div class="library-card__icon library-card__icon--purple"><FileText :size="19" /></div><div class="library-card__body"><div class="library-card__title"><strong>{{ template.name }}</strong><span class="visibility-tag" :class="template.visibility === 'PUBLIC' ? 'visibility-tag--public' : ''">{{ template.visibility === 'PUBLIC' ? '公共' : '个人' }}</span></div><p>Skill {{ template.skillId.slice(0, 8) }} · {{ template.latestVersion ? `v${template.latestVersion}` : '尚无版本' }}</p><div class="library-card__footer"><span>声明式结构 · 无脚本</span><span class="card-actions"><button v-if="template.visibility === 'PUBLIC'" class="text-button" type="button" @click="copyTemplate(template)">复制</button><button class="text-button" type="button" @click="openConfig('templates', template.id)">新版本</button></span></div></div></article><div v-if="templates.length === 0" class="empty-card"><div class="empty-card__icon"><FileText :size="22" /></div><strong>还没有模板</strong><span>创建一个可编辑的 Markdown 或 HTML 输出规范</span><button class="button button--primary" type="button" @click="openConfig('templates')">新建模板</button></div></section>
          <section class="library-grid" v-else-if="activeSection === 'providers'"><article v-for="provider in providers" :key="provider.id" class="library-card"><div class="library-card__icon library-card__icon--green"><Sparkles :size="19" /></div><div class="library-card__body"><div class="library-card__title"><strong>{{ provider.name }}</strong><span v-if="provider.isDefault" class="visibility-tag visibility-tag--default">默认</span></div><p>{{ provider.providerType }} · {{ provider.baseUrl }}</p><div class="library-card__footer"><span>{{ provider.credentialConfigured === false ? '缺少凭证' : provider.credentialConfigured ? '凭证已配置' : provider.maskedApiKey ?? '凭证已保存' }}</span><span class="card-actions"><button class="text-button" type="button" :disabled="providerBusyId === provider.id" @click="testProvider(provider)">{{ providerBusyId === provider.id ? '测试中…' : '测试' }}</button><button class="text-button" type="button" @click="openConfig('providers', provider.id)">管理</button></span></div></div></article><div v-if="providers.length === 0" class="empty-card"><div class="empty-card__icon"><Sparkles :size="22" /></div><strong>配置你的第一个模型</strong><span>每位用户单独保存 Provider 和默认模型</span><button class="button button--primary" type="button" @click="openConfig('providers')">添加 Provider</button></div></section>
          <section class="library-grid" v-else><article v-for="profile in profiles" :key="profile.id" class="library-card"><div class="library-card__icon library-card__icon--orange"><UserRound :size="19" /></div><div class="library-card__body"><div class="library-card__title"><strong>{{ profile.name }}</strong><span class="visibility-tag visibility-tag--private">私有</span></div><p>{{ profile.reference }} · {{ profile.loginUrl }}</p><div class="library-card__footer"><span>结构化语义定位器 · 凭证加密</span><span title="当前 API 只支持新增登录档案">新增档案替代修改</span></div></div></article><div v-if="profiles.length === 0" class="empty-card"><div class="empty-card__icon"><UserRound :size="22" /></div><strong>添加一个登录档案</strong><span>截图任务可以在管理员和成员登录态之间切换</span><button class="button button--primary" type="button" @click="openConfig('profiles')">添加登录档案</button></div></section>
        </template>

        <template v-else>
          <section class="settings-grid"><article class="settings-card"><div class="settings-card__icon"><ShieldCheck :size="19" /></div><h3>数据边界</h3><p>项目默认私有。管理员不会自动读取成员的源码、任务日志或产物；公开分享仍需补充管理接口。</p><span class="settings-card__status"><Check :size="14" />读取策略已启用</span></article><article class="settings-card"><div class="settings-card__icon settings-card__icon--blue"><LockKeyhole :size="19" /></div><h3>密钥保护</h3><p>Provider Key 和 Login Profile 凭证在 Platform API 加密保存，worker 只获得任务范围内的短时凭证。</p><span class="settings-card__status"><Check :size="14" />AES-GCM</span></article><article class="settings-card"><div class="settings-card__icon settings-card__icon--orange"><Image :size="19" /></div><h3>产物版本</h3><p>Worker 已按不可变版本登记文档、HTML、截图和清单；公开预览、下载和分享接口尚待补齐。</p><span class="settings-card__status">API 待接入</span></article></section>
        </template>
      </div>
    </main>

    <aside v-if="selectedTask" class="task-inspector">
      <div class="task-inspector__head"><div><span class="section-head__eyebrow">TASK DETAIL</span><h3>{{ typeLabels[selectedTask.type] }}</h3></div><button class="icon-button" type="button" aria-label="关闭任务详情" @click="selectedTask = null"><X :size="18" /></button></div>
      <div class="task-inspector__status"><span class="status-pill" :class="`status-pill--${selectedTask.status.toLowerCase()}`"><span class="status-pill__dot" />{{ statusLabels[selectedTask.status] }}</span><span class="muted">{{ selectedTask.taskId.slice(0, 12) }}</span></div>
      <div class="inspector-actions"><button v-if="selectedTask.status === 'RUNNING'" class="button button--quiet" type="button" @click="taskAction(selectedTask, 'pause')"><Menu :size="15" />暂停</button><button v-if="selectedTask.status === 'PAUSED'" class="button button--quiet" type="button" @click="taskAction(selectedTask, 'resume')"><ArrowRight :size="15" />恢复</button><button v-if="['RUNNING', 'QUEUED', 'PAUSED', 'WAITING_FOR_APPROVAL'].includes(selectedTask.status)" class="button button--danger" type="button" @click="taskAction(selectedTask, 'cancel')"><X :size="15" />取消</button></div>
      <div v-if="selectedTask.status === 'WAITING_FOR_APPROVAL'" class="approval-box"><div class="approval-box__icon"><LockKeyhole :size="17" /></div><div><strong>需要人工确认</strong><p>Platform API 尚未提供当前待审批项的读取接口，前端无法安全取得 approvalId、选项和截止时间。</p><div class="approval-box__actions"><button class="button button--primary" type="button" title="需要 GET /api/v1/tasks/{taskId}/approvals/pending" disabled><Check :size="15" />确认继续</button></div></div></div>
      <div class="inspector-block"><span class="section-head__eyebrow">EVENT STREAM</span><div v-if="taskEventError" class="config-error">{{ taskEventError }}</div><div class="event-list"><div v-for="event in selectedTaskEvents" :key="event.sequence" class="event-item"><span class="event-item__line" /><div><strong>{{ event.message ?? event.type }}</strong><small>{{ event.status }} · #{{ event.sequence }}</small></div></div><div v-if="selectedTaskEvents.length === 0 && !taskEventError" class="event-empty"><LoaderCircle :size="16" />等待事件</div></div></div>
      <div class="inspector-block"><div class="artifact-section-head"><span class="section-head__eyebrow">ARTIFACTS</span><button v-if="flatArtifacts.some((item) => item.node.type === 'ARTIFACT')" class="text-button" type="button" :disabled="artifactBusy" @click="exportArtifacts"><Archive :size="14" />导出 ZIP</button></div><div v-if="artifactError" class="config-error">{{ artifactError }}</div><div class="artifact-tree"><div v-for="item in flatArtifacts" :key="item.node.path" class="artifact-row" :class="{ 'artifact-row--folder': item.node.type === 'FOLDER' }" :style="{ paddingLeft: `${10 + item.depth * 15}px` }"><span class="artifact-row__file"><FileCode2 v-if="item.node.type === 'FOLDER'" :size="15" /><Image v-else-if="item.node.kind === 'SCREENSHOT'" :size="15" /><FileText v-else :size="15" /><span><strong>{{ item.node.name }}</strong><small v-if="item.node.type === 'ARTIFACT'">v{{ item.node.version }} · {{ item.node.mediaType }}</small></span></span><span v-if="item.node.type === 'ARTIFACT'" class="artifact-row__actions"><button class="icon-button" type="button" aria-label="预览产物" :disabled="artifactBusy" @click="previewArtifact(item.node)"><Eye :size="14" /></button><button class="icon-button" type="button" aria-label="下载产物" :disabled="artifactBusy" @click="downloadArtifact(item.node)"><Download :size="14" /></button><button class="icon-button" type="button" aria-label="分享产物" :disabled="artifactBusy" @click="openArtifactShare(item.node)"><Share2 :size="14" /></button></span></div><div v-if="flatArtifacts.length === 0 && !artifactError" class="event-empty">当前任务还没有已发布产物</div></div></div>
      <div v-if="selectedTask.resultReference && flatArtifacts.length === 0" class="artifact-preview"><div class="artifact-preview__top"><span><FileCode2 :size="15" />旧版产物引用</span></div><div class="artifact-preview__body"><div class="artifact-preview__file"><FileText :size="18" /><span>{{ selectedTask.resultReference }}</span></div><div class="artifact-preview__hint">产物树暂未返回此引用对应的版本。</div></div></div>
    </aside>

    <div v-if="showLaunch" class="drawer-layer" @click.self="showLaunch = false"><section class="launch-drawer"><div class="launch-drawer__head"><div><span class="section-head__eyebrow">NEW WORKFLOW</span><h2>描述你想交付的结果</h2><p>先生成草稿，确认后才会创建任务。</p></div><button class="icon-button" type="button" aria-label="关闭" @click="showLaunch = false"><X :size="19" /></button></div><div class="launch-drawer__body"><div v-if="errorMessage" class="config-error">{{ errorMessage }}</div><div class="chat-box"><div class="chat-box__label"><Sparkles :size="15" />自然语言目标</div><textarea v-model="chatText" placeholder="例如：为这个项目生成一份面向维护者的项目文档，并为管理员用户列表添加截图…" @keydown.meta.enter="parseDraft" @keydown.ctrl.enter="parseDraft" /><div class="chat-box__footer"><span>Enter 发送 · ⌘↵ 解析草稿</span><button class="send-button" type="button" :disabled="draftBusy || !chatText.trim()" @click="parseDraft"><LoaderCircle v-if="draftBusy" class="spin" :size="16" /><Send v-else :size="16" /></button></div></div><div v-if="draft" class="draft-card"><div class="draft-card__top"><div class="draft-type"><span class="draft-type__icon"><FileText v-if="draft.workflowType !== 'HTML' && draft.workflowType !== 'SCREENSHOT'" :size="15" /><Code2 v-else-if="draft.workflowType === 'HTML'" :size="15" /><Image v-else :size="15" /></span><div><span class="section-head__eyebrow">EDITABLE DRAFT</span><strong>{{ typeLabels[draft.workflowType] }}</strong></div></div><span class="draft-ready"><Check :size="13" />未启动</span></div><p>{{ draft.summary }}</p><div class="draft-fields"><label>项目空间<select v-model="selectedProjectId"><option value="" disabled>选择项目</option><option v-for="project in projects" :key="project.projectId" :value="project.projectId">{{ project.name }}</option></select></label><label>模板版本 ID<input v-model="selectedTemplateVersionId" placeholder="粘贴 immutable version UUID" /></label></div><div class="draft-fields draft-fields--provider"><label>Provider<select v-model="selectedProviderId"><option value="">使用默认 Provider</option><option v-for="provider in providers" :key="provider.id" :value="provider.id">{{ provider.name }}</option></select></label><label>模型<select v-model="selectedModelId" :disabled="!selectedProviderId"><option value="">{{ selectedProviderId ? '选择模型' : '跟随默认模型' }}</option><option v-for="model in selectableModels" :key="model.modelId" :value="model.modelId">{{ model.displayName }}</option></select></label></div><div class="draft-params"><span v-for="(value, key) in draft.parameters" :key="key" class="param-chip"><b>{{ key }}</b>{{ value }}</span></div><div class="draft-card__actions"><button class="button button--quiet" type="button" @click="draft = null">重新编辑</button><button class="button button--primary" type="button" :disabled="taskBusy" @click="launchTask"><LoaderCircle v-if="taskBusy" class="spin" :size="15" />确认并创建任务 <ArrowRight v-if="!taskBusy" :size="15" /></button></div></div><div v-else class="suggestions"><span>试试这些目标</span><button type="button" @click="chatText = '生成项目文档，包含源码结构和关键入口'; parseDraft()"><FileText :size="15" />项目文档</button><button type="button" @click="chatText = '生成用户操作手册，并为管理员菜单添加截图'; parseDraft()"><UserRound :size="15" />用户手册</button><button type="button" @click="chatText = '生成一个响应式 HTML 项目介绍页面'; parseDraft()"><Code2 :size="15" />HTML 页面</button><button type="button" @click="chatText = '截图管理员登录后的用户列表'; parseDraft()"><Image :size="15" />自动截图</button></div></div></section></div>

    <div v-if="showNewProject" class="modal-layer" @click.self="showNewProject = false"><section class="modal-card"><div class="modal-card__head"><div><span class="section-head__eyebrow">PROJECT SPACE</span><h2>创建项目</h2></div><button class="icon-button" type="button" aria-label="关闭" @click="showNewProject = false"><X :size="18" /></button></div><div v-if="errorMessage" class="config-error">{{ errorMessage }}</div><label class="field-label">项目名称<input v-model="newProjectName" autofocus placeholder="例如：Customer Portal" @keydown.enter="createProject" /></label><p class="modal-card__hint">创建后请导入 Git URL 或 ZIP 源码版本。项目与任务默认只对你可见。</p><div class="modal-card__actions"><button class="button button--quiet" type="button" @click="showNewProject = false">取消</button><button class="button button--primary" type="button" :disabled="newProjectBusy" @click="createProject"><LoaderCircle v-if="newProjectBusy" class="spin" :size="15" />创建项目</button></div></section></div>

    <div v-if="showGitImport" class="modal-layer" @click.self="closeGitImport"><section class="modal-card config-modal"><div class="modal-card__head"><div><span class="section-head__eyebrow">GIT IMPORT</span><h2>导入 Git 仓库</h2><p>先检查连接并选择远端分支，再创建不可变源码版本。</p></div><button class="icon-button" type="button" aria-label="关闭" @click="closeGitImport"><X :size="18" /></button></div><div v-if="errorMessage" class="config-error">{{ errorMessage }}</div><form class="config-form" @submit.prevent="gitBranches.length ? importGit() : inspectGit()"><label class="field-label">项目<select v-model="gitProjectId" required><option value="" disabled>选择项目</option><option v-for="project in projects" :key="project.projectId" :value="project.projectId">{{ project.name }}</option></select></label><label class="field-label">Git URL<input v-model="gitUrl" type="url" required placeholder="https://github.com/org/repository.git" /></label><label class="field-label">访问令牌（私有仓库可选）<input v-model="gitToken" type="password" autocomplete="new-password" placeholder="只用于本次导入请求" /></label><label v-if="gitBranches.length" class="field-label">分支<select v-model="gitBranch"><option v-for="branch in gitBranches" :key="branch" :value="branch">{{ branch }}</option></select></label><button class="button button--primary button--wide" type="submit" :disabled="gitBusy"><LoaderCircle v-if="gitBusy" class="spin" :size="16" />{{ gitBranches.length ? '导入选定分支' : '检查仓库连接' }}</button></form></section></div>

    <ConfigManagerModal v-if="configMode" :mode="configMode" :templates="templates" :providers="providers" :selected-template-id="configTemplateId" :selected-provider-id="configProviderId" @close="configMode = null" @saved="configSaved" @template-version="selectedTemplateVersionId = $event" />

    <div v-if="showShare && shareArtifact" class="modal-layer" @click.self="showShare = false"><section class="modal-card config-modal share-modal"><div class="modal-card__head"><div><span class="section-head__eyebrow">ARTIFACT ACCESS</span><h2>分享 {{ shareArtifact.name }}</h2><p>只有明确加入的组织成员可以读取该产物，权限固定为只读。</p></div><button class="icon-button" type="button" aria-label="关闭" @click="showShare = false"><X :size="18" /></button></div><div v-if="errorMessage" class="config-error">{{ errorMessage }}</div><form class="share-search" @submit.prevent="searchMembers"><div class="search-box"><Search :size="15" /><input v-model="memberQuery" placeholder="按邮箱搜索组织成员" /></div><button class="button button--quiet" type="submit" :disabled="shareBusy || !memberQuery.trim()">搜索</button></form><div v-if="memberResults.length" class="share-list"><span class="section-head__eyebrow">SEARCH RESULTS</span><div v-for="member in memberResults" :key="member.id" class="share-row"><span>{{ member.email }}</span><button class="text-button" type="button" :disabled="shareBusy" @click="grantArtifact(member)">添加只读权限</button></div></div><div class="share-list"><span class="section-head__eyebrow">CURRENT ACCESS</span><div v-for="grant in shareGrants" :key="grant.id" class="share-row"><span>{{ grant.member.email }}<small>只读</small></span><button class="text-button text-button--danger" type="button" :disabled="shareBusy" @click="revokeShare(grant)">撤销</button></div><div v-if="shareGrants.length === 0" class="event-empty">尚未分享给其他成员</div></div></section></div>

    <div v-if="showLogin" class="auth-layer"><section class="auth-card"><div class="auth-card__brand"><div class="brand-mark"><Sparkles :size="17" /></div><span>Agent Studio</span></div><div class="auth-card__intro"><span class="page-heading__kicker">PRIVATE WORKSPACE</span><h1>{{ authMode === 'login' ? '连接你的工作台' : '初始化工作区' }}</h1><p>{{ authMode === 'login' ? '登录后读取项目、模板、任务和私有 Provider 配置。' : '首次启动时使用部署环境中的 SETUP_TOKEN 创建首位管理员。' }}</p></div><div v-if="errorMessage" class="config-error auth-card__error">{{ errorMessage }}</div><div class="config-segment auth-card__segment"><button type="button" :class="{ active: authMode === 'login' }" @click="authMode = 'login'">账号登录</button><button type="button" :class="{ active: authMode === 'setup' }" @click="authMode = 'setup'">首次初始化</button></div><form @submit.prevent="authMode === 'login' ? login() : setupFirstAdmin()"><label v-if="authMode === 'setup'" class="field-label">组织名称<input v-model="organizationName" autocomplete="organization" placeholder="你的团队或项目名称" required /></label><label v-if="authMode === 'setup'" class="field-label">启动令牌<input v-model="setupToken" type="password" autocomplete="off" placeholder="SETUP_TOKEN" required /></label><label class="field-label">邮箱<input v-model="email" type="email" autocomplete="username" placeholder="you@example.com" required /></label><label class="field-label">密码<input v-model="password" type="password" :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'" minlength="12" maxlength="128" placeholder="至少 12 个字符" required /></label><button class="button button--primary button--wide" type="submit" :disabled="loginBusy"><LoaderCircle v-if="loginBusy" class="spin" :size="16" />{{ loginBusy ? '正在连接…' : authMode === 'login' ? '登录工作区' : '创建管理员并进入' }}<ArrowRight v-if="!loginBusy" :size="16" /></button></form><div class="auth-card__foot"><LockKeyhole :size="14" />会话由 Platform API 管理 · 默认不共享私有数据</div><button v-if="signedIn" class="text-button auth-card__logout" type="button" @click="logout"><LogOut :size="14" />退出当前会话</button></section></div>

    <div v-if="toastMessage" class="toast"><span class="toast__icon"><Check :size="14" /></span>{{ toastMessage }}</div>
  </div>
</template>
