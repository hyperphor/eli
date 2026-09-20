(ns hyperphor.eli.handler
  (:require [clojure.string :as str]
            [compojure.core :refer [defroutes context GET]]
            [hyperphor.way.handler :as wh]
            [hyperphor.eli.model :as model]
            [hyperphor.eli.compute :as compute]
            [hyperphor.ellum.terms :as terms]))

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

;;; "|" as a delimiter, not comma, since labels routinely contain commas
;;; themselves ("KRAS oncogene, mutant" etc).
(defn diversify-endpoint
  "Extend an existing set of row/column labels with n new ones that fit the
  set but maximize diversity (ellum.terms/diversify-terms). Returns {:values [...]}."
  [labels n]
  {:values (terms/diversify-terms (str/split labels #"\|") (or n 1))})

(defn generate-terms-endpoint
  "Seed n key terms for a topic from scratch (ellum.terms/generate-terms),
  for when there's no existing set to diversify-endpoint extend yet.
  Returns {:values [...]}."
  [topic n]
  {:values (terms/generate-terms topic (or n 10))})

(defn generate-personas-endpoint
  "Generate n personas spanning naive to expert knowledge of topic
  (ellum.terms/generate-personas). Returns {:values [...]}."
  [topic n]
  {:values (terms/generate-personas topic (or n 3))})

(defroutes site-routes)

(defroutes api-routes
  (context "/api/ks" []
    (GET "/cell" [row-label col-label template provider model]
      (wh/content-response (cell-endpoint row-label col-label template provider model)))
    (GET "/diversify" [labels n]
      (wh/content-response (diversify-endpoint labels (when-not (str/blank? n) (Integer/parseInt n)))))
    (GET "/generate-terms" [topic n]
      (wh/content-response (generate-terms-endpoint topic (when-not (str/blank? n) (Integer/parseInt n)))))
    (GET "/generate-personas" [topic n]
      (wh/content-response (generate-personas-endpoint topic (when-not (str/blank? n) (Integer/parseInt n)))))))

;;; Warning: do not `(def app ...)` -- config isn't necessarily loaded yet at
;;; compile time (same caveat as okc/nlq-aact's handler.clj).
(defn app
  []
  (wh/app site-routes api-routes))
