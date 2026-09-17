# Agent Studio

Agent Studio 是一个可自托管的 AI 工作流工作台：把代码项目导入后，选择一个可编辑的输出模板，描述希望交付的结果，系统会生成项目文档、用户手册、HTML 页面或自动化截图，并把每次输入、任务状态和产物版本绑定起来。

它解决的是一条完整的交付链路，而不是单次文本生成：

- 代码来源有明确的不可变 revision，可追溯到 Git commit 或 ZIP 摘要。
- 输出由模板版本约束，字段可以由模板声明，前端会自动生成参数表单。
- 长任务由 Temporal 管理，支持暂停、恢复、取消、重试和人工确认。
- AI 子流程由 Agent Worker 执行，浏览器操作由 Browser Worker 执行。
- 截图通过登录档案、页面路径、菜单路径和语义定位器确定目标页面，不依赖脆弱的坐标或硬编码 CSS。
- 文档、HTML、截图和清单都作为可验证、可预览、可下载、可分享的版本化产物保存。
- 源码、任务日志和产物默认私有，Provider Key 与登录凭证不会写入 Temporal 历史或普通运行日志。

项目适合用作：

1. 需要把代码仓库快速整理为交付文档的团队内部工具。
2. 需要演示“源码 → AI 工作流 → 文档与截图产物”的工程项目。
3. 展示 Java 微服务、工作流编排、浏览器自动化、对象存储和前端产品设计能力的毕业设计或求职项目。

## 产品流程

~~~mermaid
flowchart LR
    A[Git URL 或 ZIP] --> B[项目空间]
    B --> C[不可变源码 Revision]
    C --> D[自然语言目标]
    D --> E[可编辑任务草稿]
    E --> F[模板版本与动态字段]
    F --> G[Provider 与模型]
    G --> H[Temporal 工作流]
    H --> I[Agent Worker]
    H --> J[Browser Worker]
    I --> K[Markdown / HTML]
    J --> L[PNG 截图]
    K --> M[产物版本与校验]
    L --> M
    M --> N[预览、下载、分享]
~~~

一次完整演示可以这样进行：

1. 创建一个项目空间。
2. 上传 ZIP，或者输入允许的 Git HTTPS 地址并选择分支。
3. 在“新建工作流”抽屉中输入例如“生成面向维护者的项目文档，并为管理员用户列表添加截图”。
4. 检查系统识别的任务类型，选择项目、输出模板、Provider 和模型。
5. 模板的 JSON Schema 会自动变成简单表单；截图任务会要求填写应用地址。
6. 点击“确认并创建任务”，后端使用幂等键创建排队任务。
7. Temporal 驱动 Agent Worker 和 Browser Worker，前端通过任务事件查看进度。
8. 任务完成后，在产物树中预览、下载或导出 ZIP；需要时向组织成员显式分享。

## 架构

### 服务边界

| 模块 | 默认端口 | 责任 |
| --- | ---: | --- |
| frontend | 8088（宿主机） | Vue 3 工作台、登录、项目/模板/任务操作、产物预览 |
| platform-api | 8080 | 认证、组织成员、项目与 revision、模板、Provider、登录档案、任务、审批、产物和分享；唯一接触 PostgreSQL 与 MinIO/S3 凭证的服务 |
| workflow-service | 8081（Compose 内部） | Temporal 客户端与工作流桥接，管理任务生命周期、信号、查询、重试和恢复 |
| agent-worker | 8082（Compose 内部） | 自然语言草稿解析、Spring AI 模型调用、LangGraph4j 子流程、Markdown/HTML 文档生成 |
| browser-worker | 8083（Compose 内部） | Playwright Java 隔离浏览器上下文、登录态、导航、菜单和语义操作、截图发布 |
| contracts | — | 跨服务共享的任务、状态、事件和工作流请求模型 |
| PostgreSQL | 5432（仅 Compose 网络） | 业务元数据、模板版本、任务事件、审批和对象索引 |
| MinIO / S3 | 9000（内部）、9001（控制台） | 源码压缩包、截图、文档和 HTML 等二进制对象 |
| Temporal | 7233（宿主机） | 持久化工作流状态和信号 |
| Mailpit | 8025（控制台） | 本地注册、验证和密码重置邮件预览 |

~~~mermaid
flowchart TB
    U[浏览器] --> N[Nginx]
    N --> F[Vue frontend]
    N --> P[Platform API :8080]

    P --> DB[(PostgreSQL)]
    P --> OBJ[(MinIO / S3)]
    P --> W[Workflow Service :8081]

    W --> T[(Temporal)]
    W --> A[Agent Worker :8082]
    A --> P
    A --> B[Browser Worker :8083]
    B --> P
    A --> LLM[OpenAI 兼容 Provider]
    B --> APP[被测应用]
~~~

宿主机只需要通过前端访问业务 API。Workflow Service、Agent Worker 和 Browser Worker 走 Compose 内部网络；Browser Worker 的 internal 接口不会由 Nginx 对外暴露。

### 为什么分成这些服务

- platform-api 负责事实和权限。项目 revision、模板版本、Provider、任务和产物的所有权在这里落库。
- workflow-service 负责时间维度。长任务不依赖 HTTP 请求是否一直保持连接，暂停、恢复、取消和人工审批通过 Temporal signal 完成。
- agent-worker 负责推理与内容处理。模型输出先被限制成可校验的结构，再进入文档或 HTML 渲染流程。
- browser-worker 负责副作用。浏览器上下文、登录凭证和截图动作集中在一个隔离边界内，避免把浏览器状态带到 API 或 Temporal。
- 前端只保存短期会话和表单状态，模板参数由服务端版本决定，避免页面和模板字段逐渐漂移。

## 主要功能

### 项目与源码版本

- 新建项目空间。
- 从 Git HTTPS URL 检查连接、列出分支并导入指定分支。
- 上传 ZIP 并生成源码 revision。
- 每个 revision 记录来源类型、分支或 commit、SHA-256、创建时间和对象地址。
- Git 允许主机、连接超时、最大文件数和最大归档大小均可配置。
- ZIP 会检查文件头、目录穿越、文件数量和解压后大小。
- 共享项目默认只读，只有所有者能继续导入新的源码 revision。

当前默认限制：

- 单个 ZIP 原始大小不超过 100 MiB。
- multipart 请求上限为 110 MiB，为表单边界保留空间。
- Git 归档默认上限为 100 MiB。
- Git 文件数默认上限为 20,000。
- Git 连接默认超时为 90 秒。

### 四类工作流

| 任务类型 | 默认输出方向 | 典型用途 |
| --- | --- | --- |
| PROJECT_DOCS | docs/README.md | 面向维护者的架构、模块、入口和源码说明 |
| USER_GUIDE | docs/user-guide.md | 面向使用者的操作步骤和功能手册 |
| HTML | site/index.html | 生成可预览、可交付的项目介绍页或产品页面 |
| SCREENSHOT | manifests/screenshots.json | 根据声明式标记访问页面并生成截图集合 |

自然语言只负责生成可编辑草稿。真正创建任务前，用户仍然可以修改项目、模板、参数、Provider 和模型。

### 模板库

模板分为两类：

- 个人模板：只有所有者可以查看和编辑。
- 公共模板：管理员发布，组织成员可以复制成自己的模板。

模板本身只引用一个 Skill；Skill 决定任务类型。模板的每次编辑都会创建新的 immutable version，任务只引用具体版本，不会因为后续编辑而改变历史任务。

一个版本包含：

- outputFormat：MARKDOWN 或 HTML。
- parameterSchema：JSON Schema 风格的参数定义。
- formLayout：表单布局和可视化编辑器状态。
- allowedSections：允许输出的章节。
- validationRules：服务端和前端可共同使用的约束。
- markdownTemplate 或 htmlTemplate。
- HTML 专用 css。

前端的可视化模板编辑器支持：

- 添加、删除和调整内容区块。
- 标题、固定说明、生成正文、源码变更摘要、全部截图等区块。
- Markdown 与 HTML 之间切换。
- 为任务参数设置名称、类型、默认值和必填状态。
- 为截图区块设置登录档案、页面路径、菜单路径、语义操作和说明文字。
- 预览最终文档结构。
- 使用高级 JSON 修改 Schema 和布局。

模板不执行用户提供的 JavaScript，也不执行 Shell。HTML 预览会使用受限的 CSP，服务端会拒绝包含脚本的模板。

#### 参数 Schema 示例

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

用户选择模板后，前端按照字段类型生成文本框、数字框和开关，并把草稿中的已有参数合并进去。服务端创建任务时仍会再次校验任务类型、模板版本、Provider/模型归属和截图任务的 baseUrl。

### 文档与 HTML 产物

Markdown 模板支持以下固定占位符：

| 占位符 | 运行时内容 |
| --- | --- |
| {{title}} | 任务标题 |
| {{content}} | Agent Worker 生成的正文 |
| {{sourceChangeSummary}} | 源码 revision 的变更摘要 |
| {{screenshots}} | 已发布截图的 Markdown 引用集合 |

文档渲染过程会检查模板路径必须位于 docs/ 下，并拒绝绝对路径、反斜杠和 .. 路径。HTML 产物会经过安全策略处理，预览接口也会把危险的 HTML 类型降级为纯文本展示。

### 智能截图工作流

截图不是“打开固定 URL 后截一张图”。每个截图标记描述的是一个可验证的页面目标：

~~~markdown
<!-- agent-studio:screenshot:v1 {"id":"users-admin","loginProfileRef":"admin-profile","target":"用户列表","menuPath":["管理后台","用户"],"routePath":"/admin/users","actions":[{"type":"click","locator":{"kind":"role","role":"button","name":"筛选"}},{"type":"wait","locator":{"kind":"heading","name":"用户列表"}}],"caption":"管理员用户列表"} -->
~~~

字段含义：

- id：文档内唯一的截图标识。
- loginProfileRef：要使用的登录档案引用。
- target：页面目标的可读名称。
- routePath：应用相对路径，例如 /admin/users。
- menuPath：需要依次打开的菜单文字。
- actions：使用 role、label 或 test-id 的语义操作。
- caption：最终文档中使用的截图说明。

支持的动作：

- click
- fill
- select
- check
- uncheck
- wait

支持的语义定位方式：

- role + name
- label
- test-id

执行时会：

1. 创建任务专用的 Playwright BrowserContext。
2. 按登录档案完成登录，并等待异步重定向稳定。
3. 先验证允许的应用来源，再按 routePath 或 menuPath 导航。
4. 按声明的语义动作访问页面和控件。
5. 校验最终页面是否与目标证据匹配。
6. 截取 PNG，计算摘要并通过 Platform API 以不可变 artifact version 发布。
7. 用产物引用替换文档中的截图标记。
8. 任意一步无法证明目标页面、登录态、图片有效性或标记完整性时，产生明确失败或人工确认事件。

因此，页面改版时通常只需要更新模板中的菜单文字、路径或语义目标，不需要把某个页面坐标硬编码进 Worker。

### Provider 与模型

每位用户自行维护 Provider Profile：

- 名称和 Provider 类型。
- OpenAI 兼容的 baseUrl。
- 加密保存的 API Key。
- 可选的模型列表和默认模型。
- Provider 连通性检查与模型发现。

创建任务时，用户可以选择 Provider 和模型，也可以使用自己的默认配置。API Key 只在 Agent Worker 需要调用模型的短时间内解密到内存中，不能通过普通前端列表接口读回。

### 登录档案与多登录态

登录档案用于截图任务访问需要身份的页面，包含：

- reference 和显示名称。
- 登录地址和登录后路径。
- 用户名、密码、提交按钮的结构化 locator。
- 加密保存的账号与密码。

一个组织可以维护多个登录档案，例如：

- admin-profile：管理端菜单和管理员页面。
- member-profile：普通成员可见页面。
- readonly-profile：只读审核页面。

每个截图标记选择一个档案，Browser Worker 会为任务创建隔离上下文。不同档案不会共享 Cookie、Local Storage 或浏览器缓存；任务结束后上下文关闭。

### 任务、事件与人工确认

任务创建需要 Idempotency-Key。同一项目下重复提交同一个 key 且请求内容相同，会返回原任务；如果 key 被用于不同请求，会返回冲突。

典型状态：

- QUEUED
- RUNNING
- PAUSED
- WAITING_FOR_APPROVAL
- SUCCEEDED
- FAILED
- CANCELED

任务事件以带序号的 SSE 返回，事件包含：

- 状态和进度。
- 事件类型。
- 面向用户的消息。
- 失败码。
- 产物引用。
- 发生时间。

人工确认适用于截图目标不确定、需要确认产物引用或其他显式决策。Temporal 只保存结构化状态、引用和失败码，不保存源码全文、Provider Key、登录密码、原始提示词或模型完整输出。

### 产物与分享

产物按树形路径组织，支持：

- Markdown、HTML、JSON、PNG 等类型。
- 版本详情和 SHA-256。
- 内容安全预览。
- 单个文件下载。
- 当前任务所有产物导出为 ZIP。
- 组织内对指定项目或产物进行显式只读分享。

默认 ACL：

- 项目、任务日志和产物只对所有者可见。
- 共享成员只能读取被分享的资源。
- 共享项目不能上传新的源码版本，也不能替所有者创建任务。
- Provider Key 和登录凭证永远不会出现在列表、日志或产物响应中。

## API 快速参考

所有面向用户的 API 以 /api/v1 开头，并使用 Bearer 会话令牌。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/v1/setup/first-admin | 首次启动创建组织和首位管理员 |
| POST | /api/v1/auth/login | 登录并取得会话 |
| POST | /api/v1/auth/register | 注册账号 |
| POST | /api/v1/auth/verify-email | 验证邮箱令牌 |
| POST | /api/v1/auth/logout | 注销会话 |
| GET/POST | /api/v1/projects | 查询或创建项目 |
| GET | /api/v1/projects/{id}/revisions | 查询源码 revision |
| POST | /api/v1/projects/{id}/git/test | 检查 Git URL |
| POST | /api/v1/projects/{id}/git/branches | 查询远程分支 |
| POST | /api/v1/projects/{id}/imports/git | 导入 Git revision |
| POST | /api/v1/projects/{id}/imports/zip | 上传 ZIP revision |
| GET/POST | /api/v1/templates | 查询或创建模板 |
| GET | /api/v1/templates/{id}/versions | 查询模板版本 |
| POST | /api/v1/templates/{id}/versions | 创建新模板版本 |
| POST | /api/v1/templates/{id}/copy | 复制公共模板 |
| GET/POST/PATCH | /api/v1/providers | Provider 配置 |
| POST | /api/v1/providers/{id}/test | 检查 Provider |
| POST | /api/v1/providers/{id}/models/discover | 发现模型 |
| GET/POST/PATCH | /api/v1/login-profiles | 登录档案配置 |
| POST | /api/v1/tasks | 创建幂等任务 |
| GET | /api/v1/tasks | 查询可读任务 |
| POST | /api/v1/tasks/{id}/pause | 暂停任务 |
| POST | /api/v1/tasks/{id}/resume | 恢复任务 |
| POST | /api/v1/tasks/{id}/cancel | 取消任务 |
| GET | /api/v1/tasks/{id}/events | SSE 任务事件 |
| GET/POST | /api/v1/tasks/{id}/approvals | 查询或创建人工确认 |
| POST | /api/v1/tasks/{id}/approvals/{approvalId}/decision | 提交确认决定 |
| GET | /api/v1/tasks/{id}/artifacts | 查询产物树 |
| GET | /api/v1/artifacts/{id} | 查询产物详情 |
| GET | /api/v1/artifacts/{id}/preview | 受限预览 |
| GET | /api/v1/artifacts/{id}/download | 下载单个产物 |
| GET | /api/v1/tasks/{id}/artifacts/export.zip | 导出任务产物 |

internal 路由供服务间调用，使用独立的 Worker Token，不应直接暴露给浏览器或公网。

### 登录示例

以下请求只展示字段形状，不包含真实凭证：

~~~sh
curl -sS http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"replace-with-a-12-character-password"}'
~~~

响应中的 accessToken 只应放在本地会话或受保护的 Secret 管理中。不要把它写进 Git、Issue、截图或普通日志。

### ZIP 上传示例

~~~sh
curl -sS http://localhost:8080/api/v1/projects/PROJECT_ID/imports/zip \
  -H 'Authorization: Bearer ACCESS_TOKEN' \
  -F 'file=@./project.zip'
~~~

前端会在上传前检查扩展名、空文件、100 MiB 上限和项目是否为共享只读项目；服务端仍会再次检查文件头、大小和归档安全性。

## 本地运行

### 前置条件

- macOS、Linux 或 Windows + WSL2。
- Docker Desktop 或 Docker Engine，能够运行 Compose。
- Java 21。
- Node.js 22（前端 Docker 镜像使用 Node 22）。
- Git。
- 如果要连接真实模型，需要一个 OpenAI 兼容 Provider 的 HTTPS 地址和 API Key。
- 如果要执行真实截图，需要一个可从 Browser Worker 访问的测试应用。

### 方式一：完整 Compose

生成只存在于本机的环境文件：

~~~sh
cp deploy/.env.example .env
./deploy/generate-local-env.sh .env
~~~

启动 PostgreSQL、Temporal、MinIO、四个后端服务和前端：

~~~sh
docker compose --env-file .env up --build
~~~

启动后可访问：

- 工作台：http://localhost:8088
- Platform API：http://localhost:8080
- Temporal gRPC：localhost:7233
- MinIO 控制台：http://localhost:9001
- Mailpit：http://localhost:8025

第一次打开工作台时，进入“首次初始化”，填写 .env 中的 SETUP_TOKEN、组织名称、管理员邮箱和至少 12 位密码。之后使用普通“账号登录”。

停止服务：

~~~sh
docker compose --env-file .env down
~~~

只停止容器而保留数据库和对象卷：

~~~sh
docker compose --env-file .env stop
~~~

如果需要完全清理本地数据，确认不再需要 PostgreSQL、Temporal 或 MinIO 中的内容后再使用带卷参数的 Compose 清理命令。生产环境不要直接复用开发卷。

### 方式二：前端热更新 + 后端容器基础设施

编辑 Java 服务时，可以只让 Compose 提供 PostgreSQL、MinIO、Mailpit 和 Temporal，把四个应用 JAR 跑在宿主机：

~~~sh
cp deploy/.env.example .env
./deploy/generate-local-env.sh .env
./deploy/run-local.sh .env start
npm ci --no-audit --no-fund
npm run dev -- --host 127.0.0.1 --port 4177
~~~

这个模式下前端是 http://localhost:4177，run-local.sh 会：

1. 启动基础设施容器。
2. 构建 Maven reactor。
3. 等待 Platform API 健康。
4. 启动 platform-api、workflow-service、agent-worker 和 browser-worker。
5. 把 PID 与日志写到 .local-runtime/。

查看状态或停止宿主机 JVM：

~~~sh
./deploy/run-local.sh .env status
./deploy/run-local.sh .env stop
~~~

### 单独构建

后端全量测试：

~~~sh
./mvnw test
~~~

前端依赖安装与生产构建：

~~~sh
npm ci --no-audit --no-fund
npm run build
~~~

前端模板、错误提示和上传限制测试：

~~~sh
npm run test:template-form
~~~

前端开发服务器：

~~~sh
npm run dev -- --host 127.0.0.1 --port 4177
~~~

## 配置说明

deploy/.env.example 是变量清单，不包含真实密钥。推荐始终使用 deploy/generate-local-env.sh 生成本地 .env。

| 变量 | 用途 | 默认或建议 |
| --- | --- | --- |
| POSTGRES_DB | Platform 数据库名 | agent_studio |
| POSTGRES_USER / POSTGRES_PASSWORD | Platform 数据库账号 | 使用随机值 |
| SETUP_TOKEN | 首次创建管理员 | 使用随机值，初始化后继续保密 |
| ENCRYPTION_KEY | Provider/登录凭证加密 | 使用随机 Base64 key |
| AGENT_WORKER_TOKEN | Platform 与 Agent Worker | 使用随机值 |
| BROWSER_WORKER_TOKEN | Platform 与 Browser Worker | 使用随机值 |
| WORKFLOW_SERVICE_TOKEN | Platform 与 Workflow Service | 使用随机值 |
| S3_BUCKET | 对象存储桶 | agent-studio |
| S3_ACCESS_KEY / S3_SECRET_KEY | MinIO/S3 访问 | 本地随机值，生产使用 Secret Manager |
| BROWSER_LOCAL_MODE | 浏览器执行模式 | 本地演示按环境设置 |
| BROWSER_ALLOWED_HOSTS | 浏览器允许访问的主机 | 按测试应用配置 |
| BROWSER_ALLOWED_ORIGINS | 浏览器允许的来源 | 按测试应用配置 |
| GIT_ALLOWED_HOSTS | Git 导入主机白名单 | 默认 GitHub/GitLab/Bitbucket |
| GIT_MAX_ARCHIVE_BYTES | Git 归档上限 | 104857600 |
| GIT_MAX_FILES | Git 文件数上限 | 20000 |
| GIT_TIMEOUT_SECONDS | Git 请求超时 | 90 |
| PUBLIC_BASE_URL | 邮件链接和前端公开地址 | Compose 为 http://localhost:8088 |
| MAIL_HOST / MAIL_PORT | 邮件服务 | 本地使用 Mailpit |

生产部署还应补充：

- TLS 终止和 HSTS。
- 外部 S3 或兼容对象存储。
- PostgreSQL、Temporal 和对象存储备份。
- 出站 egress 防火墙或代理。
- Secret Manager、KMS 或 Vault。
- 反向代理的请求体、超时和速率限制。
- 日志脱敏、指标、告警和审计保留策略。

## 安全设计

### 认证与权限

- 首次启动只能通过部署环境中的 SETUP_TOKEN 创建首位管理员。
- 注册用户属于当前组织，用户可以拥有自己的 Provider、模板和登录档案。
- 项目、任务、模板和产物在 SQL 查询层执行 owner/organization/share 校验。
- 公共模板可以被复制，但不会让普通成员直接编辑原模板。
- 共享权限只授予指定成员的读取能力。

### 凭证边界

- Platform API 负责加密存储 Provider API Key、登录用户名和密码。
- Agent Worker 只得到当前任务需要的模型凭证。
- Browser Worker 只得到当前任务和选定登录档案需要的登录凭证。
- 凭证不会放进前端列表、任务事件、Temporal workflow history 或普通服务日志。
- Git 私有仓库令牌只用于当前导入请求，不作为项目长期属性保存。

### 输入与网络边界

- Git URL 必须使用 HTTPS，并且主机必须在白名单中。
- Git 连接和重定向会再次检查目标主机，解析到私有、回环或链路本地地址的目标会被拒绝。
- Browser Worker 只允许配置的 host/origin。
- ZIP 导入拒绝伪造文件头、目录穿越、过多条目和过大的展开结果。
- 模板 HTML 拒绝脚本、事件属性和 JavaScript URL。
- 产物预览限制 MIME 类型，并通过 CSP 隔离 HTML/SVG 等内容。

这些校验适合本地和单组织部署；生产仍应在网络层增加出站防火墙、容器隔离和备份恢复演练。

## 仓库结构

~~~text
.
├── contracts/                 # 跨服务 Java records、枚举和 OpenAPI 契约
├── platform-api/              # 认证、项目、模板、Provider、任务、产物和分享
├── workflow-service/          # Temporal workflow 与内部桥接
├── agent-worker/              # Spring AI、LangGraph4j、文档/HTML 生成
├── browser-worker/            # Playwright 隔离会话和截图执行
├── src/                       # Vue 3 前端和可视化模板编辑器
├── deploy/                    # 环境生成、JVM 本地运行、Temporal 和备份脚本
├── docs/                      # UI 设计系统和部署相关说明
├── docker-compose.yml         # 完整本地/演示栈
├── nginx.conf                 # 前端静态资源和 /api 反向代理
├── pom.xml                   # Java 21 Maven reactor
└── package.json              # 前端脚本
~~~

## 测试策略

测试按服务边界组织：

- contracts：任务请求、状态、登录 locator 和 JSON 约束。
- platform-api：认证边界、Provider/模板公共契约、归档安全、产物访问、分享和任务规则。
- workflow-service：Temporal workflow、桥接接口、暂停/恢复/取消和审批信号。
- agent-worker：草稿解析、模型输出校验、文档/HTML 渲染、截图标记解析和产物发布。
- browser-worker：导航白名单、语义 locator、会话隔离、登录重定向、截图失败码和 Token 校验。
- 前端 Node 测试：模板 Schema 表单化、参数合并、上传限制和 API 错误提取。
- 前端构建：vue-tsc --noEmit 加 Vite production build。

建议提交前运行：

~~~sh
./mvnw test
npm run test:template-form
npm run build
git diff --check
~~~

真实 Provider 和真实浏览器属于环境依赖，不能只用单元测试代替。做发布前演练时，应额外验证：

1. 登录一个测试账号。
2. 导入一个小型 Git 或 ZIP 项目。
3. 创建四种任务各至少一次。
4. 为截图任务配置两个登录档案，并访问不同菜单。
5. 观察 SSE 事件、Temporal 状态和最终 artifact tree。
6. 下载、预览和导出产物。
7. 验证共享成员只能读取被分享资源。
8. 检查日志和 Temporal history 中没有凭证和原始模型内容。

## 故障排查

### ZIP 上传显示失败

按以下顺序检查：

1. 文件扩展名必须是 .zip，且浏览器选择的是实际 ZIP 文件。
2. 文件不能为空，原始大小不能超过 100 MiB。
3. 项目不能是共享只读项目。
4. 如果经过 Nginx，确认 /api/ location 的 client_max_body_size 不低于 110m。
5. Platform API 的 multipart 配置应为 max-file-size: 100MB、max-request-size: 110MB。
6. ZIP 必须以标准 ZIP 文件头开头，不能只是把文本文件改名为 .zip。
7. 检查目录穿越、条目数量和展开后大小限制。
8. 查看前端显示的服务端错误；当前实现会保留 HTTP 状态、错误码或服务端 message。

### 页面显示已登录但数据为空

- 先确认浏览器访问的前端端口与 API 端口来自同一套 Compose。
- 清理过期的 agent-studio.session 会话后重新登录。
- 检查 Platform API 健康：curl -i http://localhost:8080/actuator/health。
- 确认数据库卷没有被替换成另一套环境。
- 如果是新环境，先完成“首次初始化”，再创建项目和 Provider。

### 截图任务失败

- 登录档案的 loginUrl 必须是 HTTPS/HTTP 且没有 fragment。
- 检查 loginPath 和 routePath 是否为应用相对路径。
- 优先使用按钮/链接/表单标签的可读名称，不要填写坐标。
- menuPath 的每一级都必须是页面上真实可见的菜单文字。
- BROWSER_ALLOWED_HOSTS 和 BROWSER_ALLOWED_ORIGINS 必须允许目标应用。
- 页面有异步跳转时，增加一个 wait 动作并填写稳定的 heading 或 test-id。
- 同一任务中的每个截图标记必须有唯一 id。
- 如果目标页面发生变化，更新模板版本，不要修改已经执行过的历史任务。

## 毕设与简历展示

### 现场演示脚本

建议把演示控制在 3–5 分钟：

1. 展示工作台和四类工作流入口。
2. 创建项目并导入 ZIP，说明 revision 和 SHA-256。
3. 打开模板库，演示公共模板复制和可视化字段编辑。
4. 创建“用户手册 + 管理员页面截图”任务，展示动态参数和登录档案。
5. 观察任务事件从 QUEUED 到 RUNNING，中途触发一次人工确认。
6. 打开产物树，预览 Markdown/HTML，查看截图版本和校验摘要。
7. 以共享成员身份查看被分享产物，说明默认私有 ACL。
8. 最后展示 Temporal、Browser Worker 和对象存储的服务边界。

### 简历项目描述

可以根据实际完成范围使用下面这段描述：

> Agent Studio｜Java 21 微服务 AI 工作流平台
> 设计并实现面向代码项目交付的自托管工作台，使用 Spring Boot、PostgreSQL、Temporal、LangGraph4j、Spring AI、Playwright Java、Vue 3 和 MinIO/S3，支持 Git/ZIP 导入、可版本化模板、动态参数表单、Markdown/HTML 生成、声明式多登录态截图、人工审批和产物校验分享；通过 owner/share ACL、归档安全校验、凭证加密、Worker Token 和 Temporal 脱敏边界保障任务数据安全。

面试时应重点说明：

- 为什么把“业务事实”放在 Platform API，把“长任务状态”放在 Temporal。
- 为什么截图用语义 locator、route/menu path，而不是 CSS 坐标硬编码。
- 如何保证重复提交不会产生重复任务。
- 如何限制模型输出结构，避免任意 HTML/脚本进入产物。
- 如何让 Provider 和登录凭证只在任务需要时短暂解密。
- 如何从 ZIP 文件头、目录穿越、展开大小和 Nginx multipart 限制解释一次真实故障。

## 许可证

本项目使用 MIT License，详见 LICENSE。
