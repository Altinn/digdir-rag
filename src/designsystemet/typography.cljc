(ns designsystemet.typography
  (:require
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]))

;; Heading component for typography
(e/defn Heading [{:keys [level size as-child children class]}]
  (e/client
   (let [level (or level 2)
         size (or size "md")
         props {:class (str "ds-heading " (or class ""))
                :data-size size}]

     (if as-child
       ;; When as-child is true, render as a div with heading styling
       (dom/div (dom/props props) (dom/text children))
       ;; Otherwise render as the appropriate heading level
       (case level
         1 (dom/h1 (dom/props props) (dom/text children))
         2 (dom/h2 (dom/props props) (dom/text children))
         3 (dom/h3 (dom/props props) (dom/text children))
         4 (dom/h4 (dom/props props) (dom/text children))
         5 (dom/h5 (dom/props props) (dom/text children))
         6 (dom/h6 (dom/props props) (dom/text children))
         (dom/h2 (dom/props props) (dom/text children)))))))

;; Paragraph component for body text
(e/defn Paragraph [{:keys [size spacing children class]}]
  (e/client
   (dom/p
    (dom/props {:class (str "ds-paragraph " (or class ""))
                :data-size (or size "md")
                :data-spacing (or spacing "md")})
    (dom/text children))))

;; Label component for form labels and similar uses
(e/defn Label [{:keys [size for required children class]}]
  (e/client
   (dom/label
    (dom/props {:class (str "ds-label " (or class ""))
                :data-size (or size "md")
                :for for
                :data-required (if required "true" "false")})
    (children.))))
