(ns hyperphor.eli.frontend.sheet-grid
  "Editable rows x columns grid for a knowledge-spreadsheet sheet
  (design/design.md §4-5). Pure presentational component -- takes sheet data
  and callback fns as props, no direct re-frame dependency, so moving it into
  `way` later (per design.md §5/first-cut.md's reusability note) is a
  relocation, not a rewrite."
  (:require [hyperphor.way.markdown :as md]))

;; Invoked as [cell-view cell on-click] (a hiccup element, not a plain call)
;; so `for`'s ^{:key ...} metadata -- attached to that literal vector, not to
;; whatever a called fn happens to return -- actually reaches reagent.
;; :done/:stale values are LLM markdown -- rendered via way's existing
;; markdown renderer (same (md/render text) pattern okc/nlq use), not raw text.
(defn- cell-view
  [{:keys [status value error]} on-click]
  (case status
    :done    [:td.ks-cell.ks-done {:on-click on-click :title "Click to recompute"} (md/render value)]
    :stale   [:td.ks-cell.ks-stale {:on-click on-click :title "Stale -- click to recompute"} (md/render value)]
    :pending [:td.ks-cell.ks-pending [:span.ks-spinner "…"]]
    :error   [:td.ks-cell.ks-error {:on-click on-click :title error} "⚠ retry"]
    [:td.ks-cell.ks-empty {:on-click on-click} [:span.ks-fill-hint "fill"]]))

(defn- template-editor
  "Collapsed by default -- editing the template is an advanced move (it
  affects every cell at once) rather than a basic one, so it stays tucked
  under a <summary> instead of sitting next to the row/column buttons."
  [template on-set-template]
  [:details.ks-template-editor
   [:summary "Template"]
   [:textarea.ks-template-input
    {:value template
     :on-change #(on-set-template (-> % .-target .-value))}]])

(defn grid
  "props: {:sheet {:row-defs :col-defs :cells :template}
           :on-row-label :on-col-label       [id label]
           :on-set-template  [template]
           :on-add-row :on-add-col :on-fill-all  []
           :on-remove-row :on-remove-col :on-fill-row :on-fill-col  [id]
           :on-fill-cell  [row-id col-id]}"
  [{:keys [sheet on-row-label on-col-label on-set-template on-add-row on-add-col
           on-remove-row on-remove-col on-fill-cell on-fill-row on-fill-col
           on-fill-all]}]
  (let [{:keys [row-defs col-defs cells template]} sheet]
    [:div.ks-grid
     [:div.ks-toolbar
      [:button {:on-click on-fill-all} "Fill all"]
      [:button {:on-click on-add-row} "+ row"]
      [:button {:on-click on-add-col} "+ column"]
      [template-editor template on-set-template]]
     [:table.ks-table
      [:thead
       [:tr
        [:th]
        (for [{:keys [id label icon model-override]} col-defs]
          ^{:key id}
          [:th.ks-col-header
           [:div.ks-header-icon icon]
           [:input {:type "text" :value label
                    :on-change #(on-col-label id (-> % .-target .-value))}]
           ;; Effective model as a caption when this column overrides the
           ;; sheet default (design.md §8) -- e.g. a model-comparator sheet.
           (when model-override
             [:div.ks-header-model (:model model-override)])
           [:div.ks-header-actions
            [:a {:on-click #(on-fill-col id)} "fill"]
            [:a {:on-click #(on-remove-col id)} "✕"]]])]]
      [:tbody
       (for [{:keys [id label]} row-defs]
         ^{:key id}
         [:tr
          [:th.ks-row-header
           [:input {:type "text" :value label
                    :on-change #(on-row-label id (-> % .-target .-value))}]
           [:div.ks-header-actions
            [:a {:on-click #(on-fill-row id)} "fill"]
            [:a {:on-click #(on-remove-row id)} "✕"]]]
          (for [{col-id :id} col-defs]
            ^{:key col-id}
            [cell-view (get cells [id col-id] {})
             #(on-fill-cell id col-id)])])]]]))
