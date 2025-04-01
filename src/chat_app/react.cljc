(ns chat-app.react
  (:require [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
            #?(:cljs [designsystemet.wrapper])))

;; Utility function to create a React-compatible handler that calls an Electric function
#?(:cljs
   (defn electric-fn [f & args]
     (fn [_event]
       (js/console.log "Calling Electric function from React")
       (try
         (apply f args)
         (catch js/Error err
           (js/console.error "Error calling Electric function:" err))))))

;; Special function to create a delayed Electric function call for onClick handlers
;; This prevents immediate execution while still allowing proper function reference
#?(:cljs
   (defn electric-click [f & args]
     (let [wrapped-fn (apply f args)]
       (js/console.log "Creating delayed Electric function call")
       (fn [_event]
         (js/console.log "Executing delayed Electric function call")
         wrapped-fn))))

;; Helper function for ClojureScript only
#?(:cljs
   (defn prepare-react-props [tree]
     (let [props (get tree :props {})
           style (get tree :style)
           class-name (get tree :class)
           enhanced-props (cond-> props
                            style (assoc :style (clj->js style))
                            class-name (assoc :className class-name))]
       (-> tree
           (assoc :props enhanced-props)
           (dissoc :style :class)))))

(e/defn react-component-tree [tree]
  (e/client
   (let [element-id (str "digdir-" (random-uuid))]
     (dom/div
      (dom/props {:id element-id})

      ;; Handle differently in Clojure vs ClojureScript
      #?(:cljs
         (js/setTimeout
          (fn []
            (when (.-renderReactComponentTree js/window)
              (let [prepared-tree (prepare-react-props tree)]
                ((.-renderReactComponentTree js/window) (clj->js prepared-tree) element-id))))
          100)
         :clj nil)))))
