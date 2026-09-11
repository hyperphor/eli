(ns hyperphor.eli.compute
  "Cell computation, dispatched by a column's :compute-kind (design/design.md §7).
  Only :llm is implemented -- ELI itself is just this method plus a seeded
  template/persona-set config, not a separate code path."
  (:require [hyperphor.ellum.core :as llm]
            [hyperphor.ellum.extract :as llme]
            [clojure.string :as str]
            [hyperphor.multitool.core :as u]))

;; Same brevity instruction pimento's own ELI used (pimento/openai.clj's
;; explain-term :instructions), minus its cancer-immunotherapy framing --
;; that's domain content and doesn't belong in a general compute step.
(def default-system-prompt
  "Keep responses brief and omit introductions and calls to action.")

(defmulti compute-cell
  "kind: dispatch keyword (:llm for now). req: kind-specific map.
  Returns the cell's text value, or throws."
  (fn [kind _req] kind))

(defmethod compute-cell :llm
  [_ {:keys [prompt provider model system] :or {system default-system-prompt}}]
  (llm/query provider prompt :model model :system system))

(defmethod compute-cell :default
  [kind _req]
  (throw (ex-info (str "Unknown compute-kind: " kind) {:kind kind})))

;;; TODO not wired into anything yet
(defn diversify-set
  [elts n]
  (let [elts (str/join ", " elts)]
    (-> (llm/query
         :openai
         (u/tx "extend this set with {{n}} new elements that fit the set but maximize diversity within it: {{elts}}  Return results as json vector, omit the original elements ")
         :model "gpt-4o"
         :system "Keep responses brief and omit introductions and calls to action.")
        llme/extract-json
        first)))
