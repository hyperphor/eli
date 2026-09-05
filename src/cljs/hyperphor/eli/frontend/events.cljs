(ns hyperphor.eli.frontend.events
  "Sheet state and the incremental-fill queue (design/design.md §6). Sheet
  lives at db's :sheet key; :fill-queue/:in-flight drive a bounded-concurrency
  worker pool over the /api/ks/cell endpoint."
  (:require [re-frame.core :as rf]
            [hyperphor.way.api :as api]
            [hyperphor.eli.model :as model]))

;; In-flight cap: keeps a "fill all" from firing R*C requests at once against
;; the provider's rate limit (design.md §6 step 2).
(def max-concurrency 4)

;;; TODO should probably be in config? Not here in any case
(defn- default-sheet
  "A small seed sheet -- ELI's own persona-explainer default (design.md §8's
  persona-set idea, hardcoded here until templates/persona-sets exist)."
  []
  {:title "ELI"
   :template model/default-template
   :default-model {:provider :openai :model "gpt-4o"}
   :row-defs [{:id :r1 :label "KRAS oncogene"}
              {:id :r2 :label "sgRNA"}]
   :col-defs [{:id :c1 :label "a five-year old" :icon "🧒"}
              {:id :c2 :label "an intelligent layman" :icon "🧑"}]
   :cells {}
   :next-id 3})

(rf/reg-event-db
 :eli/init-sheet
 (fn [db _]
   (assoc db :sheet (default-sheet))))

(rf/reg-sub
 :eli/sheet
 (fn [db _] (:sheet db)))

;;; ── Header editing ────────────────────────────────────────────────────────

(defn- relabel
  [defs id label]
  (mapv #(if (= (:id %) id) (assoc % :label label) %) defs))

(defn- mark-stale
  "Cells touching id at cell-key position `idx` (0 = row, 1 = col) go :stale,
  keeping their last-good :value rather than being deleted (design.md §6 step 5)."
  [cells idx id]
  (into {} (map (fn [[k cell]]
                  [k (if (= (nth k idx) id) (assoc cell :status :stale) cell)]))
        cells))

(rf/reg-event-db
 :eli/set-row-label
 (fn [db [_ row-id label]]
   (-> db
       (update-in [:sheet :row-defs] relabel row-id label)
       (update-in [:sheet :cells] mark-stale 0 row-id))))

(rf/reg-event-db
 :eli/set-col-label
 (fn [db [_ col-id label]]
   (-> db
       (update-in [:sheet :col-defs] relabel col-id label)
       (update-in [:sheet :cells] mark-stale 1 col-id))))

(rf/reg-event-db
 :eli/add-row
 (fn [db _]
   (let [id (keyword (str "r" (get-in db [:sheet :next-id])))]
     (-> db
         (update-in [:sheet :row-defs] conj {:id id :label "New term"})
         (update-in [:sheet :next-id] inc)))))

(rf/reg-event-db
 :eli/add-col
 (fn [db _]
   (let [id (keyword (str "c" (get-in db [:sheet :next-id])))]
     (-> db
         (update-in [:sheet :col-defs] conj {:id id :label "New persona" :icon "👤"})
         (update-in [:sheet :next-id] inc)))))

(rf/reg-event-db
 :eli/remove-row
 (fn [db [_ row-id]]
   (-> db
       (update-in [:sheet :row-defs] #(vec (remove (fn [r] (= (:id r) row-id)) %)))
       (update-in [:sheet :cells] #(into {} (remove (fn [[k _]] (= (first k) row-id))) %)))))

(rf/reg-event-db
 :eli/remove-col
 (fn [db [_ col-id]]
   (-> db
       (update-in [:sheet :col-defs] #(vec (remove (fn [c] (= (:id c) col-id)) %)))
       (update-in [:sheet :cells] #(into {} (remove (fn [[k _]] (= (second k) col-id))) %)))))

;;; ── Fill queue (bounded concurrency) ─────────────────────────────────────

(defn- find-by-id [defs id] (some #(when (= (:id %) id) %) defs))

(defn- queued-or-pending?
  [db k]
  (or (contains? (set (:fill-queue db)) k)
      (= :pending (get-in db [:sheet :cells k :status]))))

(rf/reg-event-fx
 :eli/queue-fill
 (fn [{:keys [db]} [_ items]]
   (let [items (vec (remove #(queued-or-pending? db %) items))]
     {:db (update db :fill-queue (fnil into []) items)
      :dispatch-n (repeat max-concurrency [:eli/pump])})))

(rf/reg-event-fx
 :eli/pump
 (fn [{:keys [db]} _]
   (let [queue (:fill-queue db)
         in-flight (:in-flight db 0)]
     (if (and (seq queue) (< in-flight max-concurrency))
       (let [[row-id col-id] (first queue)]
         {:db (-> db (update :fill-queue subvec 1) (update :in-flight (fnil inc 0)))
          :dispatch [:eli/request-cell row-id col-id]})
       {}))))

(rf/reg-fx
 ::fetch-cell
 (fn [{:keys [params on-success on-failure]}]
   (api/api-get "/ks/cell"
                {:params params
                 :handler #(rf/dispatch (conj on-success (:value %)))
                 :error-handler #(rf/dispatch (conj on-failure %))})))

(rf/reg-event-fx
 :eli/request-cell
 (fn [{:keys [db]} [_ row-id col-id]]
   (let [sheet (:sheet db)
         row (find-by-id (:row-defs sheet) row-id)
         col (find-by-id (:col-defs sheet) col-id)
         {:keys [provider model]} (or (:model-override col) (:default-model sheet))]
     {:db (assoc-in db [:sheet :cells [row-id col-id]] {:status :pending})
      ::fetch-cell {:params {:row-label (:label row)
                              :col-label (:label col)
                              :template (:template sheet)
                              :provider (name (or provider :anthropic))
                              :model model}
                    :on-success [:eli/cell-done row-id col-id]
                    :on-failure [:eli/cell-error row-id col-id]}})))

(rf/reg-event-fx
 :eli/cell-done
 (fn [{:keys [db]} [_ row-id col-id value]]
   {:db (-> db
            (assoc-in [:sheet :cells [row-id col-id]] {:status :done :value value})
            (update :in-flight (fnil dec 0)))
    :dispatch [:eli/pump]}))

(rf/reg-event-fx
 :eli/cell-error
 (fn [{:keys [db]} [_ row-id col-id error]]
   {:db (-> db
            (assoc-in [:sheet :cells [row-id col-id]]
                      {:status :error
                       :error (or (get-in error [:response :error]) "request failed")})
            (update :in-flight (fnil dec 0)))
    :dispatch [:eli/pump]}))
