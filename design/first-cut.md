# The basic idea

Take the ELI grid from Pimento and generalize it so a user can specify arbitrary sets of terms and personas.

The Pimento grid is available as design/pimentio.html, use that as a model.


Alias: CrossProduct 

# Details

User has a spreadsheet-like UI where they can edit the top row (as personas) and the left column (terms). 

Cells are filled in by the LLM. Display has to be incremental

# Archtecture

Use pici/okc as a model.

Like it: use Hyperphor.Way, maybe relevant parts of Hyperphor/NLQ. If need be, move functionality to the appropriate system (check with me first).

Deploy on Heroku (use NLQ-AACT and pici/oks as models)

Grid should be done in a reusable way, eg could be part of a semantic spreadsheet


# Various refinements

## Persistence/publishing
## Template
## Persona sets
## Persona icons
## User-supplied API keys/model chooser

## Per-column model selection 
Because then you can use it for comparitive testing, like I did for CDD


# Generalizations

## Knowledge spreadsheets

In a sense this is a specical case of an ks. So implement it that way.

To understand semantic spreadsheets see https://hyperphor.com/ammdi/SmartTables

And "knowledge spreadsheet" is a much better name. 



