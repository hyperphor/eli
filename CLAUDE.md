# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## What this is

ELI ("Explain Like I'm..."): a knowledge spreadsheet whose cells are computed
by an LLM from a row label, a column label, and a shared template, rather
than typed in by hand. Generalizes Pimento's fixed ELI demo tab
(`design/pimento.html`) into a user-editable tool. Full design:
`design/design.md`; original sketch: `design/first-cut.md`.

`com.hyperphor/way` (web app framework: server, config, frontend shell, ring
handler) and `com.hyperphor/ellum` (multi-provider LLM client) are external
library dependencies (Maven coordinates in `project.clj`), not part of this
repo. `com.hyperphor/nlq` is deliberately *not* a dependency -- ELI has no
query-generation step, just a direct LLM call per cell.

## Commands

```
# Run the server (reads ANTHROPIC_API_KEY/OPENAI_API_KEY from env; PORT as arg or env)
export ANTHROPIC_API_KEY=...
lein run 8090
# -> open http://localhost:8090/

# Frontend dev iteration (after `npm install` once)
lein shadow watch app       # hot-reload cljs
lein shadow release app     # optimized build (what :uberjar's prep-tasks also run)

# Hit the API directly without the UI
curl 'http://localhost:8090/api/ks/cell?row-label=KRAS+oncogene&col-label=a+five-year+old&provider=anthropic'

# Build a deployable uberjar (runs shadow release + AOT as prep-tasks)
lein uberjar
```

There is no Clojure test suite yet.

## Architecture

Backend (`src/clj/hyperphor/eli/`):

- **`core.clj`** -- `-main`: loads `resources/config.edn` via
  `hyperphor.way.config`, starts `hyperphor.way.server` with the handler's
  routes.
- **`handler.clj`** -- `GET /api/ks/cell` is the only API route. M1 has no
  server-side sheet (see design.md §6): the request carries the frontend's
  current `row-label`/`col-label`/`template`/`provider`/`model` directly;
  nothing here persists. Renders the template (`hyperphor.eli.model`) and
  calls `hyperphor.eli.compute/compute-cell`.
- **`compute.clj`** -- `compute-cell` multimethod dispatched on
  `:compute-kind`. Only `:llm` (a straight `hyperphor.ellum.core/query` call)
  is implemented -- see design.md §7 before adding a second kind.

Shared (`src/cljc/hyperphor/eli/model.cljc`):

- The sheet data shape (design.md §4) and `render-template`, the
  `{{row}}`/`{{col}}` substitution both backend (M2+) and frontend will need.

Frontend (`src/cljs/hyperphor/eli/frontend/`):

- **`events.cljs`** -- sheet state (`:sheet` in app-db: `:row-defs`
  `:col-defs` `:cells`, cells keyed `[row-id col-id]`) plus the fill queue
  (`:fill-queue`/`:in-flight`, bounded by `max-concurrency`) that drives
  incremental cell-by-cell filling. Editing a header marks that row's/
  column's cells `:stale` rather than deleting them.
- **`sheet_grid.cljs`** -- the editable grid itself. Deliberately a *pure*
  presentational component (sheet data + callback fns as props, no direct
  re-frame dependency) per design.md §5, so moving it into `way` later (if
  that's ever warranted -- check first, per first-cut.md) is a relocation,
  not a rewrite.
- **`core.cljs`** -- wires re-frame subs/dispatches to the grid, and holds
  the "more than N calls, confirm first" guardrail (design.md §6) in front of
  bulk fill actions.

## Known gaps (by design, see design/design.md §10 for the milestone plan)

- No persistence -- refreshing the page loses the sheet (M2).
- No templates/persona-sets/icons beyond the one hardcoded default sheet (M3).
- No model chooser, per-column model override, or user-supplied API keys (M4).
- No publishing (M5).
