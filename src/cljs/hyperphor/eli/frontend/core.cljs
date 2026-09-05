(ns hyperphor.eli.frontend.core
  "App shell -- one sheet, no tabs (M1 has no other views). Modeled on
  nlq-aact's own minimal frontend/core.cljs."
  (:require [re-frame.core :as rf]
            [hyperphor.way.ui.init :as init]
            [hyperphor.eli.frontend.events]
            [hyperphor.eli.frontend.sheet-grid :as grid]))

;; Above this many LLM calls in one bulk fill, ask first -- adding two rows and
;; three columns can otherwise silently trigger hundreds of paid calls
;; (design.md §6's cost guardrail).
(def confirm-threshold 12)

(defn- confirmed-items
  [items]
  (if (> (count items) confirm-threshold)
    (when (js/confirm (str "This will make " (count items) " LLM calls. Continue?"))
      items)
    items))

(defn site-header
  []
  [:div.site-hero
   [:h2 "ELI — Explain Like I'm..."]])

(defn app-ui
  []
  (when-let [sheet @(rf/subscribe [:eli/sheet])]
    (let [{:keys [row-defs col-defs]} sheet
          all-items (vec (for [r row-defs c col-defs] [(:id r) (:id c)]))]
      [:div.eli-app
       [site-header]
       [grid/grid
        {:sheet sheet
         :on-row-label #(rf/dispatch [:eli/set-row-label %1 %2])
         :on-col-label #(rf/dispatch [:eli/set-col-label %1 %2])
         :on-add-row #(rf/dispatch [:eli/add-row])
         :on-add-col #(rf/dispatch [:eli/add-col])
         :on-remove-row #(rf/dispatch [:eli/remove-row %])
         :on-remove-col #(rf/dispatch [:eli/remove-col %])
         :on-fill-cell #(rf/dispatch [:eli/queue-fill [[%1 %2]]])
         :on-fill-row (fn [row-id]
                        (when-let [items (confirmed-items (vec (for [c col-defs] [row-id (:id c)])))]
                          (rf/dispatch [:eli/queue-fill items])))
         :on-fill-col (fn [col-id]
                        (when-let [items (confirmed-items (vec (for [r row-defs] [(:id r) col-id])))]
                          (rf/dispatch [:eli/queue-fill items])))
         :on-fill-all (fn []
                        (when-let [items (confirmed-items all-items)]
                          (rf/dispatch [:eli/queue-fill items])))}]])))

(defn ^:export init
  []
  (init/init app-ui #(rf/dispatch-sync [:eli/init-sheet])))
