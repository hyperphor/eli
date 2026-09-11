(ns hyperphor.eli.model
  "Shared (cljc) shape of a knowledge-spreadsheet sheet -- see design/design.md §4.
  A sheet is {:title :template :default-model :row-defs :col-defs :cells}.
  row-defs/col-defs are ordered vectors of {:id :label ...}; :cells is sparse,
  keyed by [row-id col-id]. Nothing here is ELI-specific -- :template is an
  arbitrary {{row}}/{{col}} string, per design.md §1/§7."
  (:require [clojure.string :as str]))

;;; TODO in config
(def default-template "Explain {{row}} to {{col}}.")

(defn render-template
  "Substitute the current row/col labels into template's {{row}}/{{col}}
  placeholders. Resolved at compute time, not stored with the cell."
  [template row-label col-label]
  ;; TODO ugh use u/tx
  (-> template
      (str/replace "{{row}}" (str row-label)) 
      (str/replace "{{col}}" (str col-label))))
