(ns hyperphor.eli.frontend.sheet-grid
  "Editable rows x columns grid for a knowledge-spreadsheet sheet
  (design/design.md §4-5). Pure presentational component -- takes sheet data
  and callback fns as props, no direct re-frame dependency, so moving it into
  `way` later (per design.md §5/first-cut.md's reusability note) is a
  relocation, not a rewrite."
  (:require [reagent.core :as r]
            [hyperphor.eli.model :as model]
            [hyperphor.way.markdown :as md]))

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
  []
  ;; Edits go to a local draft, committed on blur only if model/template-error
  ;; passes. :base is the template the draft started from, so a sheet switch
  ;; discards a stale draft.
  (let [draft (r/atom nil)]
    (fn [template col-optional? on-set-template]
      (let [{:keys [base text]} @draft
            editing? (and text (= base template))
            text (if editing? text template)
            error (when editing? (model/template-error text col-optional?))]
        [:details.ks-template-editor
         [:summary "Template"]
         [:textarea.ks-template-input
          {:value text
           :class (when error "ks-invalid")
           :on-change #(reset! draft {:base template :text (-> % .-target .-value)})
           :on-blur #(when (and editing? (not error))
                       (when (not= text template) (on-set-template text))
                       (reset! draft nil))}]
         (when error
           [:div.ks-template-error error " -- not applied."])]))))

(defn- topic-bar
  "Topic-driven bulk generation: a free-text topic (eg \"Immunology\") plus
  buttons that seed/extend rows and columns from it via ellum's
  generate-terms/diversify-terms/generate-personas (design/term-gen.md)."
  [{:keys [topic on-set-topic term-count on-set-term-count
           on-generate-terms generating-terms?
           on-generate-personas generating-personas?]}]
  [:div.ks-topic-bar
   [:input.ks-topic-input
    {:type "text" :placeholder "Topic, e.g. Immunology"
     :value topic :on-change #(on-set-topic (-> % .-target .-value))}]
   [:input.ks-term-count-input
    {:type "number" :min 1 :value term-count
     :on-change #(on-set-term-count (js/parseInt (-> % .-target .-value)))}]
   [:button {:on-click on-generate-terms :disabled generating-terms?
             :title "Generate (or extend) key terms for this topic as rows"}
    (if generating-terms? "…" "🧬 generate terms")]
   [:button {:on-click on-generate-personas :disabled generating-personas?
             :title "Generate personas spanning naive to expert as columns"}
    (if generating-personas? "…" "🎭 generate personas")]])

(defn grid
  "props: {:sheet {:row-defs :col-defs :cells :template :template-col-optional?}
           :on-row-label :on-col-label       [id label]
           :on-set-template  [template]
           :on-add-row :on-add-col :on-fill-all  []
           :on-diversify-row :on-diversify-col  []
           :diversifying-row? :diversifying-col?  bool
           :topic :term-count  string/int (from sheet)
           :on-set-topic :on-set-term-count  [val]
           :on-generate-terms :on-generate-personas  []
           :generating-terms? :generating-personas?  bool
           :on-remove-row :on-remove-col :on-fill-row :on-fill-col  [id]
           :on-fill-cell  [row-id col-id]}"
  [{:keys [sheet on-row-label on-col-label on-set-template on-add-row on-add-col
           on-diversify-row on-diversify-col diversifying-row? diversifying-col?
           topic term-count on-set-topic on-set-term-count
           on-generate-terms on-generate-personas generating-terms? generating-personas?
           on-remove-row on-remove-col on-fill-cell on-fill-row on-fill-col
           on-fill-all]}]
  (let [{:keys [row-defs col-defs cells template]} sheet]
    [:div.ks-grid
     [topic-bar {:topic topic :on-set-topic on-set-topic
                 :term-count term-count :on-set-term-count on-set-term-count
                 :on-generate-terms on-generate-terms :generating-terms? generating-terms?
                 :on-generate-personas on-generate-personas :generating-personas? generating-personas?}]
     [:div.ks-toolbar
      [:button {:on-click on-fill-all} "Fill all"]
      [:button {:on-click on-add-row} "+ row"]
      ;; Asks the LLM to extend the existing rows/columns with one new,
      ;; maximally-diverse label (ellum.terms/diversify-terms) instead of a blank
      ;; placeholder -- e.g. more genes, more personas, in the spirit of the
      ;; set already on the sheet.
      [:button {:on-click on-diversify-row :disabled diversifying-row?
                :title "Ask the LLM to suggest one more row like the existing ones"}
       (if diversifying-row? "…" "✨ diversify rows")]
      [:button {:on-click on-add-col} "+ column"]
      [:button {:on-click on-diversify-col :disabled diversifying-col?
                :title "Ask the LLM to suggest one more column like the existing ones"}
       (if diversifying-col? "…" "✨ diversify columns")]
      [template-editor template (:template-col-optional? sheet) on-set-template]]
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
