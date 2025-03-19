(ns designsystemet.link
  (:require
   [designsystemet.icon :as icon]
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]))

;; Link component for navigation
(e/defn Link [{:keys [href target rel children class as-child on-click]}]
  (e/client
   (let [props {:class (str "ds-link " (or class ""))
                :href href
                :target (or target "_self")
                :rel (or rel (when (= target "_blank") "noopener noreferrer"))}]
     (if (boolean as-child)
       ;; When as-child is true, render as a div with link styling
       (dom/div
        (dom/props props)
        (when on-click (dom/on "click" (e/fn [_] (on-click.))))
        (if (string? children)
          (dom/text children)
          (children.)))

       ;; Otherwise render as an anchor element
       (dom/a
        (dom/props props)
        (when on-click (dom/on "click" (e/fn [_] (on-click.))))
        (if (string? children)
          (dom/text children)
          (children.)))))))

;; External link component that automatically sets target="_blank" and proper rel attributes
(e/defn ExternalLink [{:keys [href children class on-click]}]
  (e/client
   (Link. {:href href
           :target "_blank"
           :rel "noopener noreferrer"
           :class class
           :on-click on-click
           :children children})))

;; IconLink component that includes an icon before the link text
(e/defn IconLink [{:keys [href target rel icon children class on-click external]}]
  (e/client
   (Link. {:href href
           :target (if external "_blank" (or target "_self"))
           :rel (if external "noopener noreferrer" (or rel (when (= target "_blank") "noopener noreferrer")))
           :class class
           :on-click on-click
           :children (e/fn []
                       (icon/Icon. {:src icon :size 24})
                       (dom/text children))})))
