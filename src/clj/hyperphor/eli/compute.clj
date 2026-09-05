(ns hyperphor.eli.compute
  "Cell computation, dispatched by a column's :compute-kind (design/design.md §7).
  Only :llm is implemented -- ELI itself is just this method plus a seeded
  template/persona-set config, not a separate code path."
  (:require [hyperphor.ellum.core :as llm]))

(defmulti compute-cell
  "kind: dispatch keyword (:llm for now). req: kind-specific map.
  Returns the cell's text value, or throws."
  (fn [kind _req] kind))

(defmethod compute-cell :llm
  [_ {:keys [prompt provider model]}]
  (llm/query provider prompt :model model))

(defmethod compute-cell :default
  [kind _req]
  (throw (ex-info (str "Unknown compute-kind: " kind) {:kind kind})))
