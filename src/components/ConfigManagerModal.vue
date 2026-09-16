<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Check, LoaderCircle, X } from 'lucide-vue-next'
import { api, ApiError } from '../lib/api'
import type { LoginLocator, LoginProfile, Provider, Template } from '../types'

type Mode = 'templates' | 'providers' | 'profiles'

const props = defineProps<{
  mode: Mode
  templates: Template[]
  providers: Provider[]
  selectedTemplateId?: string
  selectedProviderId?: string
}>()
const emit = defineEmits<{
  close: []
  saved: [message: string]
  templateVersion: [id: string]
}>()

const busy = ref(false)
const error = ref('')
const templateAction = ref<'create' | 'version'>(props.selectedTemplateId ? 'version' : 'create')
const providerAction = ref<'create' | 'edit' | 'model'>(props.selectedProviderId ? 'edit' : 'create')
const initialProvider = props.providers.find((item) => item.id === props.selectedProviderId)
const discoveredModels = ref<Array<{ modelId: string; displayName: string }>>([])
const template = reactive({
  id: props.selectedTemplateId ?? '',
  name: '',
  skillId: '',
  publicTemplate: false,
  outputFormat: 'MARKDOWN' as 'MARKDOWN' | 'HTML',
  content: '# {{title}}\n',
  css: '',
})
const provider = reactive({
  id: props.selectedProviderId ?? '',
  name: initialProvider?.name ?? '',
  providerType: initialProvider?.providerType ?? 'OPENAI_COMPATIBLE',
  baseUrl: initialProvider?.baseUrl ?? '',
  apiKey: '',
  modelId: '',
  displayName: '',
  defaultProfile: initialProvider?.isDefault ?? true,
  defaultModel: true,
})
const profile = reactive({
  reference: '',
  name: '',
  loginUrl: '',
  loginPath: '',
  username: '',
  password: '',
  usernameKind: 'label' as LoginLocator['kind'],
  passwordKind: 'label' as LoginLocator['kind'],
  submitKind: 'role' as LoginLocator['kind'],
  usernameLabel: '用户名',
  passwordLabel: '密码',
  submitName: '登录',
  usernameRole: 'textbox',
  passwordRole: 'textbox',
  submitRole: 'button',
})

const title = computed(() => props.mode === 'templates' ? '模板版本' : props.mode === 'providers' ? '模型连接' : '登录档案')
const subtitle = computed(() => props.mode === 'templates'
  ? '模板版本创建后不可变；修改会产生一个新版本。'
  : props.mode === 'providers'
    ? '密钥只提交给 Platform API，保存后不会再次明文返回。'
    : '只使用 role、label 和 test-id 语义定位器，不执行自定义脚本。')

watch(() => props.selectedTemplateId, (value) => {
  if (value) {
    template.id = value
    templateAction.value = 'version'
  }
})

watch(() => props.selectedProviderId, (value) => {
  if (value) {
    provider.id = value
    const selected = props.providers.find((item) => item.id === value)
    if (selected) {
      provider.name = selected.name
      provider.providerType = selected.providerType
      provider.baseUrl = selected.baseUrl
      provider.defaultProfile = Boolean(selected.isDefault)
    }
    providerAction.value = 'edit'
  }
})

watch(() => provider.id, (value) => {
  if (providerAction.value !== 'edit') return
  const selected = props.providers.find((item) => item.id === value)
  if (!selected) return
  provider.name = selected.name
  provider.providerType = selected.providerType
  provider.baseUrl = selected.baseUrl
  provider.defaultProfile = Boolean(selected.isDefault)
})

function locator(kind: LoginLocator['kind'], name: string, role?: string): LoginLocator {
  return { kind, name, role: kind === 'role' ? role ?? 'button' : null }
}

async function saveTemplate() {
  let templateId = template.id
  let createdName = ''
  if (templateAction.value === 'create') {
    const created = await api.createTemplate(template.name.trim(), template.skillId.trim(), template.publicTemplate)
    templateId = created.id
    createdName = created.name
  }
  if (!templateId) throw new ApiError('请选择要创建新版本的模板', 400)
  let version
  try {
    version = await api.createTemplateVersion(templateId, {
      outputFormat: template.outputFormat,
      parameterSchema: {},
      formLayout: {},
      allowedSections: [],
      markdownTemplate: template.outputFormat === 'MARKDOWN' ? template.content : null,
      htmlTemplate: template.outputFormat === 'HTML' ? template.content : null,
      css: template.outputFormat === 'HTML' ? template.css : null,
      validationRules: {},
    })
  } catch (reason) {
    if (createdName) throw new ApiError(`模板“${createdName}”已创建，但版本保存失败；请从模板卡片重试新版本`, 409)
    throw reason
  }
  emit('templateVersion', version.id)
  emit('saved', `模板 v${version.ordinal} 已创建`)
}

async function saveProvider() {
  let providerId = provider.id
  let created: Provider | null = null
  if (providerAction.value === 'create') {
    created = await api.createProvider({
      name: provider.name.trim(),
      providerType: provider.providerType.trim(),
      baseUrl: provider.baseUrl.trim(),
      apiKey: provider.apiKey,
      defaultProfile: provider.defaultProfile,
    })
    providerId = created.id
  }
  if (!providerId) throw new ApiError('请选择 Provider', 400)
  if (providerAction.value === 'edit') {
    await api.updateProvider(providerId, {
      name: provider.name.trim(),
      providerType: provider.providerType.trim(),
      baseUrl: provider.baseUrl.trim(),
      defaultProfile: provider.defaultProfile,
    })
    if (provider.apiKey) await api.rotateProviderCredential(providerId, provider.apiKey)
    provider.apiKey = ''
    emit('saved', 'Provider 配置已更新')
    return
  }
  try {
    await api.addProviderModel(providerId, {
      modelId: provider.modelId.trim(),
      displayName: (provider.displayName || provider.modelId).trim(),
      defaultModel: provider.defaultModel,
    })
  } catch {
    provider.apiKey = ''
    if (created) throw new ApiError(`Provider“${created.name}”已保存，但模型创建失败；请选择该 Provider 重试添加模型`, 409)
    throw new ApiError('模型创建失败，请检查模型 ID 是否重复', 409)
  }
  provider.apiKey = ''
  emit('saved', 'Provider 与模型已保存')
}

async function discoverModels() {
  if (!provider.id) return
  busy.value = true
  error.value = ''
  try {
    discoveredModels.value = await api.discoverProviderModels(provider.id)
    if (!discoveredModels.value.length) error.value = 'Provider 未返回可用模型'
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '模型发现失败'
  } finally {
    busy.value = false
  }
}

async function saveProfile() {
  const value: Omit<LoginProfile, 'id'> & { username: string; password: string } = {
    reference: profile.reference.trim(),
    name: profile.name.trim(),
    loginUrl: profile.loginUrl.trim(),
    loginPath: profile.loginPath.trim() || undefined,
    usernameLocator: locator(profile.usernameKind, profile.usernameLabel.trim(), profile.usernameRole.trim()),
    passwordLocator: locator(profile.passwordKind, profile.passwordLabel.trim(), profile.passwordRole.trim()),
    submitLocator: locator(profile.submitKind, profile.submitName.trim(), profile.submitRole.trim()),
    username: profile.username,
    password: profile.password,
  }
  await api.createLoginProfile(value)
  profile.username = ''
  profile.password = ''
  emit('saved', '登录档案已加密保存')
}

async function save() {
  busy.value = true
  error.value = ''
  try {
    if (props.mode === 'templates') await saveTemplate()
    else if (props.mode === 'providers') await saveProvider()
    else await saveProfile()
  } catch (reason) {
    error.value = reason instanceof ApiError ? reason.message : '保存失败，请检查输入后重试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="modal-layer" @click.self="emit('close')">
    <section class="modal-card config-modal" role="dialog" aria-modal="true" aria-labelledby="config-modal-title">
      <div class="modal-card__head">
        <div><span class="section-head__eyebrow">CONFIGURATION</span><h2 id="config-modal-title">{{ title }}</h2><p>{{ subtitle }}</p></div>
        <button class="icon-button" type="button" aria-label="关闭" @click="emit('close')"><X :size="18" /></button>
      </div>

      <div v-if="error" class="config-error">{{ error }}</div>

      <form v-if="mode === 'templates'" class="config-form" @submit.prevent="save">
        <div class="config-segment">
          <button type="button" :class="{ active: templateAction === 'create' }" @click="templateAction = 'create'">新建模板</button>
          <button type="button" :class="{ active: templateAction === 'version' }" :disabled="!templates.length" @click="templateAction = 'version'">创建新版本</button>
        </div>
        <template v-if="templateAction === 'create'">
          <label class="field-label">模板名称<input v-model="template.name" required placeholder="项目维护文档" /></label>
          <label class="field-label">Skill ID<input v-model="template.skillId" required placeholder="关联的 Skill UUID" /></label>
          <label class="check-field"><input v-model="template.publicTemplate" type="checkbox" />设为组织公共模板（仅管理员）</label>
        </template>
        <label v-else class="field-label">目标模板<select v-model="template.id" required><option value="" disabled>选择模板</option><option v-for="item in templates" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <div class="form-grid">
          <label class="field-label">输出格式<select v-model="template.outputFormat"><option value="MARKDOWN">Markdown</option><option value="HTML">HTML</option></select></label>
        </div>
        <label class="field-label">模板正文<textarea v-model="template.content" required :placeholder="template.outputFormat === 'HTML' ? '<main>{{title}}</main>' : '# {{title}}'" /></label>
        <label v-if="template.outputFormat === 'HTML'" class="field-label">CSS<textarea v-model="template.css" placeholder="main { max-width: 960px; }" /></label>
        <button class="button button--primary button--wide" type="submit" :disabled="busy"><LoaderCircle v-if="busy" class="spin" :size="16" /><Check v-else :size="16" />保存不可变版本</button>
      </form>

      <form v-else-if="mode === 'providers'" class="config-form" @submit.prevent="save">
        <div class="config-segment config-segment--three">
          <button type="button" :class="{ active: providerAction === 'create' }" @click="providerAction = 'create'">新建 Provider</button>
          <button type="button" :class="{ active: providerAction === 'edit' }" :disabled="!providers.length" @click="providerAction = 'edit'">编辑连接</button>
          <button type="button" :class="{ active: providerAction === 'model' }" :disabled="!providers.length" @click="providerAction = 'model'">添加模型</button>
        </div>
        <label v-if="providerAction !== 'create'" class="field-label">Provider<select v-model="provider.id" required><option value="" disabled>选择 Provider</option><option v-for="item in providers" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <template v-if="providerAction === 'create' || providerAction === 'edit'">
          <div class="form-grid">
            <label class="field-label">名称<input v-model="provider.name" required placeholder="模型服务" /></label>
            <label class="field-label">类型<input v-model="provider.providerType" required placeholder="OPENAI_COMPATIBLE" /></label>
          </div>
          <label class="field-label">Base URL<input v-model="provider.baseUrl" type="url" required placeholder="https://api.example.com/v1" /></label>
          <label class="field-label">API Key<input v-model="provider.apiKey" type="password" autocomplete="new-password" :required="providerAction === 'create'" :placeholder="providerAction === 'edit' ? '留空则保留当前凭证' : '仅本次提交可见'" /></label>
          <label class="check-field"><input v-model="provider.defaultProfile" type="checkbox" />设为默认 Provider</label>
        </template>
        <div v-if="providerAction !== 'edit'" class="form-grid">
          <label class="field-label">模型 ID<input v-model="provider.modelId" required placeholder="model-name" /></label>
          <label class="field-label">显示名称<input v-model="provider.displayName" placeholder="默认与模型 ID 相同" /></label>
        </div>
        <template v-if="providerAction === 'model'">
          <button class="button button--quiet" type="button" :disabled="busy || !provider.id" @click="discoverModels">从 Provider 发现模型</button>
          <div v-if="discoveredModels.length" class="discovered-models"><button v-for="item in discoveredModels" :key="item.modelId" type="button" @click="provider.modelId = item.modelId; provider.displayName = item.displayName">{{ item.displayName }}<small>{{ item.modelId }}</small></button></div>
        </template>
        <label v-if="providerAction !== 'edit'" class="check-field"><input v-model="provider.defaultModel" type="checkbox" />设为默认模型</label>
        <button class="button button--primary button--wide" type="submit" :disabled="busy"><LoaderCircle v-if="busy" class="spin" :size="16" /><Check v-else :size="16" />{{ providerAction === 'edit' ? '保存 Provider 修改' : providerAction === 'model' ? '保存模型' : '保存 Provider 与模型' }}</button>
      </form>

      <form v-else class="config-form" @submit.prevent="save">
        <div class="form-grid">
          <label class="field-label">引用名<input v-model="profile.reference" required placeholder="admin" /></label>
          <label class="field-label">显示名称<input v-model="profile.name" required placeholder="管理员登录态" /></label>
        </div>
        <label class="field-label">登录地址<input v-model="profile.loginUrl" type="url" required placeholder="https://app.example.com/login" /></label>
        <label class="field-label">登录后路径（可选）<input v-model="profile.loginPath" placeholder="/dashboard" /></label>
        <div class="form-grid">
          <label class="field-label">用户名<input v-model="profile.username" required autocomplete="off" /></label>
          <label class="field-label">密码<input v-model="profile.password" type="password" required autocomplete="new-password" /></label>
        </div>
        <div class="locator-grid"><label class="field-label">用户名定位方式<select v-model="profile.usernameKind"><option value="label">Label</option><option value="test-id">Test ID</option><option value="role">Role</option></select></label><label class="field-label">用户名定位名称<input v-model="profile.usernameLabel" required /></label><label v-if="profile.usernameKind === 'role'" class="field-label">ARIA Role<input v-model="profile.usernameRole" required /></label></div>
        <div class="locator-grid"><label class="field-label">密码定位方式<select v-model="profile.passwordKind"><option value="label">Label</option><option value="test-id">Test ID</option><option value="role">Role</option></select></label><label class="field-label">密码定位名称<input v-model="profile.passwordLabel" required /></label><label v-if="profile.passwordKind === 'role'" class="field-label">ARIA Role<input v-model="profile.passwordRole" required /></label></div>
        <div class="locator-grid"><label class="field-label">提交定位方式<select v-model="profile.submitKind"><option value="role">Role</option><option value="label">Label</option><option value="test-id">Test ID</option></select></label><label class="field-label">提交定位名称<input v-model="profile.submitName" required /></label><label v-if="profile.submitKind === 'role'" class="field-label">ARIA Role<input v-model="profile.submitRole" required /></label></div>
        <button class="button button--primary button--wide" type="submit" :disabled="busy"><LoaderCircle v-if="busy" class="spin" :size="16" /><Check v-else :size="16" />保存登录档案</button>
      </form>
    </section>
  </div>
</template>
