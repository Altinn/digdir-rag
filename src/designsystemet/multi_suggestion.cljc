(ns designsystemet.multi-suggestion
  (:require
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]
   #?(:cljs [goog.object])
   #?(:cljs [goog.functions])))

(e/defn MultiSuggestion [{:keys [suggestions selected on-select on-remove placeholder width id]}]
  (e/client
   (let [hidden! (atom true)  ;; Each instance has its own hidden! state
         is-hidden? (e/watch hidden!)
         !selected (atom selected)
         selected (e/watch !selected)]
     (dom/div
      (dom/props {:class "min-h-[500px] mt-4"})
      (dom/element "u-tags"
                   (dom/props {:class "ds-multi-suggestion" :role "group" :aria-label "" :id id})
                   (let [filter-div dom/node]  ;; Reactive reference to this div
                     (when filter-div
                       (let [handler (fn [event]
                                       (let [target (.-target event)]
                                         (when-not (.contains filter-div target)  ;; Click outside this div
                                           (reset! hidden! true))))]  ;; Close only this instance
                         (.addEventListener js/document "click" handler))))
                   (e/for [s selected]
                     (dom/element "data"
                                  (dom/props {:class "ds-chip"
                                              :data-removable "true"
                                              :value s
                                              :aria-label (str s ", Press to remove, 1 of " (count selected))
                                              :role "button"
                                              :tabindex "0"})
                                  (dom/on "click" (e/fn [e]
                                                    (swap! !selected #(remove (fn [x] (= x s)) %))
                                                    (when on-remove (on-remove s))))
                                  (dom/text s)))
                   (dom/input
                    (dom/props {:class "ds-input"
                                :list (str ":r" width)
                                :placeholder placeholder
                                :type "text"
                                :aria-label (str ", Navigate left to find " (count selected) " selected")
                                :popovertarget (str ":r" width)
                                :aria-autocomplete "list"
                                :aria-controls (str ":r" width)
                                :aria-expanded (str (not is-hidden?))
                                :autocomplete "off"
                                :role "combobox"})
                    (dom/on "click" (e/fn [e]
                                      (swap! hidden! not))))  ;; Toggle this instance’s dropdown
                   (dom/button
                    (dom/props {:class "ds-button"
                                :data-icon "true"
                                :data-variant "tertiary"
                                :type "reset"
                                :aria-label "Tøm"})
                    (dom/on "click" (e/fn [e]
                                      (reset! !selected [])
                                      (when on-remove (e/for [s selected] (on-remove s))))))
                   (dom/element "u-datalist"
                                (dom/props {:data-sr-singular "%d forslag"
                                            :data-sr-plural "%d forslag"
                                            :id (str ":r" width)
                                            :role "listbox"
                                            :data-multiselectable "true"
                                            :aria-labelledby ""
                                            :hidden is-hidden?})  ;; Controlled by this instance’s hidden!
                                (e/for [s suggestions]
                                  (dom/element "u-option"
                                               (dom/props {:value s
                                                           :role "option"
                                                           :tabindex "-1"
                                                           :aria-disabled "false"
                                                           :aria-selected (if (some #{s} selected) "true" "false")
                                                           :selected (if (some #{s} selected) "true" "false")})
                                               (dom/on "click" (e/fn [e]
                                                                 (if (some #{s} selected)
                                                                   (swap! !selected #(remove (fn [x] (= x s)) %))
                                                                   (swap! !selected conj s))))
                                               (dom/text s)))))))))