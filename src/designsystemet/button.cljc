(ns designsystemet.button
  (:require
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]))

(e/defn Button [{:keys [color variant children size on-click class]}]
  (e/client
   (dom/button
    (dom/on "click" (e/fn [_] (on-click.)))
    (dom/props {:class (str "ds-button " (or class "")) :data-color (or color "accent") :data-variant (or variant "primary") :data-size (or size "md")})
    (children.))))
