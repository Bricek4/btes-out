# Agent Studio workspace

The frontend is a Vue 3 and TypeScript workspace for the Agent Studio platform. It keeps chat input transient, shows an editable draft before task creation, and uses the Platform API for projects, templates, providers, login profiles, tasks, events and artifacts.

## Development

```sh
npm install
npm run dev
```

The Vite development server proxies `/api` to `http://localhost:8080`. Set `VITE_API_BASE_URL` when the Platform API is hosted elsewhere.

## Production image

The Dockerfile builds the static bundle and serves it with Nginx. `/api` is proxied to the Compose service named `platform-api`; internal worker paths are never exposed by the frontend container.
