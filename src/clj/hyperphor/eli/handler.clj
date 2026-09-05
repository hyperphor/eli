(ns hyperphor.eli.handler
  (:require [clojure.string :as str]
            [compojure.core :refer [defroutes context GET]]
            [hyperphor.way.handler :as wh]
            [hyperphor.eli.model :as model]
            [hyperphor.eli.compute :as compute]))

;;; GET, not POST -- matches the qbox-endpoint convention okc/nlq-aact already use
;;; for their own single LLM-backed API call (a query, not a state mutation; no
;;; JSON-POST-body handler exists yet anywhere in this stack to model one on).
(defn cell-endpoint
  "M1: no server-side sheet (design.md §6) -- the frontend sends the current
  in-memory row/col labels, template and provider/model directly; nothing here
  persists. Returns {:value <llm text>}."
  [row-label col-label template provider model]
  {:value (compute/compute-cell
           :llm
           {:prompt (model/render-template (if (str/blank? template) model/default-template template)
                                            row-label col-label)
            :provider (keyword (if (str/blank? provider) "anthropic" provider))
            :model (when-not (str/blank? model) model)})})

(defroutes site-routes)

(defroutes api-routes
  (context "/api/ks" []
    (GET "/cell" [row-label col-label template provider model]
      (wh/content-response (cell-endpoint row-label col-label template provider model)))))

;;; Warning: do not `(def app ...)` -- config isn't necessarily loaded yet at
;;; compile time (same caveat as okc/nlq-aact's handler.clj).
(defn app
  []
  (wh/app site-routes api-routes))
