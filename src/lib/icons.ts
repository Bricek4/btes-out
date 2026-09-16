import { defineComponent, h, type PropType } from 'vue'

type Part = { tag: 'path' | 'rect' | 'circle'; attrs: Record<string, string | number> }
const path = (d: string): Part => ({ tag: 'path', attrs: { d } })
const circle = (cx: number, cy: number, r: number): Part => ({ tag: 'circle', attrs: { cx, cy, r } })
const rect = (x: number, y: number, width: number, height: number, rx = 2): Part => ({ tag: 'rect', attrs: { x, y, width, height, rx } })

function icon(name: string, parts: Part[]) {
  return defineComponent({
    name: `Studio${name}`,
    inheritAttrs: false,
    props: {
      size: { type: [Number, String] as PropType<number | string>, default: 24 },
      strokeWidth: { type: Number, default: 1.8 },
    },
    setup(props, { attrs }) {
      return () => h('svg', {
        viewBox: '0 0 24 24', width: props.size, height: props.size,
        fill: 'none', stroke: 'currentColor', 'stroke-width': props.strokeWidth,
        'stroke-linecap': 'round', 'stroke-linejoin': 'round',
        'aria-hidden': 'true', focusable: 'false', ...attrs,
      }, parts.map((part) => h(part.tag, part.attrs)))
    },
  })
}

// Status glyphs keep a recognizable silhouette at 12–14 px.
export const Queue = icon('Queue', [path('M8 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h3M16 4h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-3'), path('M8 8h7M9 12h7M10 16h7')])
export const LoaderCircle = icon('Flowing', [path('M5 10V6a1 1 0 0 1 1-1h7M19 14v4a1 1 0 0 1-1 1h-7M16 4l3 1-1 3M8 20l-3-1 1-3'), circle(12, 12, 2)])
export const CircleCheck = icon('Delivered', [path('M20 12v6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h8'), path('M8 11l4 4 8-9')])
export const CircleX = icon('Broken', [path('M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3M15 4h3a2 2 0 0 1 2 2v3'), path('M8 8l8 8M16 8l-8 8')])
export const Pause = icon('Paused', [path('M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3'), path('M9 8v8M15 8v8')])
export const CircleAlert = icon('Decision', [path('M12 3l8 8v2l-8 8-8-8v-2l8-8Z'), path('M12 7v6M12 16h.01')])
export const Ban = icon('Canceled', [path('M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3M15 4h3a2 2 0 0 1 2 2v3M6 18 18 6')])
export const TriangleAlert = icon('Warning', [path('M10.5 4.5a1.8 1.8 0 0 1 3 0l7 12a1.8 1.8 0 0 1-1.5 2.7H5a1.8 1.8 0 0 1-1.5-2.7l7-12Z'), path('M12 9v4M12 16h.01')])

// Workspace and content glyphs share open corners and offset layers.
export const Sparkles = icon('Agent', [path('M12 3v3M12 18v3M3 12h3M18 12h3M8 8l4-2 4 2 2 4-2 4-4 2-4-2-2-4 2-4Z'), circle(12, 12, 1.8)])
export const LayoutDashboard = icon('Workspace', [path('M4 9V5a1 1 0 0 1 1-1h6v5H4ZM14 4h5a1 1 0 0 1 1 1v9h-6V4ZM4 12h7v8H5a1 1 0 0 1-1-1v-7ZM14 17h6v2a1 1 0 0 1-1 1h-5v-3Z')])
export const FolderKanban = icon('Projects', [path('M4 8V6a2 2 0 0 1 2-2h4l2 3h6a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-7'), path('M8 11v5M12 11v3M16 11v6')])
export const Activity = icon('Workflow', [path('M4 6h5l3 6 3-6h5M4 18h5l3-6 3 6h5'), circle(4, 6, 1.2), circle(20, 18, 1.2)])
export const Blocks = icon('Templates', [rect(4, 4, 6, 6, 1.5), rect(14, 4, 6, 6, 1.5), rect(4, 14, 6, 6, 1.5), path('M14 14h6v6h-6v-6ZM14 17h6')])
export const KeyRound = icon('LoginProfile', [path('M14 4h4l3 3v4l-3 3h-4l-3-3V7l3-3ZM11 12l-7 7v2h4v-3h3v-3'), circle(16, 9, 1.3)])
export const ShieldCheck = icon('Guard', [path('M12 3 20 6v6c0 4-4 7-8 9-4-2-8-5-8-9V6l8-3Z'), path('M8 11l3 3 5-5')])
export const Settings2 = icon('Controls', [path('M4 7h16M4 17h16'), rect(7, 4, 4, 6, 1.3), rect(14, 14, 4, 6, 1.3)])
export const Box = icon('Organization', [path('M12 3 20 7v10l-8 4-8-4V7l8-4ZM4 7l8 4 8-4M12 11v10'), path('M8 5l8 4')])
export const PackageOpen = icon('ProjectPackage', [path('M4 8l8-4 8 4-8 4-8-4ZM4 8l-1 5 5 3 4-4M20 8l1 5-5 3-4-4M5 15v3l7 3 7-3v-3M12 12v9')])
export const FileText = icon('Document', [path('M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9l-6-6ZM14 3v6h6'), path('M8 13h8M8 17h5')])
export const BookOpenCheck = icon('Guide', [path('M12 6c-3-2-6-2-9-1v14c3-1 6-1 9 1 3-2 6-2 9-1V5c-3-1-6-1-9 1ZM12 6v14'), path('M5 9h4M5 12h4M15 10l2 2 3-3')])
export const Code2 = icon('Code', [path('M8 6 3 12l5 6M16 6l5 6-5 6M14 4l-4 16')])
export const FileCode2 = icon('SourceFile', [path('M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9l-6-6ZM14 3v6h6'), path('M10 12l-3 3 3 3M14 12l3 3-3 3')])
export const ScanLine = icon('Capture', [path('M8 4H5a1 1 0 0 0-1 1v3M16 4h3a1 1 0 0 1 1 1v3M4 16v3a1 1 0 0 0 1 1h3M20 16v3a1 1 0 0 1-1 1h-3M3 12h18'), rect(8, 8, 8, 8, 1)])
export const Image = icon('ImageArtifact', [rect(4, 4, 16, 16, 2), path('M5 17l5-5 4 3 3-3 3 3'), circle(9, 8, 1.3)])
export const UserRound = icon('Identity', [path('M8 5h8v4a4 4 0 0 1-8 0V5ZM4 20v-2a5 5 0 0 1 5-5h6a5 5 0 0 1 5 5v2'), path('M9 3h6')])
export const LockKeyhole = icon('Secret', [path('M7 9V7a5 5 0 0 1 10 0v2'), rect(4, 9, 16, 12, 2), circle(12, 14, 1.4), path('M12 15.5v2')])

// Action glyphs use the same short-cut arrow and node language.
export const ArrowRight = icon('Forward', [path('M4 12h15M14 7l5 5-5 5')])
export const ArrowUpRight = icon('Open', [path('M5 19 19 5M10 5h9v9')])
export const ArrowDownRight = icon('Downward', [path('M5 5l14 14M10 19h9v-9')])
export const ChevronDown = icon('Expand', [path('M6 9l6 6 6-6')])
export const ChevronRight = icon('Next', [path('M9 6l6 6-6 6')])
export const Plus = icon('Add', [path('M5 12h14M12 5v14')])
export const Check = icon('Confirm', [path('M4 11l6 6L20 6')])
export const X = icon('Close', [path('M6 6l12 12M18 6 6 18')])
export const Menu = icon('Menu', [path('M4 6h16M4 12h12M4 18h16')])
export const Search = icon('Find', [path('M5 5h8l3 3v5l-3 3H8l-3-3V5ZM16 16l5 5')])
export const RefreshCw = icon('Refresh', [path('M19 9a7 7 0 0 0-12-4L4 8M4 4v4h4M5 15a7 7 0 0 0 12 4l3-3M20 20v-4h-4')])
export const Upload = icon('Import', [path('M4 15v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4M12 16V4M7 9l5-5 5 5')])
export const Download = icon('Download', [path('M4 15v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4M12 4v12M7 11l5 5 5-5')])
export const FolderPlus = icon('CreateProject', [path('M4 8V6a2 2 0 0 1 2-2h4l2 3h6a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-7M9 13h6M12 10v6')])
export const Github = icon('Repository', [path('M6 4H4v16h2M18 4h2v16h-2M9 8h6M9 16h6M12 8v8'), circle(9, 8, 1.2), circle(15, 16, 1.2)])
export const GitBranch = icon('Revision', [path('M6 5v14M6 9h7a5 5 0 0 0 5-5'), circle(6, 4, 2), circle(6, 20, 2), circle(18, 3, 2)])
export const Share2 = icon('Share', [path('M8 11l8-5M8 13l8 5'), rect(3, 9, 5, 6, 1.5), rect(16, 3, 5, 5, 1.5), rect(16, 16, 5, 5, 1.5)])
export const Eye = icon('Preview', [path('M3 12l5-6h8l5 6-5 6H8l-5-6Z'), circle(12, 12, 3)])
export const Archive = icon('ExportBundle', [rect(4, 8, 16, 13, 2), path('M3 4h18v4H3V4ZM9 12h6M12 12v5')])
export const Send = icon('Submit', [path('M3 4l18 8-18 8 4-8-4-8ZM7 12h14')])
export const LogOut = icon('Disconnect', [path('M10 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h5M10 12h11M16 7l5 5-5 5')])
export const PanelLeftClose = icon('CollapseNav', [rect(3, 4, 18, 16, 2), path('M9 4v16M17 8l-4 4 4 4')])
export const PanelLeftOpen = icon('ExpandNav', [rect(3, 4, 18, 16, 2), path('M9 4v16M13 8l4 4-4 4')])
export const CircleHelp = icon('Help', [path('M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2h-3'), path('M9 9a3 3 0 0 1 6 0c0 2-3 2-3 4M12 16h.01')])
export const Clock3 = icon('Updated', [path('M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2h-3M12 7v5l4 2')])
