<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  ChevronDown,
  CircleHelp,
  Code2,
  Download,
  FileCode2,
  FileText,
  Filter,
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
import { api, ApiError } from './lib/api'
import type { Project, Task, TaskDraft, TaskEvent, Template, Provider, LoginProfile, TaskStatus } from './types'

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
const profiles = ref<LoginProfile[]>([])
const selectedTask = ref<Task | null>(null)
const selectedTaskEvents = ref<TaskEvent[]>([])
const draft = ref<TaskDraft | null>(null)
const chatText = ref('')
const draftBusy = ref(false)
const taskBusy = ref(false)
const showLaunch = ref(false)
const showLogin = ref(false)
const showNewProject = ref(false)
const newProjectName = ref('')
const newProjectBusy = ref(false)
const email = ref('')
const password = ref('')
const loginBusy = ref(false)
const searchQuery = ref('')
const selectedProjectId = ref('')
const selectedTemplateVersionId = ref('')
const importProjectId = ref('')
const importBusy = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const signedIn = computed(() => Boolean(api.getSession()))
const activeProject = computed(() => projects.value.find((project) => project.projectId === selectedProjectId.value) ?? projects.value[0])
const visibleTasks = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  if (!query) return tasks.value
  return tasks.value.filter((task) => `${task.type} ${task.status} ${task.taskId} ${task.projectId}`.toLowerCase().includes(query))
})
const runningCount = computed(() => tasks.value.filter((task) => task.status === 'RUNNING' || task.status === 'QUEUED').length)
const finishedCount = computed(() => tasks.value.filter((task) => task.status === 'SUCCEEDED').length)
const approvalCount = computed(() => tasks.value.filter((task) => task.status === 'WAITING_FOR_APPROVAL').length)
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
    projects.value = projectResult.value
    if (!selectedProjectId.value && projects.value[0]) selectedProjectId.value = projects.value[0].projectId
  }
  if (taskResult.status === 'fulfilled') tasks.value = taskResult.value
  if (templateResult.status === 'fulfilled') templates.value = templateResult.value
  if (providerResult.status === 'fulfilled') providers.value = providerResult.value
  if (profileResult.status === 'fulfilled') profiles.value = profileResult.value
  const failed = results.find((result) => result.status === 'rejected')
  if (failed?.status === 'rejected' && failed.reason instanceof ApiError && failed.reason.status === 401) {
    api.clearSession()
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

async function logout() {
  await api.logout()
  projects.value = []
  tasks.value = []
  showLogin.value = true
  notify('已安全退出')
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
  taskBusy.value = true
  try {
    const created = await api.createTask({
      projectId: activeProject.value.projectId,
      type: draft.value.workflowType,
      templateVersionId: selectedTemplateVersionId.value.trim(),
      parameters: draft.value.parameters,
    })
    tasks.value = [created, ...tasks.value]
    showLaunch.value = false
    draft.value = null
    chatText.value = ''
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
  try {
    selectedTaskEvents.value = await api.taskEvents(task.taskId)
  } catch {
    selectedTaskEvents.value = []
  }
}

async function openTask(task: Task) {
  selectedTask.value = task
  await refreshTaskEvents(task)
}

async function taskAction(task: Task, action: 'pause' | 'resume' | 'cancel') {
  try {
    const updated = await api.taskAction(task.taskId, action)
    tasks.value = tasks.value.map((item) => item.taskId === updated.taskId ? updated : item)
    if (selectedTask.value?.taskId === updated.taskId) selectedTask.value = updated
    await refreshTaskEvents(updated)
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

function selectSection(value: string) {
  activeSection.value = value
  selectedTask.value = null
}

function openLaunch(type?: TaskDraft['workflowType']) {
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
          <button class="icon-button" type="button" aria-label="搜索"><Search :size="18" /></button>
          <button class="icon-button" type="button" aria-label="帮助"><CircleHelp :size="18" /></button>
          <span class="topbar__divider" />
          <button class="user-menu" type="button"><span class="avatar avatar--small">A</span><span class="user-menu__name">Alex Chen</span><ChevronDown :size="14" /></button>
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

          <section class="content-section"><div class="section-head"><div><span class="section-head__eyebrow">RECENT RUNS</span><h3>最近任务</h3></div><div class="section-head__tools"><button class="icon-button" type="button" aria-label="筛选"><Filter :size="16" /></button><button class="text-button" type="button" @click="activeSection = 'tasks'">全部任务 <ArrowRight :size="14" /></button></div></div><TaskTable :tasks="tasks.slice(0, 6)" @open="openTask" @action="taskAction" /></section>
        </template>

        <template v-else-if="activeSection === 'projects'">
          <section class="toolbar-card"><div class="toolbar-card__copy"><div class="toolbar-card__icon"><FolderPlus :size="19" /></div><div><strong>导入一个项目</strong><span>支持 Git URL 或 ZIP，导入后会生成不可变源码版本</span></div></div><div class="toolbar-card__actions"><button class="button button--quiet" type="button" :disabled="!projects.length" @click="pickImport(activeProject?.projectId ?? '')"><Upload :size="16" />上传 ZIP</button><button class="button button--primary" type="button" @click="showNewProject = true"><Plus :size="16" />新建项目</button><input ref="fileInput" class="visually-hidden" type="file" accept=".zip,application/zip" @change="importZip" /></div></section>
          <section class="content-section"><div class="section-head"><div><span class="section-head__eyebrow">PROJECT SPACES</span><h3>你的项目</h3></div><div class="search-box"><Search :size="15" /><input v-model="searchQuery" placeholder="搜索项目" /></div></div><div class="project-grid"><ProjectCard v-for="(project, index) in projects" :key="project.projectId" :project="project" :accent="['#7367f0', '#23b7a4', '#f09a55', '#db6c99'][index % 4]" @open="selectedProjectId = project.projectId; openLaunch()" /><div v-if="projects.length === 0" class="empty-card"><div class="empty-card__icon"><FolderPlus :size="22" /></div><strong>创建第一个项目空间</strong><span>导入源码后，文档和截图任务会绑定到具体 revision</span><button class="button button--primary" type="button" @click="showNewProject = true">新建项目</button></div></div></section>
        </template>

        <template v-else-if="activeSection === 'tasks'">
          <section class="content-section content-section--full"><div class="section-head"><div><span class="section-head__eyebrow">TASK HISTORY</span><h3>全部任务</h3></div><div class="section-head__tools"><div class="search-box"><Search :size="15" /><input v-model="searchQuery" placeholder="搜索任务" /></div><button class="button button--primary" type="button" @click="openLaunch()"><Plus :size="16" />新建任务</button></div></div><TaskTable :tasks="visibleTasks" @open="openTask" @action="taskAction" /></section>
          <section class="content-section"><div class="section-head"><div><span class="section-head__eyebrow">TASK STATES</span><h3>状态说明</h3></div></div><div class="status-guide"><div v-for="status in (['QUEUED', 'RUNNING', 'WAITING_FOR_APPROVAL', 'PAUSED', 'SUCCEEDED', 'FAILED'] as TaskStatus[])" :key="status"><span class="status-pill" :class="`status-pill--${status.toLowerCase()}`"><span class="status-pill__dot" />{{ statusLabels[status] }}</span><small>{{ status === 'WAITING_FOR_APPROVAL' ? '等待用户选择或文字确认' : status === 'SUCCEEDED' ? '产物已登记并可预览' : status === 'FAILED' ? '保留失败 marker 和错误码' : '任务生命周期状态' }}</small></div></div></section>
        </template>

        <template v-else-if="activeSection === 'templates' || activeSection === 'providers' || activeSection === 'profiles'">
          <section class="library-banner"><div class="library-banner__glow" /><div><span class="section-head__eyebrow">CONFIGURABLE LIBRARY</span><h2>{{ pageTitle }}</h2><p>把规范沉淀成可复用配置，任务只引用不可变版本。</p></div><button class="button button--dark" type="button" @click="notify('编辑器将在下一步接入对应 API')"><Settings2 :size="16" />管理配置</button></section>
          <section class="library-grid" v-if="activeSection === 'templates'"><article v-for="template in templates" :key="template.id" class="library-card"><div class="library-card__icon library-card__icon--purple"><FileText :size="19" /></div><div class="library-card__body"><div class="library-card__title"><strong>{{ template.name }}</strong><span class="visibility-tag" :class="template.visibility === 'PUBLIC' ? 'visibility-tag--public' : ''">{{ template.visibility === 'PUBLIC' ? '公共' : '个人' }}</span></div><p>Skill {{ template.skillId.slice(0, 8) }} · {{ template.latestVersion ? `v${template.latestVersion}` : '尚无版本' }}</p><div class="library-card__footer"><span>声明式结构 · 无脚本</span><ArrowUpRight :size="15" /></div></div></article><div v-if="templates.length === 0" class="empty-card"><div class="empty-card__icon"><FileText :size="22" /></div><strong>还没有模板</strong><span>创建一个可编辑的 Markdown 或 HTML 输出规范</span></div></section>
          <section class="library-grid" v-else-if="activeSection === 'providers'"><article v-for="provider in providers" :key="provider.id" class="library-card"><div class="library-card__icon library-card__icon--green"><Sparkles :size="19" /></div><div class="library-card__body"><div class="library-card__title"><strong>{{ provider.name }}</strong><span v-if="provider.isDefault" class="visibility-tag visibility-tag--default">默认</span></div><p>{{ provider.providerType }} · {{ provider.baseUrl }}</p><div class="library-card__footer"><span>{{ provider.maskedApiKey ?? '密钥已加密保存' }}</span><ArrowUpRight :size="15" /></div></div></article><div v-if="providers.length === 0" class="empty-card"><div class="empty-card__icon"><Sparkles :size="22" /></div><strong>配置你的第一个模型</strong><span>每位用户单独保存 Provider 和默认模型</span></div></section>
          <section class="library-grid" v-else><article v-for="profile in profiles" :key="profile.id" class="library-card"><div class="library-card__icon library-card__icon--orange"><UserRound :size="19" /></div><div class="library-card__body"><div class="library-card__title"><strong>{{ profile.name }}</strong><span class="visibility-tag visibility-tag--private">私有</span></div><p>{{ profile.reference }} · {{ profile.loginUrl }}</p><div class="library-card__footer"><span>结构化语义定位器 · 凭证加密</span><ArrowUpRight :size="15" /></div></div></article><div v-if="profiles.length === 0" class="empty-card"><div class="empty-card__icon"><UserRound :size="22" /></div><strong>添加一个登录档案</strong><span>截图任务可以在管理员和成员登录态之间切换</span></div></section>
        </template>

        <template v-else>
          <section class="settings-grid"><article class="settings-card"><div class="settings-card__icon"><ShieldCheck :size="19" /></div><h3>数据边界</h3><p>项目默认私有。管理员不会自动读取成员的源码、任务日志或产物，分享由项目所有者显式授权。</p><span class="settings-card__status"><Check :size="14" />策略已启用</span></article><article class="settings-card"><div class="settings-card__icon settings-card__icon--blue"><LockKeyhole :size="19" /></div><h3>密钥保护</h3><p>Provider Key 和 Login Profile 凭证在 Platform API 加密保存，worker 只获得任务范围内的短时凭证。</p><span class="settings-card__status"><Check :size="14" />AES-GCM</span></article><article class="settings-card"><div class="settings-card__icon settings-card__icon--orange"><Image :size="19" /></div><h3>产物版本</h3><p>文档、HTML、截图和验证报告都以不可变版本登记，可预览、下载或以新版本修复。</p><span class="settings-card__status"><Check :size="14" />Immutable</span></article></section>
        </template>
      </div>
    </main>

    <aside v-if="selectedTask" class="task-inspector">
      <div class="task-inspector__head"><div><span class="section-head__eyebrow">TASK DETAIL</span><h3>{{ typeLabels[selectedTask.type] }}</h3></div><button class="icon-button" type="button" aria-label="关闭任务详情" @click="selectedTask = null"><X :size="18" /></button></div>
      <div class="task-inspector__status"><span class="status-pill" :class="`status-pill--${selectedTask.status.toLowerCase()}`"><span class="status-pill__dot" />{{ statusLabels[selectedTask.status] }}</span><span class="muted">{{ selectedTask.taskId.slice(0, 12) }}</span></div>
      <div class="inspector-actions"><button v-if="selectedTask.status === 'RUNNING'" class="button button--quiet" type="button" @click="taskAction(selectedTask, 'pause')"><Menu :size="15" />暂停</button><button v-if="selectedTask.status === 'PAUSED'" class="button button--quiet" type="button" @click="taskAction(selectedTask, 'resume')"><ArrowRight :size="15" />恢复</button><button v-if="['RUNNING', 'QUEUED', 'PAUSED', 'WAITING_FOR_APPROVAL'].includes(selectedTask.status)" class="button button--danger" type="button" @click="taskAction(selectedTask, 'cancel')"><X :size="15" />取消</button></div>
      <div v-if="selectedTask.status === 'WAITING_FOR_APPROVAL'" class="approval-box"><div class="approval-box__icon"><LockKeyhole :size="17" /></div><div><strong>需要人工确认</strong><p>当前任务暂停在一个需要选择的步骤。确认后会从 checkpoint 继续。</p><div class="approval-box__actions"><button class="button button--primary" type="button" @click="notify('审批 API 接通后将在这里提交')"><Check :size="15" />确认继续</button><button class="button button--quiet" type="button">查看上下文</button></div></div></div>
      <div class="inspector-block"><span class="section-head__eyebrow">EVENT STREAM</span><div class="event-list"><div v-for="event in selectedTaskEvents" :key="event.sequence" class="event-item"><span class="event-item__line" /><div><strong>{{ event.message ?? event.type }}</strong><small>{{ event.status }} · #{{ event.sequence }}</small></div></div><div v-if="selectedTaskEvents.length === 0" class="event-empty"><LoaderCircle :size="16" />等待事件</div></div></div>
      <div v-if="selectedTask.resultReference" class="artifact-preview"><div class="artifact-preview__top"><span><FileCode2 :size="15" />产物已生成</span><button class="icon-button" type="button" aria-label="下载产物"><Download :size="15" /></button></div><div class="artifact-preview__body"><div class="artifact-preview__file"><FileText :size="18" /><span>{{ selectedTask.resultReference }}</span></div><div class="artifact-preview__hint">打开预览或下载当前不可变版本</div></div></div>
    </aside>

    <div v-if="showLaunch" class="drawer-layer" @click.self="showLaunch = false"><section class="launch-drawer"><div class="launch-drawer__head"><div><span class="section-head__eyebrow">NEW WORKFLOW</span><h2>描述你想交付的结果</h2><p>先生成草稿，确认后才会创建任务。</p></div><button class="icon-button" type="button" aria-label="关闭" @click="showLaunch = false"><X :size="19" /></button></div><div class="launch-drawer__body"><div class="chat-box"><div class="chat-box__label"><Sparkles :size="15" />自然语言目标</div><textarea v-model="chatText" placeholder="例如：为这个项目生成一份面向维护者的项目文档，并为管理员用户列表添加截图…" @keydown.meta.enter="parseDraft" @keydown.ctrl.enter="parseDraft" /><div class="chat-box__footer"><span>Enter 发送 · ⌘↵ 解析草稿</span><button class="send-button" type="button" :disabled="draftBusy || !chatText.trim()" @click="parseDraft"><LoaderCircle v-if="draftBusy" class="spin" :size="16" /><Send v-else :size="16" /></button></div></div><div v-if="draft" class="draft-card"><div class="draft-card__top"><div class="draft-type"><span class="draft-type__icon"><FileText v-if="draft.workflowType !== 'HTML' && draft.workflowType !== 'SCREENSHOT'" :size="15" /><Code2 v-else-if="draft.workflowType === 'HTML'" :size="15" /><Image v-else :size="15" /></span><div><span class="section-head__eyebrow">EDITABLE DRAFT</span><strong>{{ typeLabels[draft.workflowType] }}</strong></div></div><span class="draft-ready"><Check :size="13" />未启动</span></div><p>{{ draft.summary }}</p><div class="draft-fields"><label>项目空间<select v-model="selectedProjectId"><option value="" disabled>选择项目</option><option v-for="project in projects" :key="project.projectId" :value="project.projectId">{{ project.name }}</option></select></label><label>模板版本 ID<input v-model="selectedTemplateVersionId" placeholder="粘贴 immutable version UUID" /></label></div><div class="draft-params"><span v-for="(value, key) in draft.parameters" :key="key" class="param-chip"><b>{{ key }}</b>{{ value }}</span></div><div class="draft-card__actions"><button class="button button--quiet" type="button" @click="draft = null">重新编辑</button><button class="button button--primary" type="button" :disabled="taskBusy" @click="launchTask"><LoaderCircle v-if="taskBusy" class="spin" :size="15" />确认并创建任务 <ArrowRight v-if="!taskBusy" :size="15" /></button></div></div><div v-else class="suggestions"><span>试试这些目标</span><button type="button" @click="chatText = '生成项目文档，包含源码结构和关键入口'; parseDraft()"><FileText :size="15" />项目文档</button><button type="button" @click="chatText = '生成用户操作手册，并为管理员菜单添加截图'; parseDraft()"><UserRound :size="15" />用户手册</button><button type="button" @click="chatText = '生成一个响应式 HTML 项目介绍页面'; parseDraft()"><Code2 :size="15" />HTML 页面</button><button type="button" @click="chatText = '截图管理员登录后的用户列表'; parseDraft()"><Image :size="15" />自动截图</button></div></div></section></div>

    <div v-if="showNewProject" class="modal-layer" @click.self="showNewProject = false"><section class="modal-card"><div class="modal-card__head"><div><span class="section-head__eyebrow">PROJECT SPACE</span><h2>创建项目</h2></div><button class="icon-button" type="button" aria-label="关闭" @click="showNewProject = false"><X :size="18" /></button></div><label class="field-label">项目名称<input v-model="newProjectName" autofocus placeholder="例如：Customer Portal" @keydown.enter="createProject" /></label><p class="modal-card__hint">创建后请导入 Git URL 或 ZIP 源码版本。项目与任务默认只对你可见。</p><div class="modal-card__actions"><button class="button button--quiet" type="button" @click="showNewProject = false">取消</button><button class="button button--primary" type="button" :disabled="newProjectBusy" @click="createProject"><LoaderCircle v-if="newProjectBusy" class="spin" :size="15" />创建项目</button></div></section></div>

    <div v-if="showLogin" class="auth-layer"><section class="auth-card"><div class="auth-card__brand"><div class="brand-mark"><Sparkles :size="17" /></div><span>Agent Studio</span></div><div class="auth-card__intro"><span class="page-heading__kicker">PRIVATE WORKSPACE</span><h1>连接你的工作台</h1><p>登录后才能读取项目、模板、任务和私有 Provider 配置。</p></div><form @submit.prevent="login"><label class="field-label">邮箱<input v-model="email" type="email" autocomplete="username" placeholder="you@example.com" required /></label><label class="field-label">密码<input v-model="password" type="password" autocomplete="current-password" placeholder="输入密码" required /></label><button class="button button--primary button--wide" type="submit" :disabled="loginBusy"><LoaderCircle v-if="loginBusy" class="spin" :size="16" />{{ loginBusy ? '正在连接…' : '登录工作区' }}<ArrowRight v-if="!loginBusy" :size="16" /></button></form><div class="auth-card__foot"><LockKeyhole :size="14" />会话由 Platform API 管理 · 默认不共享私有数据</div><button v-if="signedIn" class="text-button auth-card__logout" type="button" @click="logout"><LogOut :size="14" />退出当前会话</button></section></div>

    <div v-if="toastMessage" class="toast"><span class="toast__icon"><Check :size="14" /></span>{{ toastMessage }}</div>
  </div>
</template>
