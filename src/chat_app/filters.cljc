(ns chat-app.filters
  (:require [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
            [hyperfiddle.electric-ui4 :as ui]
            #?(:clj [models.db :as db])))

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
(e/defn FilterField [{:as x :keys [expanded? options field]} ToggleOption ToggleFieldExpanded? enabled?]
  (e/client
   (dom/div ;; NB: this extra div is required, otherwise the card
            ;;     will stretch to the bottom of the parent
            ;;     regardless of content height.
    (dom/div
     (dom/props {:class (str "mb-4 space-y-2 p-4 rounded-md shadow-md border " (if enabled? "bg-white" "bg-gray-300"))})
     (dom/div
      (dom/div
       (dom/props {:class "font-medium text-gray-800 mb-2"})
       (dom/text (str "Velg " (typesense-field->ui-name field))))

      (ui/button ToggleFieldExpanded?
                 (dom/props {:class "px-4 py-2 bg-blue-500 text-white rounded-md hover:bg-blue-600 focus:outline-none"})
                 (dom/text (str (count (filter :selected? options)) " valgt")))
      (when expanded?
        (dom/div
         (dom/props {:class "flex flex-col items-start space-y-2 mt-1 max-h-48 overflow-y-scroll"})
         (e/for [{:keys [selected? count value]} options]
           (ui/button (e/fn [] (ToggleOption. value))
                      (dom/span
                       (dom/props {:class "grid grid-cols-[16px_1fr_auto] items-center gap-2 w-full text-left p-2 hover:bg-gray-100 rounded-md"})
                       (dom/img (dom/props {:class "w-[16px] h-[16px] flex-shrink-0"
                                            :src (if selected?
                                                   "icons/checked_checkbox.svg"
                                                   "icons/unchecked_checkbox.svg")}))
                       (dom/span
                        (dom/props {:class "text-gray-800 whitespace-wrap"})
                        (dom/text value))
                       (dom/span
                        (dom/props {:class "text-gray-600 text-right"})
                        (dom/text (str "(" count ")")))))))))))))

(e/defn FilterMsg [msg enabled?]
  (e/client
   (let [mfilter (:message.filter/value msg)]
     (e/for [[idx field] (map vector (range) (mfilter :ui/fields))]
       (FilterField. field
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
                     enabled?)))))
