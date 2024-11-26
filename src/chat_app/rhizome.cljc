(ns chat-app.rhizome
  "The `rhizome` namespace contains context-aware functions that dynamically interact with the environment. 
   Like an interconnected root system, these functions adapt and respond to platform, user, and system contexts, 
   providing a living interface between the system and its surroundings."
  #?(:cljs (:require [goog.userAgent :as ua]
                     [goog.labs.userAgent.platform :as platform])))

#?(:cljs (defn mobile-device? []
           (or (or ua/IPHONE
                 ua/PLATFORM_KNOWN_ ua/ASSUME_IPHONE
                 (platform/isIphone))
             (or ua/ANDROID
               ua/PLATFORM_KNOWN_ ua/ASSUME_ANDROID
               (platform/isAndroid)))))

#?(:cljs (defn copy-to-clipboard [text]
           (if (and (exists? js/navigator.clipboard)
                 (exists? js/navigator.clipboard.writeText))
             (let [promise (.writeText (.-clipboard js/navigator) text)]
               (if (exists? (.-then promise))
                 (.then promise
                   (fn [] (js/console.log "Text copied to clipboard!"))
                   (fn [err] (js/console.error "Failed to copy text to clipboard:" err)))
                 (js/console.error "writeText did not return a Promise")))
             (js/console.error "Clipboard API not supported in this browser"))))

#?(:cljs (defn speak-text [text]
           (if (exists? js/window.speechSynthesis)
             (let [utterance (js/SpeechSynthesisUtterance. text)]
               (.speak js/window.speechSynthesis utterance))
             (js/console.error "Speech Synthesis API is not supported in this browser"))))

#?(:cljs (defn pretty-print [data]
           (with-out-str (cljs.pprint/pprint data))))
