<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ArrowRight, Blocks, Check, Code2, Eye, Plus, ScanLine, X } from '../lib/icons'
import { api, ApiError } from '../lib/api'
import MarkdownIt from 'markdown-it'
import type { LoginProfile, TemplateVersionInput } from '../types'

type BlockKind = 'heading' | 'paragraph' | 'generated' | 'changes' | 'images' | 'screenshot'
type ActionType = 'click' | 'fill' | 'select' | 'check' | 'uncheck' | 'wait'
interface Action { id: string; type: ActionType; kind: 'role' | 'label' | 'test-id'; role: string; name: string; value: string }
interface ContentBlock { id: string; kind: BlockKind; text: string; level: number; loginProfileRef: string; target: string; caption: string; routePath: string; menuPath: string[]; actions: Action[] }
interface Field { id: string; key: string; label: string; type: 'string' | 'number' | 'boolean'; defaultValue: string; required: boolean }
interface EditorState { blocks: ContentBlock[]; fields: Field[] }

const props = defineProps<{ templateId?: string }>()
const emit = defineEmits<{ loading: [value: boolean] }>()
const format = ref<'MARKDOWN' | 'HTML'>('MARKDOWN')
const addKind = ref<BlockKind>('paragraph')
const profiles = ref<LoginProfile[]>([])
const profileError = ref('')
const loadError = ref('')
const loading = ref(false)
const useRaw = ref(false)
const rawContent = ref('')
const css = ref('')
const useJson = ref(false)
const advancedJson = ref('')
const attempted = ref(false)
const markdown = new MarkdownIt({ html: false, linkify: false })
let loadSequence = 0

function newBlock(kind: BlockKind): ContentBlock {
  return { id: `shot-${crypto.randomUUID().slice(0, 8)}`, kind, text: kind === 'heading' ? '项目文档' : '', level: 2, loginProfileRef: profiles.value[0]?.reference ?? '', target: '', caption: '', routePath: '', menuPath: [], actions: [] }
}
const state = reactive<EditorState>({ blocks: [{ ...newBlock('heading'), text: '{{title}}', level: 1 }, newBlock('generated')], fields: [] })
const blockLabels: Record<BlockKind, string> = { heading: '标题', paragraph: '说明文字', generated: '生成正文', changes: '源码变更摘要', images: '全部任务截图', screenshot: '页面截图' }

function move<T>(items: T[], index: number, direction: number) {
  const next = index + direction
  if (next < 0 || next >= items.length) return
  const item = items.splice(index, 1)[0]
  if (item) items.splice(next, 0, item)
}
function addBlock() { if (state.blocks.length < 40) state.blocks.push(newBlock(addKind.value)) }
function addField() {
  if (state.fields.length < 30) state.fields.push({ id: crypto.randomUUID(), key: `field_${state.fields.length + 1}`, label: '', type: 'string', defaultValue: '', required: false })
}
function addAction(block: ContentBlock) {
  if (block.actions.length < 10) block.actions.push({ id: crypto.randomUUID(), type: 'click', kind: 'role', role: 'button', name: '', value: '' })
}
function updateActionType(action: Action) {
  action.role = action.type === 'fill' ? 'textbox' : action.type === 'select' ? 'combobox' : ['check', 'uncheck'].includes(action.type) ? 'checkbox' : 'button'
}
function marker(block: ContentBlock) {
  const value = { id: block.id, loginProfileRef: block.loginProfileRef, target: block.target.trim(), caption: (block.caption || block.target).trim(), ...(block.routePath.trim() ? { routePath: block.routePath.trim() } : {}), menuPath: block.menuPath.map((value) => value.trim()), actions: block.actions.map((action) => ({ type: action.type, locator: { kind: action.kind, name: action.name.trim(), ...(action.kind === 'role' ? { role: action.role.trim() } : {}) }, ...(['fill', 'select'].includes(action.type) ? { value: action.value } : {}) })) }
  return `<!-- agent-studio:screenshot:v1 ${JSON.stringify(value).replace(/</g, '\\u003c').replace(/>/g, '\\u003e')} -->`
}
function escape(value: string) { return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;') }
const visualContent = computed(() => {
  const content = state.blocks.map((block) => {
    if (block.kind === 'screenshot') return marker(block)
    const text = block.kind === 'generated' ? '{{content}}' : block.kind === 'changes' ? '{{sourceChangeSummary}}' : block.kind === 'images' ? '{{screenshots}}' : block.text
    if (format.value === 'MARKDOWN') return block.kind === 'heading' ? `${'#'.repeat(block.level)} ${text}` : text
    return block.kind === 'heading' ? `<h${block.level}>${escape(text)}</h${block.level}>` : `<section>${text.startsWith('{{') ? text : `<p>${escape(text).replace(/\n/g, '<br>')}</p>`}</section>`
  }).join('\n\n')
  return format.value === 'HTML' ? `<main>\n${content}\n</main>` : content
})
const compiledContent = computed(() => useRaw.value ? rawContent.value : visualContent.value)
function toggleRaw() { if (useRaw.value && !rawContent.value) rawContent.value = visualContent.value }
function toggleJson() {
  if (useJson.value && !advancedJson.value) advancedJson.value = JSON.stringify({ parameterSchema: schema.value, allowedSections: [], validationRules: {} }, null, 2)
}
const schema = computed(() => {
  const properties: Record<string, unknown> = {}
  state.fields.forEach((field) => {
    const value = field.type === 'boolean' ? field.defaultValue === 'true' : field.type === 'number' ? Number(field.defaultValue) : field.defaultValue
    properties[field.key] = { type: field.type, title: field.label.trim(), ...(field.defaultValue !== '' ? { default: value } : {}) }
  })
  return { type: 'object', properties, required: state.fields.filter((field) => field.required).map((field) => field.key), additionalProperties: true }
})
const issues = computed(() => {
  const result: Record<string, string> = {}
  if (!compiledContent.value.trim()) result.content = '至少保留一个内容区块'
  if (format.value === 'HTML' && /<script\b|\son\w+\s*=|javascript\s*:/i.test(compiledContent.value)) result.content = '模板不能包含脚本或事件代码'
  if (format.value === 'MARKDOWN' && /\{\{(?!title}}|content}}|screenshots}}|sourceChangeSummary}})[^{}]+}}/.test(compiledContent.value)) result.content = '正文只支持标题、生成正文、变更摘要和截图占位'
  const markerIds = new Set<string>()
  state.blocks.forEach((block) => {
    if (useRaw.value) return
    if (['heading', 'paragraph'].includes(block.kind) && !block.text.trim()) result[block.id] = '填写区块内容'
    if (block.kind === 'screenshot') {
      if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(block.id) || markerIds.has(block.id)) result[block.id] = '截图标识无效或重复，请重新添加区块'
      else if (!block.loginProfileRef) result[block.id] = '选择登录档案'
      else if (!block.target.trim() || block.target.length > 240) result[block.id] = '目标文本为 1–240 个字符'
      else if ((block.caption || block.target).length > 240) result[block.id] = '截图说明不能超过 240 个字符'
      else if (block.routePath.length > 512 || (block.routePath && (!block.routePath.startsWith('/') || block.routePath.startsWith('//')))) result[block.id] = '路径应以 / 开头'
      else if (block.menuPath.some((value) => !value.trim() || value.length > 120)) result[block.id] = '填写每一级菜单，最多 120 个字符'
      block.actions.forEach((action) => { if (!action.name.trim() || action.name.length > 160 || (action.kind === 'role' && (!action.role.trim() || action.role.length > 80)) || (['fill', 'select'].includes(action.type) && (!action.value.trim() || action.value.length > 500))) result[action.id] = '补全操作目标和内容' })
      markerIds.add(block.id)
    }
  })
  const keys = new Set<string>()
  state.fields.forEach((field) => {
    if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(field.key) || keys.has(field.key)) result[field.id] = '参数名以字母开头，且不能重复'
    else if (!field.label.trim()) result[field.id] = '填写字段名称'
    else if (field.type === 'number' && field.defaultValue !== '' && !Number.isFinite(Number(field.defaultValue))) result[field.id] = '默认值应为数字'
    keys.add(field.key)
  })
  if (useJson.value) {
    try { parseAdvanced() } catch { result.json = '填写有效的配置 JSON 对象' }
  }
  return result
})
function parseAdvanced(): Partial<TemplateVersionInput> {
  const value: unknown = JSON.parse(advancedJson.value)
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('JSON object required')
  const record = value as Record<string, unknown>
  for (const key of Object.keys(record)) if (!['parameterSchema', 'formLayout', 'allowedSections', 'validationRules'].includes(key)) throw new Error('unknown configuration')
  if (record.allowedSections !== undefined && !Array.isArray(record.allowedSections)) throw new Error('sections required')
  for (const key of ['parameterSchema', 'formLayout', 'validationRules']) if (record[key] !== undefined && (!record[key] || typeof record[key] !== 'object' || Array.isArray(record[key]))) throw new Error('configuration object required')
  return record
}
function buildVersion(): TemplateVersionInput {
  attempted.value = true
  if (loading.value || loadError.value) throw new ApiError(loadError.value || '模板版本正在读取', 400)
  const first = Object.values(issues.value)[0]
  if (first) throw new ApiError(first, 400)
  const advanced = useJson.value ? parseAdvanced() : {}
  const editorSnapshot = JSON.parse(JSON.stringify({ version: 1, blocks: state.blocks, fields: state.fields, useRaw: useRaw.value, useJson: useJson.value, advancedJson: useJson.value ? advancedJson.value : '' }))
  return { outputFormat: format.value, parameterSchema: schema.value, allowedSections: state.blocks.filter((block) => block.kind === 'heading').map((block) => block.text), validationRules: {}, ...advanced, formLayout: { ...(advanced.formLayout as Record<string, unknown> ?? {}), studioEditor: editorSnapshot }, markdownTemplate: format.value === 'MARKDOWN' ? compiledContent.value : null, htmlTemplate: format.value === 'HTML' ? compiledContent.value : null, css: format.value === 'HTML' ? css.value : null }
}
defineExpose({ buildVersion })

function sample(value: string) { return value.replace(/\{\{title}}/g, '项目文档').replace(/\{\{content}}/g, '这里将展示根据源码生成的正文。').replace(/\{\{sourceChangeSummary}}/g, '这里将展示本次源码变更摘要。').replace(/\{\{screenshots}}/g, '这里将展示任务截图。') }
function markdownPreview(value: string) {
  const figures: string[] = []
  const prepared = value.replace(/<!-- agent-studio:screenshot:v1\s+\{.*?}\s*-->/gs, () => {
    const token = `STUDIO_SCREENSHOT_PREVIEW_${figures.length}`
    figures.push(token)
    return `\n\n${token}\n\n`
  })
  let rendered = markdown.render(prepared)
  figures.forEach((token) => { rendered = rendered.replace(`<p>${token}</p>`, '<figure class="shot"><span>页面截图占位</span><small>运行后自动替换为图片</small></figure>') })
  return rendered
}
const previewDocument = computed(() => {
  let body = useRaw.value ? format.value === 'HTML' ? sample(compiledContent.value) : markdownPreview(sample(compiledContent.value)) : state.blocks.map((block) => {
    if (block.kind === 'screenshot') return `<figure class="shot"><span>${escape(block.caption || block.target || '页面截图')}</span><small>${escape(block.loginProfileRef || '选择登录档案')} · 运行后替换为图片</small></figure>`
    const text = block.kind === 'generated' ? '这里将展示根据源码生成的正文。' : block.kind === 'changes' ? '这里将展示本次源码变更摘要。' : block.kind === 'images' ? '这里将展示任务完成后的截图。' : sample(block.text)
    return block.kind === 'heading' ? `<h${block.level}>${escape(text)}</h${block.level}>` : format.value === 'MARKDOWN' ? markdown.render(text) : `<p>${escape(text).replace(/\n/g, '<br>')}</p>`
  }).join('')
  const policy = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'"
  return `<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="${policy}"><meta charset="utf-8"><style>html{color:#292739;background:#fff;font:14px/1.8 system-ui,sans-serif}body{margin:0;padding:26px}h1{font-size:27px;line-height:1.2;letter-spacing:-.04em}h2{font-size:20px;margin-top:30px}h3{font-size:16px}p{color:#686577}.shot{display:flex;min-height:140px;flex-direction:column;align-items:center;justify-content:center;gap:8px;margin:22px 0;padding:16px;border:1px dashed #bcb4e8;border-radius:12px;color:#6558a0;background:#f6f4ff}.shot small{font-size:11px;color:#91899e}${format.value === 'HTML' ? css.value : ''}</style></head><body>${body}</body></html>`
})

function isStoredState(value: unknown): value is EditorState {
  if (!value || typeof value !== 'object') return false
  const record = value as EditorState
  return Array.isArray(record.blocks) && record.blocks.length <= 40 && record.blocks.every((block) => block && typeof block.id === 'string' && ['heading','paragraph','generated','changes','images','screenshot'].includes(block.kind) && typeof block.text === 'string' && Number.isInteger(block.level) && block.level >= 1 && block.level <= 6 && typeof block.loginProfileRef === 'string' && typeof block.target === 'string' && typeof block.caption === 'string' && typeof block.routePath === 'string' && Array.isArray(block.menuPath) && block.menuPath.length <= 6 && block.menuPath.every((value) => typeof value === 'string') && Array.isArray(block.actions) && block.actions.length <= 10 && block.actions.every((action) => action && typeof action.id === 'string' && ['click','fill','select','check','uncheck','wait'].includes(action.type) && ['role','label','test-id'].includes(action.kind) && typeof action.name === 'string' && typeof action.role === 'string' && typeof action.value === 'string')) && Array.isArray(record.fields) && record.fields.length <= 30 && record.fields.every((field) => field && typeof field.id === 'string' && typeof field.key === 'string' && typeof field.label === 'string' && ['string','number','boolean'].includes(field.type) && typeof field.defaultValue === 'string' && typeof field.required === 'boolean')
}
function importMarkdown(content: string): ContentBlock[] | null {
  if (content.includes('<!-- agent-studio:')) return null
  const parts = content.trim().split(/\n\n+/)
  if (!content.trim() || parts.length > 40) return null
  return parts.map((part) => {
    if (part === '{{content}}') return newBlock('generated')
    if (part === '{{sourceChangeSummary}}') return newBlock('changes')
    if (part === '{{screenshots}}') return newBlock('images')
    const heading = /^(#{1,6})\s+([^\n]+)$/.exec(part)
    return heading ? { ...newBlock('heading'), level: heading[1].length, text: heading[2] } : { ...newBlock('paragraph'), text: part }
  })
}
watch(() => props.templateId, async (id) => {
  const sequence = ++loadSequence
  loadError.value = ''
  attempted.value = false
  if (!id) { loading.value = false; emit('loading', false); return }
  loading.value = true
  emit('loading', true)
  try {
    const versions = await api.templateVersions(id)
    if (sequence !== loadSequence) return
    const version = [...versions].sort((a, b) => b.ordinal - a.ordinal)[0]
    if (!version) { state.blocks = [{ ...newBlock('heading'), text: '{{title}}', level: 1 }, newBlock('generated')]; state.fields = []; rawContent.value = ''; css.value = ''; useRaw.value = false; useJson.value = false; return }
    format.value = version.outputFormat === 'HTML' ? 'HTML' : 'MARKDOWN'
    css.value = version.css ?? ''
    rawContent.value = version.htmlTemplate ?? version.markdownTemplate ?? ''
    const layout = version.formLayout as { studioEditor?: EditorState & { version?: number; useRaw?: boolean; useJson?: boolean; advancedJson?: string } } | undefined
    if (layout?.studioEditor?.version === 1 && isStoredState(layout.studioEditor)) {
      state.blocks = structuredClone(layout.studioEditor.blocks)
      state.fields = structuredClone(layout.studioEditor.fields)
      useRaw.value = Boolean(layout.studioEditor.useRaw)
      useJson.value = Boolean(layout.studioEditor.useJson)
    } else {
      const imported = format.value === 'MARKDOWN' ? importMarkdown(rawContent.value) : null
      useRaw.value = !imported
      state.blocks = imported ?? [{ ...newBlock('heading'), text: '{{title}}', level: 1 }, newBlock('generated')]
      state.fields = []
      useJson.value = true
    }
    advancedJson.value = typeof layout?.studioEditor?.advancedJson === 'string' && layout.studioEditor.advancedJson ? layout.studioEditor.advancedJson : JSON.stringify({ parameterSchema: version.parameterSchema ?? {}, formLayout: version.formLayout ?? {}, allowedSections: version.allowedSections ?? [], validationRules: version.validationRules ?? {} }, null, 2)
  } catch (reason) {
    if (sequence === loadSequence) loadError.value = reason instanceof ApiError ? reason.message : '模板读取失败，请重新选择'
  } finally {
    if (sequence === loadSequence) { loading.value = false; emit('loading', false) }
  }
}, { immediate: true })
watch(format, () => { if (!useRaw.value) rawContent.value = compiledContent.value })
onMounted(async () => {
  try { profiles.value = await api.loginProfiles() } catch { profileError.value = '登录档案暂时无法读取' }
})
</script>

<template>
  <div class="template-editor">
    <div class="template-editor__toolbar"><div class="config-segment"><button type="button" :class="{active: format === 'MARKDOWN'}" @click="format = 'MARKDOWN'">Markdown</button><button type="button" :class="{active: format === 'HTML'}" @click="format = 'HTML'">HTML</button></div><span class="editor-help">修改会保存为新版本</span></div>
    <div v-if="loadError" class="config-error" role="alert">{{ loadError }}</div>
    <div v-if="loading" class="editor-loading">正在读取模板…</div>
    <div v-else class="template-editor__layout">
      <div class="template-editor__controls">
        <section class="editor-section">
          <div class="editor-section__head"><div><span class="editor-step">01</span><h3>内容区块</h3></div><span>{{ state.blocks.length }}/40</span></div>
          <div v-if="useRaw" class="editor-notice">当前原文已保留。<button class="text-button" type="button" @click="useRaw = false; useJson = false">使用区块重新设计</button></div>
          <div v-if="!useRaw" class="editor-blocks">
            <details v-for="(block, index) in state.blocks" :key="block.id" class="editor-block" open>
              <summary><span class="editor-block__number">{{ String(index + 1).padStart(2, '0') }}</span><ScanLine v-if="block.kind === 'screenshot'" :size="14" /><Blocks v-else :size="14" /><strong>{{ blockLabels[block.kind] }}</strong><span class="editor-row-actions"><button class="icon-button" type="button" :disabled="index === 0" aria-label="区块上移" @click.prevent="move(state.blocks,index,-1)"><ArrowRight class="rotate-up" :size="13" /></button><button class="icon-button" type="button" :disabled="index === state.blocks.length-1" aria-label="区块下移" @click.prevent="move(state.blocks,index,1)"><ArrowRight class="rotate-down" :size="13" /></button><button class="icon-button editor-delete" type="button" aria-label="删除区块" @click.prevent="state.blocks.splice(index,1)"><X :size="13" /></button></span></summary>
              <div class="editor-block__body">
                <div v-if="block.kind === 'heading'" class="editor-heading-fields"><label class="field-label">标题文字<input v-model="block.text" maxlength="240" placeholder="例如：部署与运行" /></label><label class="field-label">层级<select v-model.number="block.level"><option v-for="level in 6" :key="level" :value="level">H{{level}}</option></select></label></div>
                <label v-else-if="block.kind === 'paragraph'" class="field-label">说明文字<textarea v-model="block.text" rows="3" maxlength="12000" placeholder="补充固定说明或交付要求" /></label>
                <div v-else-if="block.kind === 'generated' || block.kind === 'changes' || block.kind === 'images'" class="editor-token"><Code2 :size="15" /><span>{{block.kind === 'generated' ? '任务完成后填入生成正文' : block.kind === 'images' ? '任务完成后填入全部截图' : '任务完成后填入源码变更摘要'}}</span><Check :size="13" /></div>
                <template v-else-if="block.kind === 'screenshot'">
                  <div v-if="profileError" class="editor-inline-error">{{profileError}}</div>
                  <label class="field-label">登录档案<select v-model="block.loginProfileRef"><option value="">选择登录态</option><option v-for="item in profiles" :key="item.id" :value="item.reference">{{item.name}}</option></select><small v-if="!profiles.length">先在登录档案中添加一个账号</small></label>
                  <div class="form-grid"><label class="field-label">目标文本<input v-model="block.target" maxlength="240" placeholder="例如：用户列表" /></label><label class="field-label">截图说明<input v-model="block.caption" maxlength="240" placeholder="默认与目标文本相同" /></label></div>
                  <label class="field-label">页面路径（可选）<input v-model="block.routePath" maxlength="512" placeholder="/admin/users" /></label>
                  <div class="editor-subsection"><div class="editor-subsection__head"><strong>菜单路径</strong><button class="text-button" type="button" :disabled="block.menuPath.length >= 6" @click="block.menuPath.push('')"><Plus :size="12" />添加一级</button></div><div v-for="(_, menuIndex) in block.menuPath" :key="menuIndex" class="editor-menu-row"><span>{{menuIndex+1}}</span><input v-model="block.menuPath[menuIndex]" maxlength="120" aria-label="菜单文字" placeholder="菜单显示的文字" /><button class="icon-button" type="button" aria-label="移除菜单" @click="block.menuPath.splice(menuIndex,1)"><X :size="12" /></button></div><small v-if="!block.menuPath.length" class="editor-help">直接进入页面时可留空</small></div>
                  <div class="editor-subsection"><div class="editor-subsection__head"><strong>页面操作</strong><button class="text-button" type="button" :disabled="block.actions.length >= 10" @click="addAction(block)"><Plus :size="12" />添加操作</button></div>
                    <div v-for="(action, actionIndex) in block.actions" :key="action.id" class="editor-action">
                      <div class="editor-action__head"><span>操作 {{actionIndex+1}}</span><div class="editor-row-actions"><button class="icon-button" type="button" :disabled="actionIndex === 0" aria-label="操作上移" @click="move(block.actions,actionIndex,-1)"><ArrowRight class="rotate-up" :size="12" /></button><button class="icon-button" type="button" :disabled="actionIndex === block.actions.length-1" aria-label="操作下移" @click="move(block.actions,actionIndex,1)"><ArrowRight class="rotate-down" :size="12" /></button><button class="icon-button editor-delete" type="button" aria-label="删除操作" @click="block.actions.splice(actionIndex,1)"><X :size="12" /></button></div></div>
                      <div class="form-grid"><label class="field-label">动作<select v-model="action.type" @change="updateActionType(action)"><option value="click">点击</option><option value="fill">填写</option><option value="select">选择</option><option value="check">勾选</option><option value="uncheck">取消勾选</option><option value="wait">等待出现</option></select></label><label class="field-label">定位方式<select v-model="action.kind"><option value="role">角色与名称</option><option value="label">表单标签</option><option value="test-id">Test ID</option></select></label></div>
                      <div class="form-grid"><label v-if="action.kind === 'role'" class="field-label">角色<select v-model="action.role"><option value="button">按钮</option><option value="link">链接</option><option value="textbox">输入框</option><option value="combobox">下拉框</option><option value="checkbox">复选框</option><option value="menuitem">菜单项</option><option value="tab">标签页</option><option value="heading">标题</option></select></label><label class="field-label">目标名称<input v-model="action.name" maxlength="160" placeholder="页面上的名称" /></label></div>
                      <label v-if="action.type === 'fill' || action.type === 'select'" class="field-label">填写 / 选择内容<input v-model="action.value" maxlength="500" placeholder="使用演示数据，不填写密码" /></label><small v-if="attempted && issues[action.id]" class="editor-inline-error">{{issues[action.id]}}</small>
                    </div>
                  </div>
                </template>
                <small v-if="attempted && issues[block.id]" class="editor-inline-error">{{issues[block.id]}}</small>
              </div>
            </details>
            <div class="editor-add"><select v-model="addKind" aria-label="新增区块类型"><option v-for="(label,kind) in blockLabels" :key="kind" :value="kind">{{label}}</option></select><button class="button button--quiet" type="button" :disabled="state.blocks.length >= 40" @click="addBlock"><Plus :size="13" />添加区块</button></div><small v-if="attempted && issues.content" class="editor-inline-error">{{issues.content}}</small>
          </div>
        </section>
        <section class="editor-section">
          <div class="editor-section__head"><div><span class="editor-step">02</span><h3>任务字段</h3></div><button class="text-button" type="button" :disabled="state.fields.length >= 30" @click="useJson = false; addField()"><Plus :size="12" />添加字段</button></div>
          <small v-if="!state.fields.length" class="editor-help">为每次运行保留可填写的参数</small>
          <div v-for="(field,index) in state.fields" :key="field.id" class="editor-field">
            <div class="editor-action__head"><span>字段 {{index+1}}</span><div class="editor-row-actions"><button class="icon-button" type="button" :disabled="index === 0" aria-label="字段上移" @click="useJson = false; move(state.fields,index,-1)"><ArrowRight class="rotate-up" :size="12" /></button><button class="icon-button" type="button" :disabled="index === state.fields.length-1" aria-label="字段下移" @click="useJson = false; move(state.fields,index,1)"><ArrowRight class="rotate-down" :size="12" /></button><button class="icon-button editor-delete" type="button" aria-label="删除字段" @click="useJson = false; state.fields.splice(index,1)"><X :size="12" /></button></div></div>
            <div class="form-grid"><label class="field-label">字段名称<input v-model="field.label" maxlength="120" placeholder="例如：文档标题" @input="useJson = false" /></label><label class="field-label">参数名<input v-model="field.key" maxlength="64" placeholder="documentTitle" @input="useJson = false" /></label></div>
            <div class="form-grid"><label class="field-label">类型<select v-model="field.type" @change="field.defaultValue = ''; useJson = false"><option value="string">文字</option><option value="number">数字</option><option value="boolean">开关</option></select></label><label class="field-label">默认值<select v-if="field.type === 'boolean'" v-model="field.defaultValue" @change="useJson = false"><option value="">不预设</option><option value="true">开启</option><option value="false">关闭</option></select><input v-else v-model="field.defaultValue" :type="field.type === 'number' ? 'number' : 'text'" placeholder="可留空" @input="useJson = false" /></label></div><label class="check-field"><input v-model="field.required" type="checkbox" @change="useJson = false" />必填</label><small v-if="attempted && issues[field.id]" class="editor-inline-error">{{issues[field.id]}}</small>
          </div>
        </section>
        <details class="editor-advanced"><summary><Code2 :size="14" />高级设置<span>原文 · JSON · 样式</span></summary><div class="editor-advanced__body"><label class="check-field"><input v-model="useRaw" type="checkbox" @change="toggleRaw" />使用原文作为最终正文</label><label v-if="useRaw" class="field-label">{{format === 'HTML' ? 'HTML 原文' : 'Markdown 原文'}}<textarea v-model="rawContent" rows="8" spellcheck="false" /></label><label v-if="format === 'HTML'" class="field-label">CSS 样式<textarea v-model="css" rows="4" spellcheck="false" placeholder="main { max-width: 960px; margin: auto; }" /></label><label class="check-field"><input v-model="useJson" type="checkbox" @change="toggleJson" />使用自定义配置 JSON</label><label v-if="useJson" class="field-label">配置 JSON<textarea v-model="advancedJson" rows="8" spellcheck="false" /><small v-if="issues.json" class="editor-inline-error">{{issues.json}}</small></label></div></details>
      </div>
      <aside class="template-editor__preview"><div class="editor-preview__head"><span><Eye :size="14" />实时预览</span><span>{{format === 'HTML' ? 'HTML' : 'Markdown'}}</span></div><iframe title="模板实时预览" sandbox="" :srcdoc="previewDocument" /><div class="editor-preview__foot"><span class="live-pulse" />示例内容 · 保存时使用占位符</div></aside>
    </div>
  </div>
</template>
