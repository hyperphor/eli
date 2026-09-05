# ELI / Knowledge Spreadsheet — Design

Status: draft, expands [first-cut.md](first-cut.md). Not yet implemented.

## 1. What this is

Pimento's ELI ("Explain Like I'm...") tab is a fixed demo: a table of hardcoded
terms (rows) × audiences (columns), each cell a canned LLM explanation. This
project generalizes that into a real product: a user defines their own rows
and columns, and cells are filled in live by an LLM.

Two names in play:

- **ELI** — the product/demo identity, kept as the running name for the
  persona-explainer use case.
- **Knowledge Spreadsheet** — the general abstraction ELI is an instance of:
  a spreadsheet whose cells are computed (usually by an LLM) from their row
  key, column key, and a shared template, rather than typed in by hand. This
  is a much better name than "CrossProduct" (the alias first-cut.md floated)
  and has real prior art worth knowing about: [Groups / Web Groups, SRI's
  "knowledge spreadsheets for symbolic biocomputing"](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC3773185/),
  built into Pathway Tools and still running today. See
  `ammdi/pages/SmartTables.md`. Same idea, LLM-cell-filling instead of
  symbolic-computation-filling.

Per first-cut.md's explicit instruction ("in a sense this is a special case
of a KS, so implement it that way"), the general form is the thing being
built, not a later refactor of ELI: core namespaces are named generically
(`hyperphor.eli.sheet`, not e.g. `hyperphor.eli.persona-grid`), and cell
computation is a multimethod dispatching on `:compute-kind` from day one
(§7), defaulting to `:llm`. ELI itself is then just the `:llm` compute-kind
plus a template/persona-set config, not a separate code path. This costs
little — the multimethod has exactly one method (`:llm`) until a second
compute-kind shows up — and it's what was asked for.

## 2. Core interaction

- Top row = column headers ("personas" in ELI), left column = row headers
  ("terms"). Both are free text, user-editable in place.
- User adds/removes rows and columns.
- Each cell is computed from `(row-label, col-label, prompt-template)` by an
  LLM call. A cell is blank until computed, then shows the result.
- Filling is **incremental**: cells populate as their individual calls
  complete, not as one big blocking operation. With R rows × C columns that's
  potentially R×C independent LLM calls — this drives most of the
  architecture below (concurrency limits, per-cell status, cost signaling).
- A cell (or row, or column, or the whole sheet) can be recomputed on
  demand — after editing a header, changing the model, or just wanting a
  fresh answer.

## 3. Prior art already in the hyperphor stack

This is not a greenfield app; it's another member of the `way`-based family
(`pici/okc`, `hyperphor/nlq-aact`) and should reuse their pieces directly
rather than reinvent them:

| Concern | Existing piece | Use for ELI |
|---|---|---|
| LLM calls, multi-provider | `com.hyperphor/ellum "0.1.3"` (`hyperphor.ellum.core/complete`, `/query`, `/complete-stream`) | Cell-fill calls. Already abstracts OpenAI vs. Anthropic, structured JSON output, streaming. Independently released (own `project.clj`, own version) — depend on it directly, don't pull it in transitively via `nlq`. |
| App scaffold: config, ring handler, server, login | `com.hyperphor/way` (`hyperphor.way.config`, `.handler`, `.server`, `.oauth`) | Config via `resources/config.edn` (aero, `#env` secrets), routes via compojure + `wh/app`/`wh/content-response`, Google OAuth2 or HTTP basic-auth already implemented — no new auth code needed. |
| Data grid widget | `hyperphor.way.aggrid` (`ag-table`) | Candidate for the cell grid — see §5 for why it's a partial fit, not a drop-in. |
| Server-rendered form fields | `hyperphor.way.ss-forms` | Editable header inputs if any part of the UI ends up server-rendered rather than pure re-frame. |
| General utils | `com.hyperphor/multitool` | As everywhere else. |
| Deploy | Heroku via `heroku-jvm-application-deployer` jar, `lein uberjar`, `system.properties` — see `nlq-aact/bin/deploy.sh`, `okc/bin/deploy.sh` | Copy verbatim as `eli/bin/deploy.sh`. |

`hyperphor/nlq` itself (NL→SQL/SPARQL) is *not* a dependency here — ELI has
no query-generation step, just direct LLM prompting per cell. Don't pull it
in just because okc does.

Per first-cut.md: if grid-filling or template logic turns out to belong in
`way` or `nlq` rather than living in `eli`, raise that before moving code —
default is build in `eli` first, extract once there's a second consumer.

## 4. Data model

```clojure
;; A sheet is the persistent unit — one ELI grid.
{:id            #uuid "..."
 :title         "Cancer terms x audiences"
 :owner         "user@example.com"        ; from way auth
 :template      "Explain {{row}} to {{col}}."   ; row/col = current labels
 :default-model {:provider :anthropic :model "claude-sonnet-5"}
 :row-defs      [{:id :r1 :label "KRAS oncogene"} ...]
 :col-defs      [{:id :c1 :label "a five-year old" :icon "🧒"
                  :model-override nil}         ; per-column model, §8
                 ...]
 :cells         {[:r1 :c1] {:status :done :value "..." :model {...}
                             :computed-at #inst "..."}
                 [:r1 :c2] {:status :pending}
                 [:r2 :c1] {:status :error :error "rate limited"}
                 ...}}
```

Notes:

- `row-defs`/`col-defs` are ordered vectors of `{:id :label}`, not bare
  strings — `:id` is stable across a label edit (renaming a persona
  shouldn't discard that column's already-computed cells' identity, though
  it should probably invalidate their `:value`; see §6).
- `:cells` is sparse and keyed by `[row-id col-id]`, not a dense matrix —
  most of the time most cells haven't been computed yet.
- The template uses `{{row}}` / `{{col}}` placeholders resolved against the
  *current* label at compute time. ELI's default template is literally
  `"Explain {{row}} to {{col}}."`; nothing ELI-specific is hardcoded into the
  data model itself — that's what makes this a knowledge-spreadsheet instance
  rather than an ELI-only structure. A different template turns the same
  grid into a comparison matrix, a translation table, a code-example
  generator, etc.
- This is the persisted/wire shape (cljc), shared by backend (Postgres
  storage, §9) and frontend (re-frame db).

## 5. Grid widget: build vs. reuse ag-grid

`hyperphor.way.aggrid/ag-table` (wraps ag-grid-community/enterprise via
reagent) is the existing reusable grid in the stack, used for read-mostly
tabular data (samples, patients, NLQ logs) in okc. ELI's grid differs from
those in ways that matter:

- **Column headers must be inline-editable text**, and columns/rows are
  added and removed by the user — ag-grid supports editable *cells* well,
  editable *header labels* and dynamic column add/remove are much rockier
  (custom header components, manual `columnDefs` churn on every rename).
- **Cell rendering needs a per-cell status** (blank / spinner / text /
  error), updated asynchronously and independently as each LLM call
  resolves — doable with a custom `cellRenderer` reading from the re-frame
  db, but ag-grid's change-detection model (row-data diffing) fights an
  update pattern that's naturally "patch this one cell as its promise
  resolves."
- Sheets here are expected to be small (a handful of rows/personas), not the
  hundred-thousand-row tables ag-grid's virtualization exists for.

Recommendation: build the grid as a plain reagent component (a `<table>` of
editable `<th>`/inline `<input>` headers and `<td>` cells subscribing to
`[:cell row-id col-id]`), not on top of `ag-table`. Revisit ag-grid if/when
the knowledge-spreadsheet generalization (§7) needs large sheets, sorting,
or CSV export — ag-grid's export-to-CSV (already used elsewhere, see
`way/aggrid.cljs` comment) is a nice free feature to fall back to later, but
not worth bending the interaction model for now.

First-cut.md also asks that the grid be built "in a reusable way, eg could
be part of a semantic spreadsheet." Concretely: this component lives in its
own namespace (`hyperphor.eli.frontend.sheet-grid` for now) taking its data
and callbacks as args/subs rather than reaching into eli-specific re-frame
keys directly. That makes eventually moving it into `way` (per the
check-with-me-first note in first-cut.md) a relocation, not a rewrite.

## 6. Compute flow (incremental fill)

No websocket/SSE infrastructure exists anywhere in this stack today (way,
nlq, okc, nlq-aact all confirmed) — don't add it just for this. Plain
per-cell request/response over the existing ring stack is enough:

1. User action ("fill sheet" / "fill row" / "fill column" / edit a single
   cell / edit a header that invalidates dependent cells) enqueues a set of
   `[row-id col-id]` work items.
2. Frontend drains the queue with a **bounded concurrency pool** (e.g. 4–6
   in flight at once) — necessary both to avoid hammering the provider's
   rate limit and because an unthrottled R×C fan-out of concurrent requests
   is exactly the failure mode ellum's `key-status`/rate-limit-headers
   feature exists to help diagnose.
3. Each request is a single backend endpoint call. Its exact shape depends
   on whether persistence (§9) exists yet:
   - **M1 (no persistence):** the request is self-contained —
     `POST /api/ks/cell {:row-label :col-label :template :provider :model}`
     — the frontend has already resolved labels/template/effective model
     (`col-def model-override` else `sheet default-model`) from its
     in-memory sheet state, since there's no server-side sheet to look up
     yet. The endpoint just calls `ellum/complete` and returns the text via
     `wh/content-response`.
   - **M2+ (persisted sheets):** the request narrows to
     `POST /api/ks/cell {:sheet-id :row-id :col-id}` — the backend resolves
     the sheet, current labels, template and effective model itself, calls
     `ellum/complete`, persists the result cell, and returns it. Preferred
     going forward since it removes a class of client/server drift, but not
     buildable until a sheet actually lives somewhere server-side.
4. As each response lands, the frontend dispatches an event that updates
   just that cell's re-frame state to `:done`/`:error` — the table
   re-renders only the changed `<td>`.
5. Editing a row/column label marks that row's/column's cells `:stale`
   (shown dimmed, not blanked) rather than deleting them, so a rename
   doesn't lose the last-good answer until a recompute actually runs.

Cost/rate-limit guardrail: before a bulk "fill sheet" on a sheet whose
R×C exceeds a small threshold, show a confirmation with the call count
(`R × C - already-computed`) rather than firing silently — LLM calls cost
real money and this UI makes it trivial to request hundreds of them by
adding two rows and three columns.

## 7. Generalization: Knowledge Spreadsheet

The data model in §4 is already the general form: `template` is an
arbitrary string with `{{row}}`/`{{col}}` substitution, and nothing about
`row-defs`/`col-defs`/`cells` assumes "explain X to Y." What makes it
actually general, per §1, is building this in from the start rather than
generalizing later:

- `col-defs[i].compute-kind` (default `:llm`) selects how that column's
  cells get computed. A true knowledge spreadsheet (per the SRI Groups
  lineage) can mix computation kinds across columns: a lookup, a formula
  over other cells, an NLQ query, alongside LLM-filled columns.
- The backend cell-compute step is a multimethod dispatching on
  `compute-kind`, with one method (`:llm`, doing exactly what §6 describes)
  implemented up front. Adding `:formula` or `:lookup` later is a new
  `defmethod`, not a rewrite of §4/§6.
- ELI-the-product is this multimethod's `:llm` case plus a seeded template
  and persona-set config (§8) — no ELI-specific code path, no
  `hyperphor.eli.explain` namespace sitting next to a generic one.

What's genuinely deferred, because there's no second consumer to design
against yet, is anything *past* one working `:compute-kind`: a
formula language, cross-cell dependency tracking, a plugin API for
third-party compute kinds. Build those against a real second use case, not
speculatively.

## 8. Refinements from first-cut.md

- **Per-column model selection** — `col-defs[i].model-override`
  (`{:provider :model}`), resolved in the compute step (§6 step 3). Directly
  enables comparative testing: same rows, same template, columns = "GPT-4o"
  / "Claude Sonnet" / "Claude Haiku" instead of personas. Column header
  should show the effective model as a small caption under the label.
- **User-supplied API keys / model chooser** — `ellum` already dispatches on
  `:provider`/`:model` per-call, so the plumbing is there; what's missing is
  where the key comes from. Default: server-side keys via `#env` in
  `config.edn` (same as every other app in the stack). For user-supplied
  keys, don't persist them in the sheet document (§4) or let them flow into
  logs — `hyperphor.way.config/redact` already redacts anything
  key-named like a credential server-side, but a user key would need to
  travel per-request (e.g. a header set from browser-local storage) and be
  used only for that request's `ellum/complete` call, never written to the
  sheet's stored doc. Model chooser is a dropdown sourced from
  `ellum/list-models` per provider.
- **Templates** — a named, reusable `{:row-defs :col-defs :template}` seed a
  new sheet can start from (e.g. the ELI persona set below, or "compare
  models" with rows = test prompts and columns = models). Store as edn
  resources initially (same pattern as okc's `#include "prince/config.edn"`
  per-project configs), promote to user-saved templates once persistence
  (§9) exists.
- **Persona sets** — a named `col-defs` preset (e.g. Pimento's five:
  intelligent layman / five-year-old / scientist in a different field /
  biotech investor / patient). Same storage path as templates; a persona set
  is just a template with an empty `row-defs`.
- **Persona icons** — `col-defs[i].icon`, a single emoji (matches the
  favicon convention used for Artifacts elsewhere in this environment — one
  glyph, no asset pipeline). Small fixed default set assignable per persona;
  user can override by typing any emoji into the header.
- **Persistence/publishing** — see §9.

## 9. Persistence & publishing

Nothing in `way`/`okc`/`nlq-aact` persists *user-authored documents* today —
DynamoDB in nlq-aact holds an append-only query log, and while okc does use
`com.datomic/local`, its storage-dir is either a local dev path or synced
from `gs://pici-radiohead/okc/datomic` (see `okc/src/clj/.../datomic.clj`) —
i.e. that data's durability comes from GCS, not from Heroku's dyno disk,
which is ephemeral and wiped on every restart/deploy. Reusing
`com.datomic/local` for ELI's mutable, user-edited sheets would inherit that
same requirement (sync to GCS/S3 on every write, restore on boot) just to
get back to where a plain database already starts. Skip it:

- Store sheets as EDN/transit blobs in **Heroku Postgres**, one row per
  sheet: `sheet(id, owner, doc jsonb-or-text, public boolean, updated_at)`.
  Least new machinery — a Postgres dependency and add-on are already
  standard in this stack (nlq-aact's AACT source, `org.postgresql/postgresql`
  in okc's `project.clj`), and durability is Heroku-native, no sync job
  required. The whole sheet is one opaque document read/written together
  (`doc`), rather than modeling rows/cols/cells as separate tables/entities
  — matches how it's actually used (load, edit, autosave). DynamoDB
  (`hyperphor.nlq.logging.dynamo` is a ready-made client) is an equally
  reasonable alternative if the deployment already leans AWS.
- Ownership/access via whichever of `way`'s two existing auth mechanisms the
  deployment wants — Google OAuth2 (`hyperphor.way.oauth`) for a multi-user
  deployment, or HTTP basic-auth (`:basic-auth-creds` in config.edn, as okc
  uses) if this stays single-tenant/internal.
- **Publish** = a sheet flagged `:sheet/public? true`, servable read-only at
  `/ks/<id>` with no auth — a snapshot of the last-computed cells, not a
  live re-fill. Matches what Pimento's static ELI page already effectively
  is.
- Autosave on every header edit / cell completion rather than an explicit
  save action — consistent with "spreadsheet," and avoids losing completed
  LLM calls (the expensive part) to a missed save.

## 10. Suggested build order

1. **M1 — single-sheet MVP.** Fixed single provider/model from config, no
   persistence (in-memory/re-frame only, refresh loses it), manual grid
   editing, incremental fill with concurrency cap, `:compute-kind` multimethod
   in place with only `:llm` implemented (§7). Proves the interaction model
   and the general shape at once — nothing here gets rewritten to "become"
   general later.

   Repo skeleton, following the okc/nlq-aact pattern directly:
   - `project.clj` — group `com.hyperphor/eli` (or `org.parkerici` if this
     stays PICI-internal, tbd), `lein-shadow` plugin,
     `[com.hyperphor/way ...] [com.hyperphor/ellum "0.1.3"]
     [com.hyperphor/multitool ...]`, `:uberjar` profile with the
     javac-before-aot `:prep-tasks` ordering okc's project.clj calls out
     (skipping it silently breaks `:aot`).
   - `shadow-cljs.edn` / `:shadow-cljs` build config — one `:app` build,
     entry point `hyperphor.eli.frontend.core`.
   - `resources/config.edn` — `:app-title "ELI"`,
     `:app-main "hyperphor.eli.frontend.core.init"`, `:port #env PORT`,
     `:css ["/css/eli.css"]`, provider API keys via `#env`.
   - `bin/deploy.sh`, `system.properties` — copied from `nlq-aact`/`okc`
     nearly verbatim (`lein uberjar` then the `heroku-jvm-application-deployer`
     jar). Neither reference repo has a `Procfile`; the deployer jar supplies
     its own run command, not a git-push/buildpack deploy — confirm this
     still holds before assuming no Procfile is needed.
   - `bin/link-checkouts.sh` — symlink `checkouts/way`, `checkouts/ellum`
     for live local development against sibling checkouts, same purpose as
     okc's version.
   - `src/clj/hyperphor/eli/{handler,sheet,compute}.clj`,
     `src/cljc/hyperphor/eli/model.cljc` (the §4 data shape, shared),
     `src/cljs/hyperphor/eli/frontend/{core,sheet_grid}.cljs`.
2. **M2 — persistence.** Postgres-backed save/load/autosave (§9), per-user
   sheets list.
3. **M3 — templates, persona sets, icons.** Seed-able new sheets (§8).
4. **M4 — model chooser, per-column model, user-supplied keys.** Comparative
   testing use case (§8).
5. **M5 — publishing.** Read-only public URLs (§9).
6. **M6 — additional compute-kinds** (formula/lookup/NLQ columns, §7), once
   a real second use case wants one.

## 11. Open questions

- Streaming per-cell text (via `ellum/complete-stream`) would make fills
  feel more responsive than opaque spinner→text, but adds real complexity
  for a first cut (per-cell SSE-ish plumbing where none exists yet) — defer
  past M1, revisit once the plain request/response version feels slow.
- Should stale-cell invalidation (§6) be automatic on every header edit, or
  require an explicit "recompute" click? Automatic is more spreadsheet-like
  but risks surprise cost; leaning toward marking stale automatically but
  requiring a click to actually re-fire the LLM calls.
- Export: ag-grid's CSV export is free if §5 is revisited later; until then,
  a plain "download as CSV" over the sheet doc is a small, isolated backend
  endpoint and doesn't need to wait on the grid-widget decision.
