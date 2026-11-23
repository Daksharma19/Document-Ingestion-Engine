# PLAN: docextract UI v2, Extract + Parse modes and a design-system pass

## Goal
Turn the single-screen extractor into one polished tool with two modes:
- **Extract**: the existing `/api/extract` flow, restyled.
- **Parse**: new. `POST /api/parse` shows each document's pages, how each page was read and why, its blocks as
  readable content, and the Markdown (copy and download).

Stack stays the same: React 18.3.1, TS 5.6, Vite 5.4, plain CSS tokens, no router or UI library. The mode
switch is a segmented control in the top bar. It also writes `location.hash` (`#extract` / `#parse`) so a reload
keeps the mode, which needs no router.

## Screens and flows
Top bar: brand, a mode switch (`Extract | Parse`), and the health pill (checking / online / offline). An offline
pill is a warning, not a blocker: submit stays enabled and the request error explains what failed.

Both modes use the same layout: a form column on the left and a results column on the right. Below 900px the
columns stack, and at 375px everything is one column with no horizontal scroll (tables scroll inside their own box).

- **Extract**: same form as today (Documents, Schema [auto/preset/custom JSON], Business rules, Tier,
  includeText) and the same results (summary stats, then one card per file with a table/json/text view, violations,
  rules and routing meta).
- **Parse**: Documents (up to 50 files, 20 MB each, 25 MB per request), options `includeMarkdown` (default on) and
  `figures` (default on). Results start with summary stats (files, OK/ERROR, pages, pages per method, cost units,
  time) and **Download all JSON**. Then one `ParsedDocumentCard` per file:
  - Header: filename, status badge, kind, mediaType, cost, elapsed.
  - Views (segmented): `Content | Markdown | JSON`. Markdown only appears when `markdown` is present.
  - **Content**: one section per page. The page header has "Page N", a method badge (NATIVE/OCR/LAYOUT/VISION),
    a confidence badge, a "pending" badge when `pending`, and `reason` as muted text. Blocks render as follows:
    HEADING becomes `h3`–`h6` from `level`; PARAGRAPH becomes `p`; consecutive LIST_ITEM blocks are grouped into
    one `ul`; TABLE becomes a table with `rows[0]` as the header row; FIGURE becomes a labelled figure-text box. A
    pending page shows "Not parsed: {reason}". A page with no blocks shows "No text found on this page".
  - **Markdown**: raw Markdown in a `pre` (no Markdown renderer dependency), with Copy and Download `.md`
    buttons.
  - **JSON**: the raw document.
  - An ERROR document shows only its header and `error`.
  - Long documents: pages collapse after the first 3, with "Show all N pages".

Each mode keeps its own state, so switching modes does not lose results. Both hooks stay mounted, and the
inactive page gets the `hidden` attribute.

## Exact wire types (add to `src/api/types.ts`)
```ts
export type PageMethod = 'NATIVE' | 'OCR' | 'LAYOUT' | 'VISION';
export type BlockType = 'HEADING' | 'PARAGRAPH' | 'LIST_ITEM' | 'TABLE' | 'FIGURE';
export interface BBox { x0: number; y0: number; x1: number; y1: number }
export interface Block {            // @JsonInclude(NON_NULL)
  type: BlockType; text?: string; level?: number; rows?: string[][]; bbox?: BBox;
  confidence: number; method?: PageMethod;
}
export interface PageProbe {        // NON_NULL
  width: number; height: number; meaningfulChars: number; lines: number; garbageRatio: number;
  imageAreaRatio: number; inkRatio?: number; functionWordRatio?: number;
}
export interface ParsedPage {       // NON_NULL
  index: number; method: PageMethod; reason?: string; pending: boolean; confidence?: number;
  costUnits: number; probe?: PageProbe; blocks?: Block[];
}
export type ParseStatus = 'OK' | 'ERROR';
export interface ParsedDocument {   // NON_NULL
  filename?: string; mediaType?: string; kind?: string; status: ParseStatus; error?: string;
  pages?: ParsedPage[]; markdown?: string; costUnits: number; elapsedMs: number;
}
export interface ParseRequest { files: File[]; includeMarkdown: boolean; figures: boolean }
```
Sources: `parse/model/{ParsedDocument,ParsedPage,Block,BlockType,PageMethod,PageProbe,BBox}.java`,
`api/ParseController.java`. The extract types stay as they are, since they already match `FileExtractionResult`.

## Ownership split (no file is touched by both roles)
**frontend-engineer owns** (types, the API client, hooks, feature logic and feature components that compose shared
pieces):
- `src/api/types.ts`, `src/api/client.ts` (add `parse(req, signal)`, which reuses `toApiError` and the AbortError passthrough)
- `src/hooks/useBackendHealth.ts` (moved from `features/extraction/hooks/`; the old file is deleted), `src/hooks/useMode.ts`
- `src/lib/format.ts` (add `downloadText(filename, text, mime)`, `copyToClipboard(text): Promise<boolean>`)
- `src/App.tsx`, `src/main.tsx`
- `src/features/extraction/**` (all existing files, migrated to the shared components)
- `src/features/parse/ParsePage.tsx`, `features/parse/hooks/useParse.ts`, `features/parse/groupBlocks.ts`,
  `features/parse/components/{ParseForm,ParseResultsPanel,ParsedDocumentCard,PageSection,BlockView,MarkdownView}.tsx`
- `FileDropzone` moves to `src/components/FileDropzone.tsx` and the **engineer** owns it, because it holds
  validation logic. It takes a `maxFiles` prop and emits only the classNames listed below.

**frontend-designer owns** (visual system and pure presentational components with no API imports and no state
beyond what the UI needs):
- `src/styles/global.css` (tokens, light/dark, every className in the contract below)
- `index.html` (meta theme-color, title, favicon)
- `src/components/{Badge,Card,Segmented,Stat,EmptyState,Alert,Spinner,Button,HealthPill,DataTable,CodeBlock}.tsx`

The engineer imports designer components only through the prop contracts below. Until those components land, the
engineer may use the existing `Badge`/`Card` and the classNames directly. The designer must not change existing
classNames on `Badge` or `Card` without keeping the old ones.

## Contracts
### Shared component props (designer implements, engineer consumes)
```ts
Badge     { tone?: 'neutral'|'success'|'warning'|'danger'|'info'; children; title?: string }   // existing + title
Card      { title?: ReactNode; actions?: ReactNode; children; as?: 'section'|'article'; id?: string }
Segmented<T extends string> { label: string; options: {value:T; label:string; disabled?:boolean}[]; value:T; onChange:(v:T)=>void; size?: 'sm'|'md' }
          // role="tablist" + roving arrow keys, or a radiogroup; designer picks and documents it, the engineer does not care
Stat      { label: string; value: ReactNode; tone?: BadgeTone }
EmptyState{ title: string; children?: ReactNode; icon?: 'docs'|'parse'; busy?: boolean }   // busy => spinner + aria-live
Alert     { tone: 'danger'|'warning'|'info'; title?: string; children; onDismiss?: () => void }
Spinner   { size?: 'sm'|'md'; label?: string }
Button    { variant?: 'primary'|'secondary'|'ghost'; size?: 'sm'|'md'; busy?: boolean } & ButtonHTMLAttributes
HealthPill{ status: 'checking'|'up'|'down' }
DataTable { head?: string[]; rows: ReactNode[][]; caption?: string; rowClassName?: (i:number)=>string|undefined } // wraps in .table-scroll
CodeBlock { children: string; maxHeight?: number; label?: string }   // <pre tabIndex=0 aria-label>
```
### Feature classNames (engineer emits them, designer styles them in global.css)
- App shell: `.topbar`, `.topbar__brand`, `.topbar__mode`, `.container`, `.layout`, `.layout__form`, `.layout__results`, `.stack`
- Forms: `.field`, `.field__label`, `.field__hint`, `.error-text`, `.checkbox`, `.tiers`, `.tier`, `.tier--selected`, `.tier__hint`, `.form-actions`, `textarea.code`
- Dropzone: `.dropzone`, `.dropzone--active`, `.dropzone__hint`, `.file-list`, `.file-list__name`, `.file-list__size`, `.file-list__count`
- Results: `.results-header`, `.summary`, `.result-title`, `.meta` (dl/dt/dd), `.section`, `.issues`, `.row--invalid`
- Parse: `.page` (one page section), `.page__header`, `.page__reason`, `.page--pending`, `.blocks`, `.block`,
  `.block--heading`, `.block--paragraph`, `.block--list`, `.block--table`, `.block--figure`, `.page-toggle`,
  `.md-actions`
- Method badges: `<Badge tone=…>` with this mapping, owned by the engineer in `features/parse/methodTone.ts`:
  NATIVE→success, OCR→info, LAYOUT→warning, VISION→warning, pending→neutral. The designer makes sure every tone
  meets 4.5:1 contrast in both themes.
- Theme: tokens on `:root`, dark under `@media (prefers-color-scheme: dark)`. No manual theme toggle (out of scope).

## State and data flow
`useParse()` mirrors `useExtraction()`:
`{phase:'idle'} | {phase:'loading', fileCount, startedAt} | {phase:'success', results: ParsedDocument[]} | {phase:'error', message}`.
- One request is in flight at a time. A new run aborts the old one, `cancel()` aborts and returns to idle (or to
  the previous success, see the open questions), and stale responses are dropped.
- Partial results: the API answers once for the whole batch, so a per-file ERROR appears inside the success
  state. The summary counts errors and the card shows `error`.
- Request-level errors are a 400 with problem JSON (no files, >50 files) or 413 (over 25 MB per request).
  `client.parse` maps 413 to "Upload too large (25 MB per request)". The same mapping is added to `extract`.
- Slow requests: the loading state shows elapsed seconds (a `useElapsed(startedAt)` tick every 1s), the file
  count and the Cancel button.
- Client checks before upload: at least 1 file, no more than 50 (parse) or 20 (extract), each file 20 MB or less,
  and a total size of 25 MB or less (shown as a warning in `.file-list__count`).
- Health: `useBackendHealth` polls every 15s and also re-checks when the window gets focus.

## Empty, loading, error and partial states (both modes)
| state | UI |
|---|---|
| idle | `EmptyState` with mode-specific copy |
| loading | `EmptyState busy` with "Parsing 3 files… 12s" and a Cancel button in the form |
| request error | `Alert tone=danger` with the problem `detail`, form kept as it was |
| success, all OK | summary + cards |
| success, some ERROR / pending pages | summary shows counts in the warning tone, card shows the error or a pending page notice |
| success, empty page/doc | "No text found" muted line |
| backend down | health pill shows offline, plus an `Alert tone=warning` above the results while it stays down |

## Accessibility
- The mode switch is keyboard operable and has a visible focus ring (`:focus-visible` on every interactive element).
- Results regions have `aria-live="polite"` for the loading/success transition. Errors use `role="alert"`.
- Headings inside parsed content start at `h3` so they do not break the page outline. `level` 1→h3, 2→h4, 3+→h5.
- Tables use `<th scope="col">` and scroll horizontally inside `.table-scroll` with `tabIndex=0`.
- The dropzone stays a real `<input type=file>` reachable by keyboard. Remove buttons have `aria-label`.
- Copy gives `aria-live` feedback ("Copied") that lasts about 2s.
- `prefers-reduced-motion` disables the spinner animation and transitions.
- Touch targets are at least 40px at mobile width.

## Tasks
Design (D) and Build (B) tracks can run in parallel. B tasks only depend on D tasks where noted, and even then
they can start against the existing classNames.

**Design (frontend-designer)**
- D1 Token pass in `global.css`: color scale (bg/surface/surface-2/border/text/muted/primary + 5 tones),
  spacing scale `--space-1..8`, radii, shadows, type scale, focus ring token, dark theme. Verify 4.5:1 contrast.
- D2 Restyle the base primitives: `.btn*`, `.badge*`, `.card*`, form controls, `.segmented`, `.table` + `.table-scroll`, `.code-block`.
- D3 Build `Segmented`, `Button`, `Stat`, `EmptyState`, `Alert`, `Spinner`, `HealthPill`, `DataTable`, `CodeBlock`; extend `Badge` (title) and `Card` (as, id).
- D4 App shell and responsive layout: `.topbar` with `.topbar__mode`, then 900px and 375px breakpoints.
- D5 Parse classNames: `.page*`, `.block*`, `.md-actions`, `.page-toggle`, styled as a readable document (max line length about 75ch).
- D6 `index.html` meta and theme-color; `prefers-reduced-motion`.

**Build (frontend-engineer)**
- B1 Parse types and `client.parse()`; 413 mapping for both calls. Verify with `tsc`.
- B2 Move `FileDropzone` and `useBackendHealth` to shared locations, add `maxFiles` and the total-size check, and add focus re-check.
- B3 `useMode` (hash-backed) and `App.tsx` mode switch (existing `.segmented` until D3), both pages mounted.
- B4 `useParse` + `ParseForm` + `ParseResultsPanel` (all states, elapsed timer, cancel).
- B5 `groupBlocks.ts` (merges LIST_ITEM runs), `BlockView`, `PageSection` (method/confidence/pending/reason, collapse after 3).
- B6 `MarkdownView` (copy and download `.md`) and `downloadText`/`copyToClipboard` in `lib/format.ts`.
- B7 After D3: migrate Extraction and Parse components to `Segmented`/`Stat`/`EmptyState`/`Alert`/`DataTable`/`CodeBlock`/`Button`.

## QA (frontend-qa)
- `npm run build` (tsc + vite) is clean.
- Use the `/verify` skill to run the API, OCR service and UI. Extract: invoice preset, auto, an invalid JSON
  schema (blocked on the client), cancel mid-run. Parse: a native PDF (NATIVE badges), a scanned PDF (OCR), a
  DOCX (no bbox, a single page), a corrupt file (per-file ERROR next to OK files), the OCR service stopped (pending
  pages with reason), 51 files (400 shown as an alert), and over 25 MB in total.
- Check Markdown copy and download, mode switch keeping results, and reload keeping the mode.
- Check widths 375, 768 and 1280 in light and dark: no horizontal page scroll.
- Keyboard-only pass covering the mode switch, dropzone, views and copy. Run axe or Lighthouse a11y on both modes.

## Open questions (backend)
1. **Page `index` base**: OFFICE_OR_TEXT emits `1`, and PDF uses `PdfPageReader` page index (0- or 1-based not
   confirmed). The UI shows `index` as given, labelled "Page {index}", until this is confirmed.
2. **`kind` values** are a free string (not an enum on the wire), so the UI displays them verbatim.
3. **No progress or streaming**: `/api/parse` returns once per batch, so there is no per-file progress. Cancel aborts
   the HTTP request only, and the server keeps working.
4. **`/health`** returns `Map<String,String>` and the UI only uses `res.ok`. It does not report the OCR service
   status. A field for it would make pending pages explainable up front.
5. Cancel behaviour: go back to idle (the current behaviour) or keep the previous results? The plan keeps the previous success when there is one.
