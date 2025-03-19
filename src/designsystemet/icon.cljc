(ns designsystemet.icon
  (:require
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-dom2 :as dom]
   [hyperfiddle.electric-svg :as svg]
   #?(:clj [clojure.data.xml :as xml])
   #?(:clj [clojure.java.io :as io])
   #?(:clj [clojure.string :as str])))

(e/defn Icon [props]
  (e/client
   (let [p props
         src (:src p)
         size (:size p)
         fill (:fill p) ; Optional override
         stroke (:stroke p) ; Optional override
         svg-width (if (number? size) (str size "px") (or size "24px"))
         svg-data (e/server
                   (let [svg-str (slurp (io/resource src))
                         parsed (xml/parse-str svg-str)
                         attrs (:attrs parsed)
                         vb (or (:viewBox attrs) "0 0 24 24")
                         [x y w h] (map parse-double (str/split vb #"\s+"))
                         paths (mapv :attrs (:content parsed))] ; Keep all path attrs
                     {:attrs attrs
                      :viewBox vb
                      :width-ratio w
                      :height-ratio h
                      :paths paths}))
         aspect-ratio (/ (:height-ratio svg-data) (:width-ratio svg-data))
         svg-height (str (int (* size aspect-ratio)) "px")]
     (svg/svg
      (dom/props (merge (:attrs svg-data) ; Preserve original SVG attrs
                        {:width svg-width
                         :height svg-height
                         :viewBox (:viewBox svg-data)
                         :class "dynamic-icon"
                         :role "img"}
                        (when fill {:fill fill}) ; Override if provided
                        (when stroke {:stroke stroke})))
      (e/for [path (:paths svg-data)]
        (svg/path
         (dom/props (merge path ; Preserve original path attrs
                           (when fill {:fill fill}) ; Override if provided
                           (when stroke {:stroke stroke})))))))))