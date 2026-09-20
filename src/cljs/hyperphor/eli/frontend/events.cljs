(ns hyperphor.eli.frontend.events
  "Sheet state and the incremental-fill queue (design/design.md §6). Sheet
  lives at db's :sheet key; :fill-queue/:in-flight drive a bounded-concurrency
  worker pool over the /api/ks/cell endpoint."
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [hyperphor.way.api :as api]
            [hyperphor.eli.frontend.sample-sheets :as samples]))

;; In-flight cap: keeps a "fill all" from firing R*C requests at once against
;; the provider's rate limit (design.md §6 step 2).
(def max-concurrency 4)

(def default-sample-id (:id (first samples/sheets)))

(rf/reg-event-db
 :eli/init-sheet
 (fn [db _]
   (-> db
       (assoc :sheet (samples/by-id default-sample-id))
       (assoc :sample-id default-sample-id))))

(rf/reg-event-db
 :eli/load-sheet
 (fn [db [_ sample-id]]
   (-> db
       (assoc :sheet (samples/by-id sample-id))
       (assoc :sample-id sample-id))))

(rf/reg-sub
 :eli/sheet
 (fn [db _] (:sheet db)))

(rf/reg-sub
 :eli/sample-sheets
 (fn [_ _] (mapv #(select-keys % [:id :label]) samples/sheets)))

(rf/reg-sub
 :eli/sample-id
 (fn [db _] (:sample-id db)))

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

(defn- mark-done-cells-stale
  "Template changes affect every cell, but only :done ones have a value worth
  re-marking :stale -- leaves already-:stale/:error/empty cells alone."
  [cells]
  (into {} (map (fn [[k cell]]
                  [k (if (= :done (:status cell)) (assoc cell :status :stale) cell)]))
        cells))

(rf/reg-event-db
 :eli/set-template
 (fn [db [_ template]]
   (-> db
       (assoc-in [:sheet :template] template)
       (update-in [:sheet :cells] mark-done-cells-stale))))

(rf/reg-event-db
 :eli/set-topic
 (fn [db [_ topic]] (assoc-in db [:sheet :topic] topic)))

(rf/reg-sub
 :eli/topic
 (fn [db _] (get-in db [:sheet :topic] "")))

(rf/reg-event-db
 :eli/set-term-count
 (fn [db [_ n]] (assoc-in db [:sheet :term-count] n)))

(rf/reg-sub
 :eli/term-count
 (fn [db _] (get-in db [:sheet :term-count] 10)))

(defn- add-row-with-label
  [db label]
  (let [id (keyword (str "r" (get-in db [:sheet :next-id])))]
    (-> db
        (update-in [:sheet :row-defs] conj {:id id :label label})
        (update-in [:sheet :next-id] inc))))

(defn- add-col-with-label
  [db label]
  (let [id (keyword (str "c" (get-in db [:sheet :next-id])))]
    (-> db
        (update-in [:sheet :col-defs] conj {:id id :label label :icon "👤"})
        (update-in [:sheet :next-id] inc))))

(rf/reg-event-db
 :eli/add-row
 (fn [db _] (add-row-with-label db "New term")))

(rf/reg-event-db
 :eli/add-col
 (fn [db _] (add-col-with-label db "New persona")))

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

;;; ── Diversify (extend rows/cols with an LLM-suggested new one) ──────────────

(rf/reg-fx
 ::fetch-diversify
 (fn [{:keys [labels n on-success on-failure]}]
   (api/api-get "/ks/diversify"
                {:params {:labels (str/join "|" labels) :n n}
                 ;; diversify-terms returns nil when the LLM's response didn't
                 ;; parse as JSON -- treat that as a failure, not a row/col
                 ;; with a nil label.
                 :handler #(if-let [label (first (:values %))]
                             (rf/dispatch (conj on-success label))
                             (rf/dispatch (conj on-failure {:reason "no result"})))
                 :error-handler #(rf/dispatch (conj on-failure %))})))

(rf/reg-sub
 :eli/diversifying-row?
 (fn [db _] (boolean (:diversifying-row? db))))

(rf/reg-sub
 :eli/diversifying-col?
 (fn [db _] (boolean (:diversifying-col? db))))

(rf/reg-event-fx
 :eli/diversify-row
 (fn [{:keys [db]} _]
   {:db (assoc db :diversifying-row? true)
    ::fetch-diversify {:labels (map :label (get-in db [:sheet :row-defs]))
                        :n 1
                        :on-success [:eli/diversify-row-done]
                        :on-failure [:eli/diversify-failed :row]}}))

(rf/reg-event-db
 :eli/diversify-row-done
 (fn [db [_ label]]
   (-> db (add-row-with-label label) (assoc :diversifying-row? false))))

(rf/reg-event-fx
 :eli/diversify-col
 (fn [{:keys [db]} _]
   {:db (assoc db :diversifying-col? true)
    ::fetch-diversify {:labels (map :label (get-in db [:sheet :col-defs]))
                        :n 1
                        :on-success [:eli/diversify-col-done]
                        :on-failure [:eli/diversify-failed :col]}}))

(rf/reg-event-db
 :eli/diversify-col-done
 (fn [db [_ label]]
   (-> db (add-col-with-label label) (assoc :diversifying-col? false))))

(rf/reg-event-fx
 :eli/diversify-failed
 (fn [{:keys [db]} [_ which _error]]
   {:db (assoc db (if (= which :row) :diversifying-row? :diversifying-col?) false)
    :dispatch [:error "Couldn't diversify -- try again"]}))

;;; ── Topic-driven bulk generation (generate-terms/generate-personas) ─────────

(rf/reg-fx
 ::fetch-values
 (fn [{:keys [url params on-success on-failure]}]
   (api/api-get url
                {:params params
                 ;; Same "empty/unparseable LLM response is a failure" contract
                 ;; as ::fetch-diversify, just over a whole vector of labels.
                 :handler #(if-let [values (seq (:values %))]
                             (rf/dispatch (conj on-success values))
                             (rf/dispatch (conj on-failure {:reason "no result"})))
                 :error-handler #(rf/dispatch (conj on-failure %))})))

(rf/reg-sub
 :eli/generating-terms?
 (fn [db _] (boolean (:generating-terms? db))))

(rf/reg-sub
 :eli/generating-personas?
 (fn [db _] (boolean (:generating-personas? db))))

;; No existing rows yet -> seed from the topic (generate-terms); otherwise
;; extend the existing set (diversify-terms via the /diversify endpoint
;; already used by diversify-row), per term-gen.md's "either initial or
;; using diversify".
(rf/reg-event-fx
 :eli/generate-terms
 (fn [{:keys [db]} _]
   (let [existing (mapv :label (get-in db [:sheet :row-defs]))
         n (get-in db [:sheet :term-count] 10)]
     {:db (assoc db :generating-terms? true)
      ::fetch-values (if (seq existing)
                       {:url "/ks/diversify"
                        :params {:labels (str/join "|" existing) :n n}
                        :on-success [:eli/generate-terms-done]
                        :on-failure [:eli/generate-terms-failed]}
                       {:url "/ks/generate-terms"
                        :params {:topic (get-in db [:sheet :topic]) :n n}
                        :on-success [:eli/generate-terms-done]
                        :on-failure [:eli/generate-terms-failed]})})))

(rf/reg-event-db
 :eli/generate-terms-done
 (fn [db [_ labels]]
   (-> (reduce add-row-with-label db labels)
       (assoc :generating-terms? false))))

(rf/reg-event-fx
 :eli/generate-terms-failed
 (fn [{:keys [db]} _]
   {:db (assoc db :generating-terms? false)
    :dispatch [:error "Couldn't generate terms -- try again"]}))

(rf/reg-event-fx
 :eli/generate-personas
 (fn [{:keys [db]} _]
   {:db (assoc db :generating-personas? true)
    ::fetch-values {:url "/ks/generate-personas"
                     :params {:topic (get-in db [:sheet :topic]) :n 3}
                     :on-success [:eli/generate-personas-done]
                     :on-failure [:eli/generate-personas-failed]}}))

(rf/reg-event-db
 :eli/generate-personas-done
 (fn [db [_ labels]]
   (-> (reduce add-col-with-label db labels)
       (assoc :generating-personas? false))))

(rf/reg-event-fx
 :eli/generate-personas-failed
 (fn [{:keys [db]} _]
   {:db (assoc db :generating-personas? false)
    :dispatch [:error "Couldn't generate personas -- try again"]}))
