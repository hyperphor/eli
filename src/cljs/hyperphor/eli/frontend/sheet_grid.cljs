(ns hyperphor.eli.frontend.sheet-grid
  "Editable rows x columns grid for a knowledge-spreadsheet sheet
  (design/design.md §4-5). Pure presentational component -- takes sheet data
  and callback fns as props, no direct re-frame dependency, so moving it into
  `way` later (per design.md §5/first-cut.md's reusability note) is a
  relocation, not a rewrite.")

(defn- cell-view
  [{:keys [status value error]} on-click]
  (case status
    :done    [:td.ks-cell.ks-done {:on-click on-click :title "Click to recompute"} value]
    :stale   [:td.ks-cell.ks-stale {:on-click on-click :title "Stale -- click to recompute"} value]
    :pending [:td.ks-cell.ks-pending [:span.ks-spinner "…"]]
    :error   [:td.ks-cell.ks-error {:on-click on-click :title error} "⚠ retry"]
    [:td.ks-cell.ks-empty {:on-click on-click} [:span.ks-fill-hint "fill"]]))

(defn grid
  "props: {:sheet {:row-defs :col-defs :cells}
           :on-row-label :on-col-label       [id label]
           :on-add-row :on-add-col :on-fill-all  []
           :on-remove-row :on-remove-col :on-fill-row :on-fill-col  [id]
           :on-fill-cell  [row-id col-id]}"
  [{:keys [sheet on-row-label on-col-label on-add-row on-add-col
           on-remove-row on-remove-col on-fill-cell on-fill-row on-fill-col
           on-fill-all]}]
  (let [{:keys [row-defs col-defs cells]} sheet]
    [:div.ks-grid
     [:div.ks-toolbar
      [:button {:on-click on-fill-all} "Fill all"]
      [:button {:on-click on-add-row} "+ row"]
      [:button {:on-click on-add-col} "+ column"]]
     [:table.ks-table
      [:thead
       [:tr
        [:th]
        (for [{:keys [id label icon]} col-defs]
          ^{:key id}
          [:th.ks-col-header
           [:div.ks-header-icon icon]
           [:input {:type "text" :value label
                    :on-change #(on-col-label id (-> % .-target .-value))}]
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
            (cell-view (get cells [id col-id] {})
                       #(on-fill-cell id col-id)))])]]]))
