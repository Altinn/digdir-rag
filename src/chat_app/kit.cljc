(ns chat-app.kit
  "The `kit` namespace offers a collection of essential, task-oriented utilities. 
   These modular tools are self-contained and designed for broad reuse, enabling clear, reliable operations 
   that focus on simplicity and functionality."
  (:require [clojure.string :as str]
            #?(:clj [nextjournal.markdown :as md])
            #?(:clj [hiccup2.core :as h])
            #?(:clj [nextjournal.markdown.transform :as md.transform])))

(defn lowercase-includes? [s1 s2]
  (and (string? s1) (string? s2)
    (str/includes? (str/lower-case s1) (str/lower-case s2))))

#?(:clj (defn parse-text [s]
          (->> (md/parse s)
            md.transform/->hiccup
            h/html
            str)))