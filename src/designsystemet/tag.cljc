(ns designsystemet.tag
  (:require
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]))

(e/defn Tag
  [{:keys [color class-name children size]}]
  (e/client
   (dom/span
    (dom/props {:class (str "ds-tag " (or class-name "")) :data-color color :data-size size})
    (dom/text children))))