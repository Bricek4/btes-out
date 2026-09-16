# Agent Studio

An AI workflow workspace for importing software projects, generating project documentation, applying reusable output templates, and capturing browser screenshots.

The platform is built as four Java services with a Vue 3 frontend. Its local stack runs with Docker Compose; the production profile targets a single Linux host and an S3-compatible object store.

## Planned workflows

- Import a project from Git or ZIP.
- Generate Markdown project documentation into `docs/`, review changes, and update documentation incrementally.
- Optionally render documentation as HTML and capture screenshots from a user-provided running application URL.
- Manage personal and administrator-published templates, task history, approvals, and artifacts.

## Development

## Modules

| Module | Responsibility |
| --- | --- |
| `contracts` | Domain states, DTOs, and the OpenAPI task contract. |
| `platform-api` | HTTP API, persistence migrations, authorization, and task read model. |
| `workflow-service` | Workflow orchestration boundary. |
| `agent-worker` | Documentation-analysis worker boundary. |
| `browser-worker` | Browser-screenshot worker boundary. |

Services depend only on `contracts`; workers and workflow orchestration do not depend on
`platform-api` or on each other.

## Build

JDK 21 is required. A Maven Wrapper is checked in because a system Maven installation is not
required:

```sh
./mvnw test
```

The stable HTTP and SSE task contract is at
[`contracts/openapi/agent-studio-api.yaml`](contracts/openapi/agent-studio-api.yaml). Task creation
and cancellation require an `Idempotency-Key`; a repeated create request returns the previously
accepted task. Never commit credentials or `.env` files.
