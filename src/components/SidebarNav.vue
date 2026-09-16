<script setup lang="ts">
import {
  Activity,
  Blocks,
  Box,
  ChevronRight,
  FolderKanban,
  KeyRound,
  LayoutDashboard,
  PanelLeftClose,
  PanelLeftOpen,
  Settings2,
  ShieldCheck,
  Sparkles,
} from 'lucide-vue-next'

defineProps<{ active: string; collapsed: boolean }>()
const emit = defineEmits<{ select: [value: string]; toggle: [] }>()

const primary = [
  { id: 'overview', label: '总览', icon: LayoutDashboard },
  { id: 'projects', label: '项目空间', icon: FolderKanban },
  { id: 'tasks', label: '任务队列', icon: Activity },
]
const library = [
  { id: 'templates', label: '模板库', icon: Blocks },
  { id: 'providers', label: '模型与 Provider', icon: Sparkles },
  { id: 'profiles', label: '登录档案', icon: KeyRound },
]
</script>

<template>
  <aside class="sidebar" :class="{ 'sidebar--collapsed': collapsed }">
    <div class="sidebar__brand">
      <div class="brand-mark"><Sparkles :size="17" :stroke-width="2.4" /></div>
      <div v-if="!collapsed" class="brand-copy">
        <strong>Agent Studio</strong>
        <span>workflow workspace</span>
      </div>
      <button class="icon-button sidebar__toggle" type="button" :aria-label="collapsed ? '展开侧栏' : '收起侧栏'" @click="emit('toggle')">
        <PanelLeftOpen v-if="collapsed" :size="17" />
        <PanelLeftClose v-else :size="17" />
      </button>
    </div>

    <div v-if="!collapsed" class="sidebar__eyebrow">WORKSPACE</div>
    <nav class="sidebar__nav" aria-label="主导航">
      <button v-for="item in primary" :key="item.id" class="nav-item" :class="{ 'nav-item--active': active === item.id }" type="button" @click="emit('select', item.id)">
        <component :is="item.icon" :size="18" :stroke-width="active === item.id ? 2.4 : 1.9" />
        <span v-if="!collapsed">{{ item.label }}</span>
        <ChevronRight v-if="!collapsed && active === item.id" class="nav-item__chevron" :size="14" />
      </button>
    </nav>

    <div v-if="!collapsed" class="sidebar__eyebrow sidebar__eyebrow--library">LIBRARY</div>
    <nav class="sidebar__nav" aria-label="资源管理">
      <button v-for="item in library" :key="item.id" class="nav-item" :class="{ 'nav-item--active': active === item.id }" type="button" @click="emit('select', item.id)">
        <component :is="item.icon" :size="18" :stroke-width="active === item.id ? 2.4 : 1.9" />
        <span v-if="!collapsed">{{ item.label }}</span>
      </button>
    </nav>

    <div class="sidebar__spacer" />
    <nav class="sidebar__nav" aria-label="系统导航">
      <button class="nav-item" :class="{ 'nav-item--active': active === 'security' }" type="button" @click="emit('select', 'security')">
        <ShieldCheck :size="18" /><span v-if="!collapsed">权限与审计</span>
      </button>
      <button class="nav-item" :class="{ 'nav-item--active': active === 'settings' }" type="button" @click="emit('select', 'settings')">
        <Settings2 :size="18" /><span v-if="!collapsed">工作区设置</span>
      </button>
    </nav>

    <div v-if="!collapsed" class="sidebar__footer">
      <div class="workspace-switcher">
        <div class="workspace-switcher__icon"><Box :size="16" /></div>
        <div><strong>个人工作区</strong><span>单组织 · Member</span></div>
        <ChevronRight :size="15" />
      </div>
      <div class="profile-chip"><div class="avatar">U</div><div><strong>当前账号</strong><span>在线</span></div><span class="status-dot" /></div>
    </div>
  </aside>
</template>
