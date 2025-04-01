(ns chat-app.filters
  (:require
   #?(:clj [models.db :as db])
   [hyperfiddle.electric :as e]
   [chat-app.react :refer [react-component-tree]]
   [hyperfiddle.electric-dom2 :as dom]))

;; Utility functions
(defn toggle [s k]
  (if (s k)
    (disj s k)
    (conj s k)))

(defn typesense-field->ui-name [field]
  ({"type" "dokumenttyper"
    "orgs_short" "organisasjoner"
    "orgs_long" "organisasjoner"
    "owner_short" "eiere"
    "owner_long" "eiere"
    "publisher_short" "utgivere"
    "publisher_long" "utgivere"
    "recipient_short" "mottakere"
    "recipient_long" "mottakere"
    "source_published_year" "år publisert"} field field))

;; Filter Components
(e/defn FilterField [{:as x :keys [options field idx]} ToggleOption ToggleFieldExpanded? enabled?]
  (e/client
   (let [field-id (str "filter-" idx)
         selected-values (map :value (filter :selected? options))]
     (react-component-tree.
      {:component "Combobox"
       :props {:label (str "Velg " (typesense-field->ui-name field))
               :multiple true
               :id field-id
               :size "md"
               :value selected-values
               :onValueChange #?(:cljs (fn [_] nil) :clj identity)}
       :children (concat
                  [{:component "Combobox.Empty"
                    :children ["Fant ingen treff"]}]
                  (mapv (fn [opt]
                          {:component "Combobox.Option"
                           :props {:value (:value opt)}
                           :children [(:value opt)]})
                        options))}))))

(e/defn FilterMsg [msg enabled?]
  (e/client
   (let [mfilter (:message.filter/value msg)]
     (dom/div
      (dom/props {:class "flex gap-8 items-last"})
      (e/for [[idx field] (map vector (range) (mfilter :ui/fields))]
        (FilterField. (assoc field :idx idx)
                      (e/fn ToggleOption [option]
                        (if-not enabled?
                          (e/client
                           (js/alert "Filteret kan ikke endres etter oppfølgningspørsmål er sendt"))
                          (e/server
                           (e/offload
                            #(db/set-message-filter
                              db/conn
                              (:db/id msg)
                              (-> (update-in mfilter [:fields idx :selected-options] toggle option)
                                  (dissoc :ui/fields)))))))
                      (e/fn ToggleFieldExpanded? []
                        (e/server
                         (e/offload
                          #(db/set-message-filter
                            db/conn
                            (:db/id msg)
                            (-> (update-in mfilter [:fields idx :expanded?] not)
                                (dissoc :ui/fields))))))
                      enabled?))))))
