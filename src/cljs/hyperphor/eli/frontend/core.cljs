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

(defn- sheet-chooser
  "Dropdown over sample-sheets.cljs's registry; confirms before switching
  away from a sheet that has filled cells, since sheets aren't persisted."
  [has-filled-cells?]
  (let [current @(rf/subscribe [:eli/sample-id])]
    [:select.ks-sheet-chooser
     {:value (name current)
      :on-change (fn [e]
                   (let [id (keyword (-> e .-target .-value))]
                     (when (or (not has-filled-cells?)
                               (js/confirm "Switch sample sheets? This discards the current sheet."))
                       (rf/dispatch [:eli/load-sheet id]))))}
     (for [{:keys [id label]} @(rf/subscribe [:eli/sample-sheets])]
       ^{:key id} [:option {:value (name id)} label])]))

(defn site-header
  [has-filled-cells?]
  [:div.site-hero
   [:h2 "ELI — Explain Like I'm..."]
   [sheet-chooser has-filled-cells?]])

(defn- not-done?
  "True unless cells already has a :done value for k -- bulk fills (row/col/
  all) skip these so they don't redo cells filled (or hand-edited) already;
  a single-cell click still recomputes explicitly, see on-fill-cell below."
  [cells k]
  (not= :done (get-in cells [k :status])))

(defn app-ui
  []
  (when-let [sheet @(rf/subscribe [:eli/sheet])]
    (let [{:keys [row-defs col-defs cells]} sheet
          all-items (vec (for [r row-defs c col-defs
                                :let [k [(:id r) (:id c)]]
                                :when (not-done? cells k)]
                            k))]
      [:div.eli-app
       [site-header (some #(= :done (:status %)) (vals cells))]
       [grid/grid
        {:sheet sheet
         ;; dispatch-sync, not dispatch: :value is bound to app-db (a
         ;; controlled input), so the db update must land before React
         ;; re-renders or the caret jumps to the end on every keystroke.
         :on-row-label #(rf/dispatch-sync [:eli/set-row-label %1 %2])
         :on-col-label #(rf/dispatch-sync [:eli/set-col-label %1 %2])
         :on-add-row #(rf/dispatch [:eli/add-row])
         :on-add-col #(rf/dispatch [:eli/add-col])
         :on-remove-row #(rf/dispatch [:eli/remove-row %])
         :on-remove-col #(rf/dispatch [:eli/remove-col %])
         ;; Explicit single-cell click: always (re)compute, even if :done.
         :on-fill-cell #(rf/dispatch [:eli/queue-fill [[%1 %2]]])
         :on-fill-row (fn [row-id]
                        (let [items (vec (for [c col-defs
                                                :let [k [row-id (:id c)]]
                                                :when (not-done? cells k)]
                                            k))]
                          (when-let [items (confirmed-items items)]
                            (rf/dispatch [:eli/queue-fill items]))))
         :on-fill-col (fn [col-id]
                        (let [items (vec (for [r row-defs
                                                :let [k [(:id r) col-id]]
                                                :when (not-done? cells k)]
                                            k))]
                          (when-let [items (confirmed-items items)]
                            (rf/dispatch [:eli/queue-fill items]))))
         :on-fill-all (fn []
                        (when-let [items (confirmed-items all-items)]
                          (rf/dispatch [:eli/queue-fill items])))}]])))

(defn ^:export init
  []
  (init/init app-ui #(rf/dispatch-sync [:eli/init-sheet])))
