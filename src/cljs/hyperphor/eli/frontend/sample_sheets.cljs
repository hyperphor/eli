(ns hyperphor.eli.frontend.sample-sheets
  "Pre-configured sheets a user can pick from instead of building one by hand
  in the UI (design/design.md §8's 'templates' idea, code-defined for now
  rather than edn resources). Each entry is a full sheet map per model.cljc's
  shape; :cells is always empty -- filling happens after loading."
  (:require [hyperphor.eli.model :as model]))

(def eli-explainer
  "ELI's own persona-explainer demo: same term, explained to different audiences."
  {:title "ELI — persona explainer"
   :template model/default-template
   :default-model {:provider :openai :model "gpt-4o"}
   :row-defs [{:id :r1 :label "KRAS oncogene"}
              {:id :r2 :label "sgRNA"}
              {:id :r3 :label "tumor microenvironment"}]
   :col-defs [{:id :c1 :label "a five-year old" :icon "🧒"}
              {:id :c2 :label "a patient" :icon "🤑"}
              {:id :c3 :label "a scientist in a different field" :icon "🧑‍🔬"}
              ]
   :cells {}})

(def model-comparator
  "Same prompt, run across several models -- columns are models (via
  :model-override) rather than personas, per design.md §8."
  {:title "Model / provider comparator"
   :template "{{row}}"
   :default-model {:provider :anthropic :model "claude-sonnet-5"}
   :row-defs [{:id :r1 :label "Write a haiku about recursion."}
              {:id :r2 :label "What's the capital of Mongolia, and one fact about it?"}
              {:id :r3 :label "Continue the sequence and explain the rule: 2, 3, 5, 7, 11, __"}]
   :col-defs [{:id :c1 :label "Claude Opus 5" :icon "🔴"
               :model-override {:provider :anthropic :model "claude-opus-5"}}
              {:id :c2 :label "Claude Sonnet 5" :icon "🟠"
               :model-override {:provider :anthropic :model "claude-sonnet-5"}}
              {:id :c3 :label "GPT-5.6 Sol" :icon "🟢"
               :model-override {:provider :openai :model "gpt-5.6-sol"}}
              {:id :c4 :label "GPT-5.4" :icon "🔵"
               :model-override {:provider :openai :model "gpt-5.4"}}]
   :cells {}})

(def translations
  "Same phrase translated into several languages -- a second demo of the
  template axis (col-defs carry no model-override, just a language label)."
  {:title "Translations"
   :template "Translate \"{{row}}\" into {{col}}. Give only the translation."
   :default-model {:provider :anthropic :model "claude-sonnet-5"}
   :row-defs [{:id :r1 :label "Where is the nearest train station?"}
              {:id :r2 :label "Thank you very much for your help."}]
   :col-defs [{:id :c1 :label "French" :icon "🇫🇷"}
              {:id :c2 :label "Japanese" :icon "🇯🇵"}
              {:id :c3 :label "Swahili" :icon "🇰🇪"}]
   :cells {}})

(def style-transfer
  "Same text rewritten in different authors' styles -- columns are authors
  rather than personas or languages, a third demo of the template axis."
  {:title "Style transfer"
   :template "Rewrite the following text in the voice and style of {{col}}, keeping the meaning intact:\n\n\"{{row}}\""
   :default-model {:provider :anthropic :model "claude-sonnet-5"}
   :row-defs [{:id :r1 :label "climate change is coming and we are unprepared"}
              {:id :r2 :label "we are out of paperclips please order some"}]
   :col-defs [{:id :c1 :label "Cormac McCarthy" :icon "🤠"}
              {:id :c2 :label "Virginia Woolf" :icon "🌊"}
              {:id :c3 :label "Edgar Allan Poe" :icon "🐦‍⬛"}]
   :cells {}})

(def sheets
  "Ordered [{:id :label :sheet}] registry backing the sheet chooser. First
  entry is the one :eli/init-sheet loads."
  [{:id :eli :label "ELI — persona explainer" :sheet eli-explainer}
   {:id :model-comparator :label "Model / provider comparator" :sheet model-comparator}
   {:id :translations :label "Translations" :sheet translations}
   {:id :style-transfer :label "Style transfer" :sheet style-transfer}])

(defn by-id [id]
  (:sheet (some #(when (= (:id %) id) %) sheets)))
