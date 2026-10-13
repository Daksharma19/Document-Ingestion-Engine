# docextract-web

React + TypeScript + Vite UI for the docextract API.

## Run
```bash
./mvnw spring-boot:run          # backend on :8080 (from repo root)
cd frontend && npm install && npm run dev   # UI on :5173, /api proxied to :8080
```
`npm run build` type-checks and emits `dist/`. Set `VITE_API_BASE_URL` when the API is not same-origin.

## Structure
```
src/
  api/            the only code that knows HTTP: typed client + types mirroring the Java records
  components/     generic, feature-agnostic UI primitives (Card, Badge)
  features/
    extraction/   everything for the extract flow, colocated
      components/ form, dropzone, results
      hooks/      request lifecycle (abort, stale-response guard), backend health polling
      presets.ts  starter schemas
      validateInput.ts  client-side mirror of the backend's 400 checks
  lib/            pure helpers (formatting, download)
  styles/         design tokens (CSS variables, light/dark) + global styles
```

Principles:
- **Feature-first folders.** A new screen (e.g. evaluation reports) goes in `features/<name>/` without touching others.
- **One boundary to the backend.** Components never call `fetch`; when the Java contract changes, `api/types.ts` is the one place to update.
- **Per-file failures are data, not exceptions.** `status != OK` is rendered on its card. Only request-level 4xx/network errors become `ApiError`.
- **No extra dependencies until they pay for themselves.** No router, state library or CSS framework yet. Add React Router at the second page and TanStack Query when there is server state to cache.
