# Agent Studio

An AI workflow workspace for importing software projects, generating project documentation, applying reusable output templates, and capturing browser screenshots.

The platform is built as four Java services with a Vue 3 frontend. Its local stack runs with Docker Compose; the production profile targets a single Linux host and an S3-compatible object store.

## Planned workflows

- Import a project from Git or ZIP.
- Generate Markdown project documentation into `docs/`, review changes, and update documentation incrementally.
- Optionally render documentation as HTML and capture screenshots from a user-provided running application URL.
- Manage personal and administrator-published templates, task history, approvals, and artifacts.

## Development

See the module structure and local run instructions as they are added. Never commit credentials or `.env` files.
