# Agent Studio

> 面向代码项目交付的自托管 AI 工作流平台。导入源码、选择模板、配置模型与登录态后，生成可审查的项目文档、用户手册、HTML 页面和自动化截图。

Agent Studio 将源码版本、模板版本、任务状态和最终产物放进同一条可追溯的工作流。它不把交付简化成一次文本回答：每个任务都绑定具体的项目 revision 与模板版本，产物可以预览、下载、校验和按成员分享。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 代码项目导入 | 支持 Git HTTPS URL 与 ZIP 上传；每次导入创建不可变 revision，记录来源、分支或 commit、SHA-256 与时间。 |
| 可视化模板 | 支持个人模板、管理员公共模板、复制、版本化、Markdown/HTML 输出与字段 Schema。 |
| 动态任务表单 | 模板定义 string、number、boolean 等参数后，创建任务时自动生成输入控件并校验必填项。 |
| 长任务工作流 | Temporal 管理任务启动、暂停、恢复、取消、审批和恢复，前端通过 SSE 获取进度。 |
| 文档与 HTML | Agent Worker 基于 Spring AI、LangGraph4j 生成受模板约束的 Markdown 和 HTML 产物。 |
| 多登录态截图 | 通过登录档案、页面路径、菜单路径和语义定位器访问目标页面，无需硬编码坐标。 |
| 产物与共享 | 文档、HTML、截图和清单以不可变版本发布，支持预览、下载、ZIP 导出和组织内分享。 |
| 凭证保护 | Provider Key 与登录凭证加密保存，只在当前 Worker 执行任务时提供必要上下文。 |

## 工作流概览

~~~mermaid
flowchart LR
    A[Git URL 或 ZIP] --> B[项目空间]
    B --> C[不可变源码 Revision]
    C --> D[任务草稿]
    D --> E[模板版本与动态字段]
    E --> F[Provider 与模型]
    F --> G[Temporal 工作流]
    G --> H[Agent Worker]
    G --> I[Browser Worker]
    H --> J[Markdown / HTML]
    I --> K[页面截图]
    J --> L[版本化产物]
    K --> L
    L --> M[预览、下载、分享]
~~~

一次完整任务的路径：

1. 创建项目空间，导入 Git 分支或 ZIP。
2. 输入想要交付的内容，例如“生成用户手册，并补充管理员用户列表截图”。
3. 选择任务类型、模板、模型与项目；模板字段会自动显示为表单。
4. 使用 Idempotency-Key 创建任务，避免重复提交产生重复执行。
5. Temporal 协调 Agent Worker 与 Browser Worker。
6. 在产物树中预览、下载、导出或分享结果。

## 快速开始

### 运行条件

- Docker Engine 或 Docker Desktop，支持 Docker Compose v2。
- Java 21。
- Node.js 22，推荐用于前端本地开发。
- Git。
- 可选：一个兼容 Chat API 的模型服务地址与 API Key。
- 可选：一个可被 Browser Worker 访问的测试应用，用于真实截图任务。

### Compose 启动完整环境

生成仅供本机使用的环境文件：

~~~sh
cp deploy/.env.example .env
./deploy/generate-local-env.sh .env
~~~

构建并启动全部服务：

~~~sh
docker compose --env-file .env up --build
~~~

启动后可访问：

| 地址 | 用途 |
| --- | --- |
| http://localhost:8088 | Agent Studio 工作台 |
| http://localhost:8080 | Platform API |
| http://localhost:9001 | MinIO 控制台 |
| http://localhost:8025 | Mailpit 本地邮件查看 |
| localhost:7233 | Temporal gRPC |

第一次打开工作台时，进入“首次初始化”，填写 .env 中的 SETUP_TOKEN、组织名称、管理员邮箱和至少 12 位密码。完成后使用普通账号登录。

停止容器：

~~~sh
docker compose --env-file .env down
~~~

只停止容器并保留数据库、对象存储卷：

~~~sh
docker compose --env-file .env stop
~~~

### 前端热更新与本地 JVM 调试

开发 Java 服务时，可以让 Compose 只运行 PostgreSQL、MinIO、Mailpit 和 Temporal，把四个 Java 服务直接运行在宿主机：

~~~sh
cp deploy/.env.example .env
./deploy/generate-local-env.sh .env
./deploy/run-local.sh .env start

npm ci --no-audit --no-fund
npm run dev -- --host 127.0.0.1 --port 4177
~~~

本地开发前端地址为 http://localhost:4177。查看状态或停止本地 JVM：

~~~sh
./deploy/run-local.sh .env status
./deploy/run-local.sh .env stop
~~~

## 架构

~~~mermaid
flowchart TB
    U[浏览器] --> N[Nginx]
    N --> F[Vue 3 Frontend]
    N --> P[Platform API]

    P --> DB[(PostgreSQL)]
    P --> O[(MinIO / S3)]
    P --> W[Workflow Service]

    W --> T[(Temporal)]
    W --> A[Agent Worker]
    A --> P
    A --> B[Browser Worker]
    B --> P

    A --> L[兼容 Chat API 的模型服务]
    B --> R[目标 Web 应用]
~~~

### 服务边界

| 模块 | 默认端口 | 责任 |
| --- | ---: | --- |
| frontend | 8088 | Vue 3 工作台，处理登录、项目、模板、任务、产物和分享操作。 |
| platform-api | 8080 | 认证、组织成员、项目 revision、模板、Provider、登录档案、任务、审批、产物和分享。它是唯一持有数据库与对象存储凭证的应用服务。 |
| workflow-service | 8081，内部 | 对接 Temporal，负责工作流启动、查询、暂停、恢复、取消和审批信号。 |
| agent-worker | 8082，内部 | 任务草稿、模型调用、LangGraph4j 子流程、Markdown/HTML 渲染与截图标记处理。 |
| browser-worker | 8083，内部 | Playwright Java 隔离会话、登录、导航、语义操作和截图发布。 |
| contracts | — | 跨服务共享的任务、状态、事件和工作流请求模型。 |
| PostgreSQL | 5432，内部 | 业务元数据、模板版本、任务事件、审批和对象索引。 |
| MinIO / S3 | 9000，内部 | 源码压缩包、文档、HTML、截图和其他产物对象。 |
| Temporal | 7233 | 长任务状态、信号和恢复能力。 |

浏览器通过前端和 Platform API 使用系统。Workflow Service、Agent Worker 与 Browser Worker 位于内部网络，Nginx 不会代理 internal 路由。

## 项目与任务

### 项目 revision

项目支持两种输入：

- Git HTTPS URL：先检查地址与可用分支，再导入指定分支。
- ZIP：上传源码压缩包，创建新的 revision。

每一个 revision 都会记录来源类型、来源地址、分支或 commit、SHA-256、创建时间和对象存储位置。任务永远引用一个明确 revision，后续重新导入不会影响已经创建的任务。

默认导入限制：

| 限制 | 默认值 |
| --- | ---: |
| ZIP 原始文件 | 100 MiB |
| multipart 请求 | 110 MiB |
| Git 归档 | 100 MiB |
| Git 文件数 | 20,000 |
| Git 连接超时 | 90 秒 |

ZIP 导入会检查真实文件头、目录穿越、条目数量与展开后大小。共享项目为只读，成员不能上传新的源码 revision。

### 四类工作流

| 类型 | 默认输出方向 | 用途 |
| --- | --- | --- |
| PROJECT_DOCS | docs/README.md | 面向维护者的模块说明、启动方式和关键入口。 |
| USER_GUIDE | docs/user-guide.md | 面向使用者的操作步骤、页面流程和功能说明。 |
| HTML | site/index.html | 可预览、可交付的项目介绍页或 HTML 文档。 |
| SCREENSHOT | manifests/screenshots.json | 根据声明式标记访问页面并生成截图集合。 |

创建任务时需要选择：

- 已导入 revision 的项目。
- 与任务类型匹配的模板版本。
- 当前用户可用的 Provider 和模型，或用户自己的默认配置。
- 唯一的 Idempotency-Key。

任务状态包括 QUEUED、RUNNING、PAUSED、WAITING_FOR_APPROVAL、SUCCEEDED、FAILED 和 CANCELED。事件通过带序号的 SSE 返回，包含状态、进度、失败码、消息和产物引用。

## 模板系统

模板用于描述交付结构，而不是绑定某一套固定手册或 HTML 格式。每个模板关联一个 Skill，Skill 决定模板可以服务的任务类型。

### 权限与版本

- 个人模板仅所有者可读写。
- 管理员可以发布公共模板。
- 组织成员可以将公共模板复制为个人模板。
- 修改模板会创建一个新版本。
- 任务创建后固定引用当时选定的模板版本，历史任务不会被后续修改影响。

一个模板版本可以包含：

- 输出格式：MARKDOWN 或 HTML。
- 参数 Schema。
- 表单布局。
- 允许的章节。
- Markdown 或 HTML 正文。
- HTML 专用 CSS。
- 校验规则。

### 可视化编辑

可视化模板编辑器支持：

- 标题、固定说明、生成正文、源码变更摘要和截图区块。
- Markdown 与 HTML 输出切换。
- 字段名称、类型、默认值和必填状态。
- 截图区块的登录档案、路径、菜单、操作与图注。
- 产物结构预览。
- 高级 JSON 配置，用于精细调整 Schema 和布局。

模板中的 HTML 不允许脚本。前端编辑器会阻止脚本和常见危险写法，服务端拒绝 script 标签，预览接口通过 CSP 与受限媒体类型隔离内容。

### 参数 Schema 示例

~~~json
{
  "type": "object",
  "properties": {
    "audience": {
      "type": "string",
      "title": "文档读者",
      "default": "维护者"
    },
    "includeApi": {
      "type": "boolean",
      "title": "包含 API 入口",
      "default": true
    },
    "maxSections": {
      "type": "number",
      "title": "最多章节数",
      "default": 8
    }
  },
  "required": ["audience"],
  "additionalProperties": true
}
~~~

选择模板后，界面会自动显示“文档读者”“包含 API 入口”“最多章节数”等字段。截图任务还会额外校验合法的 baseUrl。

### 文档占位符

Markdown 模板支持以下运行时占位符：

| 占位符 | 替换内容 |
| --- | --- |
| {{title}} | 任务标题。 |
| {{content}} | Agent Worker 生成的正文。 |
| {{sourceChangeSummary}} | 当前源码 revision 的变更摘要。 |
| {{screenshots}} | 已发布截图的 Markdown 引用集合。 |

文档路径必须位于 docs/ 下。渲染器会拒绝绝对路径、反斜杠和目录回退路径。

## 声明式多登录态截图

截图不是“打开固定 URL 后保存一张图片”。模板中的截图标记描述了页面目标、登录身份和可执行的操作。

~~~markdown
<!-- agent-studio:screenshot:v1 {"id":"users-admin","loginProfileRef":"admin-profile","target":"用户列表","menuPath":["管理后台","用户"],"routePath":"/admin/users","actions":[{"type":"click","locator":{"kind":"role","role":"button","name":"筛选"}},{"type":"wait","locator":{"kind":"role","role":"heading","name":"用户列表"}}],"caption":"管理员用户列表"} -->
~~~

| 字段 | 含义 |
| --- | --- |
| id | 文档内唯一的截图标识。 |
| loginProfileRef | 要使用的登录档案引用。 |
| target | 可读的页面目标名称。 |
| routePath | 应用相对路径，例如 /admin/users。 |
| menuPath | 需要依次进入的菜单文字。 |
| actions | 页面操作序列。 |
| caption | 最终文档中的截图说明。 |

支持的动作包括 click、fill、select、check、uncheck 和 wait。定位器使用 role + name、label 或 test-id，不依赖页面坐标和脆弱的 CSS 选择器。

Browser Worker 的执行过程：

1. 为当前任务创建独立的 Playwright BrowserContext。
2. 获取当前任务所需的登录档案。
3. 完成登录并等待异步重定向稳定。
4. 检查允许访问的 host/origin，再按 routePath、menuPath 和 actions 操作。
5. 验证目标页面，截取 PNG 并计算摘要。
6. 通过 Platform API 发布不可变截图版本。
7. 将产物引用写入文档或任务产物树。

不同登录档案不会共享 Cookie、Local Storage 或浏览器缓存；任务结束后，隔离上下文会关闭。

## Provider、登录档案与安全边界

### Provider

每位用户可以维护自己的模型 Provider：

- Provider 名称、类型和兼容 Chat API 的 Base URL。
- 加密保存的 API Key。
- 可选模型列表和默认模型。
- 连通性检查与模型发现。

API Key 只在 Agent Worker 为当前任务发起模型调用时解密到内存中，不通过前端列表接口、任务事件或普通日志返回。

### 登录档案

登录档案包含：

- reference 和显示名称。
- 登录地址与登录后路径。
- 用户名、密码和提交按钮的结构化 locator。
- 加密保存的用户名和密码。

一个组织可以维护多个登录档案，例如 admin-profile、member-profile 和 readonly-profile。每个截图标记选择一个档案，Browser Worker 会为任务建立隔离上下文。

### 权限与网络边界

- 项目、任务日志和产物默认仅所有者可读。
- 指定组织成员可被显式授予项目或产物的只读访问。
- 共享项目不能导入新源码版本，也不能替所有者创建任务。
- Provider Key 与登录凭证不会出现在共享数据中。
- Worker 使用独立 Token 调用内部接口，普通浏览器请求不能访问。
- Git 只接受 HTTPS 与允许主机；连接和重定向会再次验证目标。
- Browser Worker 仅可访问 BROWSER_ALLOWED_HOSTS 和 BROWSER_ALLOWED_ORIGINS。
- 生产环境仍应部署 TLS、出站 egress 防火墙、Secret Manager、KMS 或 Vault。

## API 一览

面向前端的 API 都以 /api/v1 开头，使用 Bearer 会话令牌。完整接口契约见 contracts/openapi/agent-studio-api.yaml。

| 资源 | 主要接口 | 用途 |
| --- | --- | --- |
| 认证 | auth/login、auth/register、setup/first-admin | 登录、注册、邮箱验证与首位管理员初始化。 |
| 项目 | projects、projects/{id}/revisions | 创建项目和查询源码 revision。 |
| 导入 | projects/{id}/git/test、imports/git、imports/zip | 检查 Git、导入分支或 ZIP。 |
| 模板 | templates、templates/{id}/versions、templates/{id}/copy | 查询、创建、版本化和复制模板。 |
| Provider | providers、providers/{id}/test、models/discover | 管理模型连接、验证连通性和发现模型。 |
| 登录档案 | login-profiles | 管理多登录态截图所需的账户配置。 |
| 任务 | tasks、tasks/{id}/events、tasks/{id}/pause | 创建任务、读取事件、暂停、恢复与取消。 |
| 审批 | tasks/{id}/approvals | 创建和处理人工确认。 |
| 产物 | tasks/{id}/artifacts、artifacts/{id}/preview | 查看、预览、下载、校验和导出产物。 |
| 分享 | shares、members | 将项目或产物按成员显式分享。 |

服务间 internal API 使用独立 Worker Token，不应通过公网暴露。

## 配置

deploy/.env.example 列出了完整变量。本地运行推荐使用 deploy/generate-local-env.sh 自动生成随机环境值。

| 变量 | 作用 |
| --- | --- |
| POSTGRES_DB、POSTGRES_USER、POSTGRES_PASSWORD | Platform API 的 PostgreSQL 连接。 |
| SETUP_TOKEN | 首位管理员初始化令牌。 |
| ENCRYPTION_KEY | Provider Key 与登录凭证的加密密钥。 |
| AGENT_WORKER_TOKEN、BROWSER_WORKER_TOKEN、WORKFLOW_SERVICE_TOKEN | 服务间内部鉴权。 |
| S3_BUCKET、S3_ACCESS_KEY、S3_SECRET_KEY | MinIO/S3 对象存储配置。 |
| GIT_ALLOWED_HOSTS | 允许导入的 Git 主机，默认包含 GitHub、GitLab 和 Bitbucket。 |
| GIT_MAX_ARCHIVE_BYTES、GIT_MAX_FILES、GIT_TIMEOUT_SECONDS | Git 导入限制。 |
| BROWSER_ALLOWED_HOSTS、BROWSER_ALLOWED_ORIGINS | Browser Worker 可访问的目标应用范围。 |
| PUBLIC_BASE_URL | 邮件链接和对外工作台地址。 |
| MAIL_HOST、MAIL_PORT | 邮件服务；本地默认使用 Mailpit。 |

不要提交 .env、Provider API Key、登录档案、Worker Token 或对象存储密钥。

## 仓库结构

~~~text
.
├── contracts/                 # 跨服务模型与 OpenAPI 契约
├── platform-api/              # 认证、项目、模板、Provider、任务、产物、分享
├── workflow-service/          # Temporal 工作流与控制桥接
├── agent-worker/              # Spring AI、LangGraph4j、文档与 HTML 生成
├── browser-worker/            # Playwright 会话、登录和截图执行
├── src/                       # Vue 3 前端与可视化模板编辑器
├── deploy/                    # 环境生成、本地 JVM 启动、Temporal 和备份脚本
├── docs/                      # UI 设计与运行说明
├── docker-compose.yml         # 完整本地运行栈
├── nginx.conf                 # 前端静态资源与 API 反向代理
├── pom.xml                    # Java 21 Maven reactor
└── package.json               # 前端脚本
~~~

## 验证与测试

后端全量测试：

~~~sh
./mvnw test
~~~

前端模板、上传限制和错误提示测试：

~~~sh
npm run test:template-form
~~~

前端类型检查与生产构建：

~~~sh
npm run build
~~~

提交前建议执行：

~~~sh
git diff --check
~~~

测试覆盖契约、任务规则、导入安全、模板版本、产物访问、Temporal 状态、浏览器会话、导航策略、截图失败码和前端参数处理。发布前应使用真实模型服务和真实测试应用执行一次端到端验证。

## 常见问题

### ZIP 上传失败

1. 确认选择的是实际 ZIP 文件，而不是仅修改扩展名的普通文件。
2. 确认文件不为空且不超过 100 MiB。
3. 确认当前项目不是别人分享给你的只读项目。
4. 如果经过 Nginx，确认 /api/ 的 client_max_body_size 不低于 110m。
5. 检查 ZIP 是否存在目录穿越、异常条目或过大的解压内容。
6. 查看前端显示的具体服务端错误；上传失败不会再统一折叠成模糊提示。

### 页面能登录但无法截图

- 检查 loginUrl、loginPath、routePath 和菜单文字是否与目标应用一致。
- 为异步跳转增加 wait 动作，优先等待稳定的 heading 或 test-id。
- 检查 BROWSER_ALLOWED_HOSTS 与 BROWSER_ALLOWED_ORIGINS 是否包含目标应用。
- 选择正确的 loginProfileRef，且每个截图标记使用唯一 id。
- 页面结构更新后，应新建模板版本修正语义，不修改历史任务。

### 前端显示已登录但数据为空

- 确认前端端口与 API 端口属于同一套 Compose 或本地运行环境。
- 清理过期的 agent-studio.session 后重新登录。
- 检查 Platform API 健康状态：curl -i http://localhost:8080/actuator/health。
- 对新环境先完成首次初始化，再创建项目、模板和 Provider。

## 许可证

本项目使用 MIT License，详见 LICENSE。
