(ns hyperphor.eli.compute
  "Cell computation, dispatched by a column's :compute-kind (design/design.md §7).
  Only :llm is implemented -- ELI itself is just this method plus a seeded
  template/persona-set config, not a separate code path."
  (:require [hyperphor.ellum.core :as llm]
            [hyperphor.ellum.extract :as extract]))

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

(defn generate-labels
  "Run a free-form prompt (eg \"generate 10 key terms in X\") and return the
  resulting list of short strings, or nil if the response didn't parse."
  [prompt provider model]
  (let [response (llm/query provider
                            (str prompt "\n\nReturn the results as a JSON vector of short strings in a ```json code block.")
                            :model model)
        [values] (extract/extract-json response)]
    (when (sequential? values)
      (seq (filter string? values)))))

(defmethod compute-cell :default
  [kind _req]
  (throw (ex-info (str "Unknown compute-kind: " kind) {:kind kind})))
