# Agent Studio

Agent Studio is a self-hostable workspace for turning code projects into maintainable project documentation, user guides, HTML deliverables and verified UI screenshots. A document can declare a screenshot marker with a login profile, route, menu path and semantic actions. The platform resolves that marker in an isolated browser context, checks the resulting page, uploads the image as an immutable artifact and replaces the marker in the document.

The repository is a Java 21 Maven reactor with four deployable backend services and a Vue 3 frontend:

- `platform-api` owns authentication, projects, immutable revisions, templates, providers, login profiles, tasks, approvals, artifacts and object-level sharing. It is the only service with MinIO/S3 credentials.
- `workflow-service` runs Temporal workflows for durable task state, retries, pause/resume/cancel, approval signals and recovery.
- `agent-worker` uses Spring AI 2.0.1 and LangGraph4j to create editable drafts, generate Markdown/HTML and plan marker-driven browser work.
- `browser-worker` runs Playwright Java with isolated contexts, semantic role/label/test-id locators, multi-login support and screenshot publication.
- `frontend` is the premium light Vue workspace for the project-to-artifact flow.

The platform deliberately keeps source text, passwords, provider keys, prompts and model completions out of Temporal history and operational logs. Provider credentials are task-scoped and decrypted only in Agent Worker memory; login credentials are task-scoped and decrypted only in Browser Worker memory. Templates are declarative and cannot execute custom JavaScript or shell commands.

## Local development

Build the backend reactor:

```sh
./mvnw test
```

Build the frontend:

```sh
cd frontend
npm ci --no-audit --no-fund
npm run build
```

The frontend development server proxies `/api` to `http://localhost:8080`. Platform API requires all security-sensitive values from the environment; do not put real credentials in a committed file.

## Compose deployment

The local Compose stack runs PostgreSQL, Temporal, MinIO, the four backend services and the frontend. Generate a local environment file and start the stack:

```sh
cp deploy/.env.example .env
./deploy/generate-local-env.sh .env
docker compose --env-file .env up --build
```

The first-admin setup token is the value generated in `.env`. The service URLs are not exposed on the public network except through the frontend and the configured Platform API port. Production deployment should place a TLS reverse proxy in front of the frontend, use an external S3-compatible store, and supply secrets through the host secret manager or an equivalent KMS/Vault integration.

## Screenshot marker

Markers are versioned and validated before execution:

```markdown
<!-- agent-studio:screenshot:v1 {"id":"users-admin","loginProfileRef":"admin-profile","target":"user list","menuPath":["Administration","Users"],"routePath":"/admin/users","caption":"Admin user list"} -->
```

Only the declared path is executed. Missing route evidence, an unavailable login profile, a wrong page, an invalid image or an unresolved marker produces a specific task failure or a typed Temporal approval request.

## Repository boundaries

The old application and its archived PostgreSQL/workspaces data are separate from this repository. This project contains no old product branding, fixed legacy manual sections or old requirement documents. Git URL import remains fail-closed until a controlled egress proxy can enforce redirect and DNS policy; ZIP import is available with archive traversal and size limits.
