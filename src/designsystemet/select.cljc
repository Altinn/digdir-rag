(ns designsystemet.select
  (:require
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]))

;; Field Component
(e/defn Field [{:keys [children]}]
  (e/client
   (dom/div
    (dom/props {:class "ds-field"})
    (children.))))

;; Label Component
(e/defn Label [{:keys [weight for children]}]
  (e/client
   (dom/label
    (dom/props {:class ["ds-label" (when weight (str "data-weight-" weight))]
                :for for})
    (children.))))

;; Select Component
(e/defn Select [{:keys [width default-value options on-change]}]
  (e/client
   (let [!selected (atom (or default-value "")) ; Reactive state for selected value
         selected (e/watch !selected)]
     (dom/select
      (dom/props {:class ["ds-input" (when width (str "data-width-" width))]
                  :aria-invalid false
                  :id (str (gensym ":select-")) ; Unique ID
                  :value selected})
      (e/for [{:keys [value label disabled]} options]
        (dom/option
         (dom/props {:value value
                     :disabled (boolean disabled)
                     :selected (boolean (= value default-value))})
         (dom/text value)))))))