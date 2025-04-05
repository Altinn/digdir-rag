(ns kudos-chunks.chunkr-import
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [typesense.search :refer [multi-search]]
            [typesense.import-collection :refer [upsert-collection]]
            [typesense.files-metadata :refer [upsert-file-chunkr-status get-file-metadata]]
            [taoensso.timbre :as log]
            [lib.converters :refer [sha1]]))

(def ^:private config
  {:api-key (System/getenv "CHUNKR_API_KEY")
   :base-url "https://api.chunkr.ai/api/v1"
  ;;  :base-url "https://api.chunkr.digdir.cloud"
   })



(defn- make-request
  "Make an authenticated request to Chunkr API"
  [method endpoint opts & {:keys [content-type] :or {content-type "application/json"}}]
  (let [base-request {:method method
                     :url (str (:base-url config) endpoint)
                     :headers {"Authorization" (str (:api-key config))}
                     :as :json}
        request (if (:multipart opts)
                 (merge base-request opts)  ; For multipart requests, don't set Content-Type
                 (merge base-request
                        {:headers {"Authorization" (str (:api-key config))
                                 "Content-Type" content-type}}
                        opts))]
    ;; (log/debug "Making request to Chunkr API:" (pr-str (dissoc request :headers)))
    ;; (log/trace "Complete request (including headers):" (pr-str request))
    (http/request request)))

(defn markdown-content [chunks]
  (let [content (->> chunks
                     (mapcat :segments)
                     (map #(get % :markdown "")) ;; Use empty string as default if :markdown is missing
                     (str/join "\n"))]
    content))

(defn chunkr-chunks->typesense-chunks
  "Convert Chunkr chunks to Typesense document format"
  [chunks doc-num]
  (map-indexed
   (fn [idx chunk]
     (let [content (markdown-content [chunk])]
       {:id (str doc-num "-" idx)
        :doc_num doc-num
        :chunk_id (str doc-num "-" idx)
        :chunk_index idx
        :content_markdown content
        :markdown_checksum (sha1 content)
        :url (str doc-num "-" idx)
        :url_without_anchor (str doc-num "-" idx)
        :type "content"
        :language "no"
        :updated_at (quot (System/currentTimeMillis) 1000)
        :page_num (-> chunk :segments first :page_number)
        :token_count 0
        :item_priority 1}))
   chunks))

(defn download-pdf-to-inbox
  "Download a PDF file from the given URL to the 'pdf_inbox' folder"
  [url]
  (let [filename (last (str/split url #"/"))
        target-dir "pdf_inbox"
        target-path (str target-dir "/" filename)]
    (io/make-parents target-path)
    (with-open [in (io/input-stream url)
                out (io/output-stream target-path)]
      (io/copy in out))
    target-path))

(defn upload-pdf-to-chunkr
  "Upload a PDF file from the 'pdf_inbox' folder to Chunkr.ai OCR API"
  [filename]
  (let [file-path (str "pdf_inbox/" filename)
        file (io/file file-path)]
    (if-not (.exists file)
      (throw (ex-info "PDF file does not exist" {:path file-path}))
      (let [file-size (.length file)
            _ (println "File size:" file-size "bytes")
            _ (println "Sending request to Chunkr API...")
            response (try
                       (make-request :post "/task" 
                                   {:multipart [{:name "file"
                                                 :content-type "application/pdf"
                                                 :content file
                                                 :filename filename
                                                 :ocr_strategy "Auto" 
                                                 :high_resolution true
                                                 :chunk_processing {:ignore_headers_and_footers true
                                                                       :target_length 1024}}
                                                
                                                
                                                #_{}]}
                                   {:content-type "multipart/form-data"})
                       (catch Exception e
                         (println "Error details:" (ex-data e))
                         (throw (ex-info "Failed to upload PDF to Chunkr"
                                         {:filename filename
                                          :file-size file-size
                                          :error (.getMessage e)}
                                         e))))]
        response))))

(defn save-chunks-to-jsonl [chunks filename]
  (with-open [writer (io/writer filename)]
    (doseq [chunk chunks]
      (.write writer (str (json/generate-string chunk) "\n")))))

(defn get-unchunked-files [files-collection-name]
  (let [result (multi-search
                {:collection files-collection-name
                 :q "*"
                 :query-by "doc_num"
                 :include-fields "doc_num,chunkr_status"
                 :page 1
                 :per_page 1})]
    (if (:success result)
      result
      (println :error (str result)))))

(defn chunks-exist-for-doc?
  "Check if chunks already exist for a document in the chunks collection"
  [chunks-collection doc-num]
  (let [result (multi-search
                {:collection chunks-collection
                 :q doc-num
                 :query-by "doc_num"
                 :include-fields "doc_num"
                 :page 1
                 :per_page 1})]
    (and (:success result)
         (pos? (count (:hits result))))))

;; (def chunks-to-import-filename "./typesense_chunks/chunks_to_import.jsonl")
(def docs-collection (System/getenv "DOCS_COLLECTION"))
(def chunks-collection (System/getenv "CHUNKS_COLLECTION"))
(def files-collection-name (System/getenv "FILES_COLLECTION"))

(def max-retries 1)

(defn handle-error [ctx error]
  (let [{:keys [current-state file-status]} ctx
        file-id (get-in ctx [:file-status :file_id])]
    (log/error error "Error in state" current-state)
    (when file-id
      (upsert-file-chunkr-status (:files-collection-name ctx) file-id (str "error-" (name current-state))))
    (assoc ctx
           :error error
           :error-state current-state)))

(def states
  {:init {:next :downloading-pdf
          :action (fn [ctx]
                    (try
                      (log/info "Starting PDF conversion for doc_num:" (:doc-num ctx))
                      (let [file-status (get-file-metadata (:files-collection-name ctx) (:doc-num ctx))]
                        (log/debug "Got file status:" file-status)
                        (assoc ctx :file-status file-status))
                      (catch Exception e
                        (handle-error ctx e))))}

   :downloading-pdf {:next :uploading-to-chunkr
                     :retryable true
                     :action (fn [ctx]
                               (try
                                 (let [{:keys [file_id kudos_url]} (:file-status ctx)]
                                   (log/info "Downloading PDF from URL:" kudos_url)
                                   (upsert-file-chunkr-status (:files-collection-name ctx) file_id "downloading-pdf")
                                   (let [filename (download-pdf-to-inbox kudos_url)]
                                     (log/debug "Downloaded PDF to:" filename)
                                     (assoc ctx :filename filename)))
                                 (catch Exception e
                                   (handle-error ctx e))))}

   :uploading-to-chunkr {:next :uploaded-to-chunkr
                         :retryable true
                         :action (fn [ctx]
                                   (try
                                     (let [{:keys [file_id]} (:file-status ctx)]
                                       (log/info "Uploading to Chunkr:" (:filename ctx))
                                       (upsert-file-chunkr-status (:files-collection-name ctx) file_id "uploading-to-chunkr")
                                       (let [upload-response (upload-pdf-to-chunkr (-> (:filename ctx) io/file .getName))]
                                         (upsert-file-chunkr-status (:files-collection-name ctx) file_id "uploaded-to-chunkr")
                                         (assoc ctx :task-id (get-in upload-response [:body :task_id]))))
                                     (catch Exception e
                                       (handle-error ctx e))))}

   :uploaded-to-chunkr {:next :chunkr-done
                        :retryable true
                        :action (fn [ctx]
                                  (try
                                    (log/info "Got Chunkr task ID:" (:task-id ctx))
                                    (loop [attempt 1]
                                      (let [task  (get-in (make-request :get (str "/task/" (:task-id ctx)) {}) [:body])
                                            file-id (get-in ctx [:file-status :file_id])
                                            status (get-in task [:status])]
                                        (log/debug "Status update " attempt " for doc_num " (:doc-num ctx) "file_id:" file-id "- status:" status)
                                        (cond
                                          (= status "Succeeded")
                                          (do
                                            (upsert-file-chunkr-status (:files-collection-name ctx)
                                                                       (get-in ctx [:file-status :file_id])
                                                                       "chunkr-done")
                                            (assoc ctx :status :completed :chunks (get-in task [:output :chunks])))
                                          
                                          (= status "Failed")
                                          (do
                                            (log/error "Chunkr task failed for file:" (get-in ctx [:file-status :file_id]))
                                            (upsert-file-chunkr-status (:files-collection-name ctx)
                                                                       (get-in ctx [:file-status :file_id])
                                                                       "error-chunkr-failed")
                                            (throw (ex-info "Chunkr task failed" 
                                                            {:task-id (:task-id ctx)
                                                             :file-id (get-in ctx [:file-status :file_id])
                                                             :status status})))
                                          
                                          :else
                                          (do
                                            (upsert-file-chunkr-status (:files-collection-name ctx)
                                                                       (get-in ctx [:file-status :file_id])
                                                                       "processing")
                                            (Thread/sleep 10000)
                                            (recur (inc attempt))))))
                                    (catch Exception e
                                      (handle-error ctx e))))}

   :chunkr-done {:next :uploading-chunks-to-typesense
                 :retryable true
                 :action (fn [ctx]
                           (try
                             (log/info "Converting chunks from Chunkr format to Typesense format...")
                             (let [
                                   chunks (get-in ctx [:chunks])]
                               (log/debug "Downloaded" (count chunks) "chunks")
                               (assoc ctx :typesense-chunks (chunkr-chunks->typesense-chunks chunks (:doc-num ctx))))
                             (catch Exception e
                               (handle-error ctx e))))}

   :uploading-chunks-to-typesense {:next :completed
                                   :retryable true
                                   :action (fn [ctx]
                                             (try
                                               (let [{:keys [file_id]} (:file-status ctx)]
                                                 (save-chunks-to-jsonl (:typesense-chunks ctx) (str "./typesense_chunks/" file_id ".jsonl"))  
                                                 (upsert-file-chunkr-status (:files-collection-name ctx) file_id "uploading-chunks-to-typesense")
                                                 (upsert-collection chunks-collection (str "./typesense_chunks/" file_id ".jsonl") 100 10)
                                                 (upsert-file-chunkr-status (:files-collection-name ctx) file_id "completed")
                                                 (log/info "Successfully completed processing for doc_num:" (:doc-num ctx))
                                                 ctx)
                                               (catch Exception e
                                                 (handle-error ctx e))))}

   :completed {:action (fn [ctx]
                         (log/info "Processing completed for doc_num:" (:doc-num ctx))
                         ctx)}})

(defn should-retry? [ctx attempt]
  (and (:error ctx)
       (get-in states [(:error-state ctx) :retryable])
       (< attempt max-retries)))

(defn run-state-machine
  "Runs the state machine with the given initial context"
  [initial-ctx]
  (let [file-status (get-file-metadata (:files-collection-name initial-ctx) (:doc-num initial-ctx))
        doc-num (:doc-num initial-ctx)]
    (cond
      ;; Check if file status is already marked as completed
      (= (:chunkr_status file-status) "completed")
      (do
        (log/info "Skipping already completed doc_num:" doc-num)
        (assoc initial-ctx :current-state :completed))
      
      ;; Check if chunks exist even though status isn't completed
      (chunks-exist-for-doc? chunks-collection doc-num)
      (do
        (log/info "Found existing chunks for doc_num:" doc-num "- updating status to completed")
        (upsert-file-chunkr-status (:files-collection-name initial-ctx) (:file_id file-status) "completed")
        (assoc initial-ctx :current-state :completed))
      :else
      (loop [ctx (assoc initial-ctx :current-state :init)
             attempt 1]
        (let [current-state (:current-state ctx)
              state-config (get states current-state)
              next-state (:next state-config)
              action-fn (:action state-config)
              new-ctx (action-fn ctx)]
          (cond
            ;; Error occurred and we should retry
            (should-retry? new-ctx attempt)
            (do
              (log/warn "Retrying state" (:error-state new-ctx) "attempt" attempt "of" max-retries)
              (Thread/sleep (* 1000 attempt)) ; Exponential backoff
              (recur (assoc new-ctx :current-state (:error-state new-ctx)
                            :error nil
                            :error-state nil)
                     (inc attempt)))

            ;; Error occurred and we shouldn't/can't retry
            (:error new-ctx)
            (do
              (log/error "Failed to process doc_num:" (:doc-num new-ctx)
                         "in state:" (:error-state new-ctx)
                         "error:" (:error new-ctx))
              new-ctx)

            ;; No error, continue to next state
            next-state
            (recur (assoc new-ctx :current-state next-state) 1)

            ;; No next state, we're done
            :else new-ctx))))))

(defn process-pdf-with-chunkr
  "Download PDF, upload to Chunkr, and poll for task completion using a state machine"
  [files-collection-name doc-num]
  (run-state-machine {:files-collection-name files-collection-name
                      :doc-num doc-num}))


(comment


  (def current-page 1)
  (def page-size 300)


  (def chunks-result
    (multi-search {:collection chunks-collection
                   :q "*"
                   :query-by "doc_num"
                   :include-fields "doc_num"
                   :facet-by "doc_num"
                   :page-size 30
                   :page 1}))


  (contains? (->>
              (multi-search {:collection "kudos_docs_2025-03-24"
                             :query-by "doc_num"
                             :include-fields "doc_num"
                             :page-size 200
                             :page 1
                             :q "*"})
              :hits
              (mapv :doc_num))
             
             "71354"
             )
  


  ;; import documents from KUDOS, converting them to Markdown with Chunkr.ai
  (doseq [i (range 1 14)]
    (doseq [doc-num (->>
                     (multi-search {:collection "kudos_docs_2025-03-24"
                                    :query-by "title"
                                    :include-fields "doc_num"
                                    :page-size 200
                                    :page 1
                                    :q "Stimulab"})
                     :hits
                     (mapv :doc_num))
            ]
      (let [_ (println "Processing document:" doc-num)]
        (process-pdf-with-chunkr files-collection-name doc-num))))
  
  (doseq [i (range 1 14)]
    (doseq [doc-num (->>
                     (multi-search {:collection "kudos_docs_2025-03-24"
                                    :query-by "doc_num"
                                    :include-fields "doc_num"
                                    :page-size 200
                                    :page i
                                    :q "*"})
                     :hits
                     (mapv :doc_num))]
      (let [_ (println "Processing document:" doc-num)]
        (process-pdf-with-chunkr files-collection-name doc-num))))


  (System/getenv "TYPESENSE_API_HOST")

  ;;
  )
