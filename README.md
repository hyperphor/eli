# ELI

ELI ("Explain Like I'm...") is a knowledge spreadsheet: rows and columns are
user-defined labels (terms and personas, by default), and each cell is filled
in live by an LLM from a shared template. Generalizes Pimento's fixed ELI
demo tab into an editable tool. See `design/design.md` for the full design
and `design/first-cut.md` for the original sketch.

## Status

M1: a single in-memory sheet, no persistence yet -- refreshing the page loses
your edits. Proves the interaction model (editable grid, incremental fill
with bounded concurrency) before persistence/templates/publishing (later
milestones in design.md §10).

## Running

```
export ANTHROPIC_API_KEY=...   # or OPENAI_API_KEY, if you switch the default provider
lein run 8090
```
then open `http://localhost:8090/`.

For frontend dev iteration, `npm install` once, then either
`lein shadow watch app` (hot-reload) or `lein shadow release app` (optimized,
what the `:uberjar` profile's `prep-tasks` also run).

Hit the API directly without the UI:
```
curl 'http://localhost:8090/api/ks/cell?row-label=KRAS+oncogene&col-label=a+five-year+old&provider=anthropic'
```
