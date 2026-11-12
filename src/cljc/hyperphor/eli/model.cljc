(ns hyperphor.eli.model
  "Shared (cljc) shape of a knowledge-spreadsheet sheet -- see design/design.md §4.
  A sheet is {:title :template :default-model :row-defs :col-defs :cells},
  plus optional :template-col-optional? for sheets whose columns vary the
  model rather than the prompt.
  row-defs/col-defs are ordered vectors of {:id :label ...}; :cells is sparse,
  keyed by [row-id col-id]. Nothing here is ELI-specific -- :template is an
  arbitrary {{row}}/{{col}} string, per design.md §1/§7."
  (:require [clojure.string :as str]))

;;; TODO in config
(def default-template "Explain {{row}} to {{col}}.")

(defn template-error
  "nil if template is usable, else a message: it must contain {{row}}, {{col}}
  (unless col-optional?), may contain {{topic}}, and no other braces."
  [template col-optional?]
  (cond
    (not (str/includes? template "{{row}}")) "Template must include {{row}}"
    (and (not col-optional?) (not (str/includes? template "{{col}}")))
    "Template must include {{col}}"
    (re-find #"[{}]" (str/replace template #"\{\{(row|col|topic)\}\}" ""))
    "Only {{row}}, {{col}} and {{topic}} may use braces"))

(defn render-template
  "Substitute the current row/col labels and sheet topic into template's
  {{row}}/{{col}}/{{topic}} placeholders. Resolved at compute time."
  [template row-label col-label topic]
  ;; TODO ugh use u/tx
  (-> template
      (str/replace "{{row}}" (str row-label)) 
      (str/replace "{{col}}" (str col-label))
      (str/replace "{{topic}}" (str topic))))
