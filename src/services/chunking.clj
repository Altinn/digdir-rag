(ns services.chunking
  "Hierarchical document chunking service.
   Converts document segments into semantically meaningful chunks
   while preserving relationships between elements like captions and images."
  (:require
   [clojure.core.async :refer [chan go <! >!]]
   [clojure.tools.logging :as log]))

;; Define hierarchy levels for different segment types
(defn get-hierarchy-level
  "Returns the hierarchy level for a given segment type."
  [segment-type]
  (case segment-type
    :title 3
    :section-header 2
    1))

(defn- finalize-and-start-new-chunk
  "Helper function to finalize the current chunk and start a new one."
  [chunks segments]
  (when (seq segments)
    (conj chunks {:segments (vec segments)})))

(defn hierarchical-chunking
  "Processes segments into chunks based on hierarchical structure and semantic relationships.
   
   Key features:
   - Maintains hierarchy (titles, sections)
   - Keeps related elements together (captions with pictures/tables)
   - Respects target chunk length
   - Can ignore headers and footers
   
   Parameters:
   - segments: Collection of segment maps
   - configuration: Map containing chunking configuration
   
   Returns:
   - Vector of chunk maps"
  [segments configuration]
  (try
    (let [target-length (get-in configuration [:chunk-processing :target-length])
          ignore-headers-and-footers (get-in configuration [:chunk-processing :ignore-headers-and-footers])
          
          ;; Calculate word counts in parallel using core.async
          segments-with-counts (let [c (chan)
                                    segments-count (count segments)]
                                (doseq [segment segments]
                                  (go
                                    (try
                                      (>! c (assoc segment :word-count 
                                                  (or (:word-count segment)
                                                      (try
                                                        (-> segment 
                                                            (assoc :configuration configuration)
                                                            (.count-embed-words))
                                                        (catch Exception e
                                                          (log/error "Error counting words:" (.getMessage e))
                                                          0)))))
                                      (catch Exception e
                                        (log/error "Error in word count processing:" (.getMessage e))
                                        (>! c segment)))))
                                (loop [result []
                                       remaining segments-count]
                                  (if (pos? remaining)
                                    (recur (conj result (<! c)) (dec remaining))
                                    result)))
          
          ;; Process segments into chunks
          result (loop [remaining-segments segments-with-counts
                        chunks []
                        current-segments []
                        current-word-count 0
                        prev-hierarchy-level 1
                        segment-paired false]
                   
                   (if-let [segment (first remaining-segments)]
                     (let [segment-word-count (:word-count segment)
                           current-hierarchy-level (get-hierarchy-level (:segment-type segment))]
                       
                       (case (:segment-type segment)
                         ;; Handle titles and section headers
                         (:title :section-header)
                         (if (> current-hierarchy-level prev-hierarchy-level)
                           ;; Higher hierarchy level - start new chunk
                           (let [new-chunks (finalize-and-start-new-chunk chunks current-segments)]
                             (recur (rest remaining-segments)
                                    new-chunks
                                    [segment]
                                    segment-word-count
                                    current-hierarchy-level
                                    false))
                           ;; Continue with current hierarchy
                           (recur (rest remaining-segments)
                                  chunks
                                  (conj current-segments segment)
                                  (+ current-word-count segment-word-count)
                                  current-hierarchy-level
                                  false))
                         
                         ;; Handle page headers and footers
                         (:page-header :page-footer)
                         (if ignore-headers-and-footers
                           ;; Skip headers/footers if configured to ignore
                           (recur (rest remaining-segments)
                                  chunks
                                  current-segments
                                  current-word-count
                                  prev-hierarchy-level
                                  segment-paired)
                           ;; Otherwise, put each header/footer in its own chunk
                           (let [new-chunks (finalize-and-start-new-chunk chunks current-segments)
                                 newer-chunks (finalize-and-start-new-chunk 
                                               (or new-chunks chunks) 
                                               [segment])]
                             (recur (rest remaining-segments)
                                    newer-chunks
                                    []
                                    0
                                    prev-hierarchy-level
                                    false)))
                         
                         ;; Handle all other segment types
                         (let [next-segment (second remaining-segments)
                               next-segment-type (when next-segment (:segment-type next-segment))
                               next-segment-word-count (when next-segment (:word-count next-segment))
                               
                               ;; Check for picture/table + caption pairing
                               picture-table-with-caption? (and (not segment-paired)
                                                               (contains? #{:picture :table} (:segment-type segment))
                                                               (= :caption next-segment-type)
                                                               (> (+ current-word-count segment-word-count next-segment-word-count)
                                                                  target-length))
                               
                               ;; Check for caption + picture/table pairing
                               caption-with-picture-table? (and (not segment-paired)
                                                               (= :caption (:segment-type segment))
                                                               (contains? #{:picture :table} next-segment-type)
                                                               (> (+ current-word-count segment-word-count next-segment-word-count)
                                                                  target-length))
                               
                               ;; Determine if we need special pairing behavior
                               special-pairing? (or picture-table-with-caption? caption-with-picture-table?)]
                           
                           (if special-pairing?
                             ;; Handle special pairing - start new chunk to keep pair together
                             (let [new-chunks (finalize-and-start-new-chunk chunks current-segments)]
                               (recur (rest remaining-segments)
                                      new-chunks
                                      [segment]
                                      segment-word-count
                                      prev-hierarchy-level
                                      true))
                             
                             ;; Default chunking behavior
                             (if (> (+ current-word-count segment-word-count) target-length)
                               ;; Start new chunk if target length exceeded
                               (let [new-chunks (finalize-and-start-new-chunk chunks current-segments)]
                                 (recur (rest remaining-segments)
                                        new-chunks
                                        [segment]
                                        segment-word-count
                                        prev-hierarchy-level
                                        false))
                               ;; Add to current chunk
                               (recur (rest remaining-segments)
                                      chunks
                                      (conj current-segments segment)
                                      (+ current-word-count segment-word-count)
                                      prev-hierarchy-level
                                      false))))))
                     
                     ;; No more segments - finalize last chunk
                     (finalize-and-start-new-chunk chunks current-segments)))]
      
      ;; Return the final chunks collection
      (vec result))
    
    (catch Exception e
      (log/error "Error in hierarchical chunking:" (.getMessage e))
      (throw (ex-info "Hierarchical chunking failed" 
                     {:error (.getMessage e)
                      :cause e})))))

;; Test helpers - these would typically be in a test namespace
(comment
  (defn create-segment
    "Helper function to create test segments"
    [content segment-type]
    {:bbox {:x 0.0 :y 0.0 :width 0.0 :height 0.0}
     :confidence nil
     :content content
     :html ""
     :image nil
     :llm nil
     :markdown ""
     :ocr nil
     :page-height 0.0
     :page-width 0.0
     :page-number 0
     :segment-id ""
     :segment-type segment-type
     :word-count (count (clojure.string/split content #"\s+"))})
  
  (defn create-test-config
    "Helper function to create test configuration"
    [target-length ignore-headers-and-footers]
    {:chunk-processing {:ignore-headers-and-footers ignore-headers-and-footers
                        :target-length target-length
                        :tokenizer {:type :cl100k-base}}
     :expires-in nil
     :high-resolution false
     :input-file-url nil
     :json-schema nil
     :model nil
     :ocr-strategy :all
     :segment-processing {:table {:embed-sources [:html :markdown]}}
     :segmentation-strategy :layout-analysis
     :target-chunk-length nil
     :error-handling nil})
  
  ;; Example test
  (let [segments [(create-segment "Caption 1" :caption)
                  (create-segment "Picture 1" :picture)
                  (create-segment "Caption 2" :caption)
                  (create-segment "Picture 2" :picture)]
        chunks (hierarchical-chunking segments (create-test-config 100 true))]
    
    ;; Verify that captions stay with their pictures
    (assert (= 4 (count (get-in chunks [0 :segments]))))
    (assert (= :caption (get-in chunks [0 :segments 0 :segment-type])))
    (assert (= :picture (get-in chunks [0 :segments 1 :segment-type])))
    (assert (= :caption (get-in chunks [0 :segments 2 :segment-type])))
    (assert (= :picture (get-in chunks [0 :segments 3 :segment-type])))))
