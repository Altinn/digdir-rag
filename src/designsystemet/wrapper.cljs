(ns designsystemet.wrapper
  (:require
   ["react" :as react]
   ["react-dom/client" :as react-dom]
   [clojure.string :as string]))

;; Function to safely handle event handlers, including Electric functions
(defn safe-event-handler [handler-fn]
  (if (nil? handler-fn)
    nil
    (fn [event]
      (js/console.log "Handling event in wrapper " handler-fn)
      (try
        ;; First try calling without arguments (for Electric functions)
        (try
          (handler-fn)
          (catch js/Error _
            (js/console.log "Trying with event parameter instead")
            ;; If that fails, try with the event argument
            (handler-fn event)))
        (catch js/Error err
          (js/console.error "Error in event handler:" err))))))

;; Process props to handle event handlers safely
(defn process-props [props]
  (let [processed-props (js-obj)]
    (doseq [[k v] props]
      (let [key-str (name k)
            ;; Special handling for event handlers (props starting with "on")
            processed-value (if (and (string/starts-with? key-str "on")
                                     (fn? v))
                              ;; For event handlers, wrap them in our safe handler
                              (safe-event-handler v)
                              ;; For other props, use as-is
                              v)]
        (aset processed-props key-str processed-value)))
    processed-props))

(defn create-element-from-spec [spec]
  (cond
    (string? spec) spec
    ;; Handle both ClojureScript maps and JavaScript objects
    (or (map? spec) (object? spec))
    (let [spec-map (if (map? spec) spec (js->clj spec :keywordize-keys true))
          {:keys [component props children]} spec-map
          ;; Initialize DigdirCustom if it doesn't exist
          _ (when-not (exists? js/window.DigdirCustom)
              (set! js/window.DigdirCustom #js {}))

          ;; Look for the component in both the digdir library and our custom components
          ;; Log the component name we're looking for to help with debugging
          _ (js/console.log "Looking for component:" component)
          _ (when (exists? js/window.digdir)
              (js/console.log "Available components in window.digdir:" (js-keys js/window.digdir)))

          Component (or (and (exists? js/window.DigdirCustom) (aget js/window.DigdirCustom component))
                        (and (exists? js/window.digdir) (aget js/window.digdir component)))

          ;; Process props to handle Electric functions
          processed-props (process-props props)]
      (if-not Component
        (do (js/console.error "Component not found:" component)
            nil)
        (do
          (js/console.log "Found component:" component)
          (react/createElement Component
                               processed-props
                               (into-array (map create-element-from-spec (or children [])))))))
    :else
    (do (js/console.error "Invalid spec:" spec)
        nil)))

(set! (.-renderReactComponentTree js/window)
      (fn [tree-spec element-id]
        (let [el (.getElementById js/document element-id)
              root (.createRoot react-dom el)
              react-element (create-element-from-spec tree-spec)]
          (.render root react-element))))
