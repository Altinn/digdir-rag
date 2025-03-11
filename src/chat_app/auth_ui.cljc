(ns chat-app.auth-ui
  (:require [chat-app.rhizome :as rhizome]
            [chat-app.webauthn :as webauthn]
            [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
            [hyperfiddle.electric-ui4 :as ui]
            #?(:clj [chat-app.auth :as auth])
            #?(:clj [models.db :refer [conn auth-conn fetch-user-id] :as db])))

;; Client-side utility functions
#?(:cljs (defn get-from-local-storage [key]
           (.getItem js/localStorage key)))

(defonce !prepared-opts (atom nil))

;; Authentication Components
(e/defn HandleRegistration []
  (e/client
   (println "this is a test")
   (let [create-opts (e/server
                      (let [current-user (:user-id (auth/verify-token (get-in e/http-request [:cookies "auth-token" :value])))
                            session-id (get-in e/http-request [:headers "sec-websocket-key"])]
                        (webauthn/create-public-key-options
                         session-id
                         {:name current-user
                          :display-name current-user
                          :rp-name "company-name"
                          :rp-id "localhost"})))
         cb #(reset! webauthn/!created-key %)
         prepared-opts (webauthn/prepare-for-creation create-opts)]
     (reset! !prepared-opts create-opts)
     (webauthn/create-credential prepared-opts cb)
     nil)))

#_(e/defn SessionTimer []
  (e/client
   (let [jwt-expiry (e/server (:expiry (auth/verify-token (get-in e/http-request [:cookies "auth-token" :value]))))
         positive-duration? (fn [d] (t/< (t/new-duration 0 :seconds) d))
         !t-between (atom (t/between (t/now) (t/instant jwt-expiry))) t-between (e/watch !t-between)
         !interval-running (atom nil) interval-running (e/watch !interval-running)
         _ (when-not interval-running
             (println "Setting interval")
             (reset! !interval-running
                     (js/setInterval #(reset! !t-between
                                              (t/between (t/now) (t/instant jwt-expiry))) 1000)))
         time-dist [(t/hours t-between)
                    (t/minutes t-between)
                    (t/seconds t-between)]
         [hours minutes seconds] time-dist
         message (cond
                   (<= 1 hours) (str hours " hour and " (mod minutes 60) " minutes")
                   (<= 5 minutes) (str minutes " minutes")
                   (< 0 minutes) (str minutes " minutes " (mod seconds 60) " seconds")
                   :else (str seconds " seconds"))]
     
     ;; When session expires cancel the websocket session
     (when-not (positive-duration? t-between)
       (set! (.-href js/window.location) "/login"))
     
     ;; Handle when a new passkey is generated 
     (dom/p (dom/text "Created key: " (str (e/watch webauthn/!created-key))))
     (dom/p (dom/text "Session challenges: " (e/server (e/watch webauthn/!session-challenges))))
     (when-let [created-key (e/watch webauthn/!created-key)]
       (let [data (webauthn/serialize-public-key-credential created-key)]
         (e/server
          (let [conn @delayed-connection
                session-id (get-in e/http-request [:headers "sec-websocket-key"])
                success-cb (fn [username passkey]
                             (let [passkey-str (pr-str (-> passkey
                                                           (update-in [:public-key -2] webauthn/byte-array-to-base64)
                                                           (update-in [:public-key -3] webauthn/byte-array-to-base64)))]
                               (println "This is the username: " username)
                               (println "this is the auth-data: " (get-in passkey [:public-key -2]))
                               (println "this is the auth-data: " (webauthn/byte-array-to-base64 (get-in passkey [:public-key -2])))
                               (println "round trip: " (webauthn/base64-to-byte-array (webauthn/byte-array-to-base64 (get-in passkey [:public-key -2]))))
                               (println "cose map: " (update-in passkey [:public-key -2] webauthn/byte-array-to-base64))
                               (d/transact conn [{:db/id [:user/email username]
                                                  :user/key-created (str (t/now))
                                                  :user/key passkey-str}])))]
            (println "This is the session-id: " session-id)
            (e/offload #(webauthn/handle-new-user-creds session-id data success-cb)))
          nil)))
     
     ;; View for session time and creating a passkey
     (dom/div (dom/props {:class "p-4 border rounded flex flex-col gap-4 bg-red-100"})
              (dom/div
               (dom/p (dom/text "Session:"))
               (dom/p (dom/props {:class "font-bold"})
                      (dom/text message)))
              (dom/div
               (dom/p (dom/props {:class "text-xs mb-1 font-light"})
                      (dom/text "Complete registration"))
               (dom/button
                (dom/props {:class "px-4 py-2 rounded bg-black text-white hover:bg-slate-800"})
                (dom/on "click" (e/fn [e] (HandleRegistration.)))
                (dom/text "Create passkey")))))))

(e/defn AuthAdminDashboard []
  (e/client
   (dom/div
    (dom/props {:class "max-h-full overflow-x-hidden"})
    (dom/div
     (dom/props {:class "p-8 flex flex-col gap-4"})
     (let [token (e/client (get-from-local-storage "auth-token"))]
       (dom/div
        (dom/p (dom/text "Local storage JWT: " token))
        (dom/p (dom/text "Local storage JWT unsigned: " (e/server (auth/verify-token token))))
        (let [http-cookie-jwt (e/server (get-in e/http-request [:cookies "auth-token" :value]))]
          (dom/p
           (dom/text
            "created-by: "
            (e/server
             (let [user-email (:user-id (auth/verify-token
                                         (get-in e/http-request [:cookies "auth-token" :value])))]
               (e/offload #(fetch-user-id user-email))))))
          (dom/p (dom/text "HTTP Only cookie: " http-cookie-jwt))
          (let [cookie (e/server (auth/verify-token http-cookie-jwt))
                jwt-expiry (:expiry cookie)]
            (dom/p (dom/text "HTTP Only cookie: " cookie))
            (dom/p (dom/text "HTTP Only cookie user: " (:user-id cookie)))
            (dom/p (dom/text "HTTP Only cookie expiry: " jwt-expiry))
            #_(dom/p (dom/text "HTTP Only cookie expiry intant: " (t/instant jwt-expiry)))

            #_(let [!t-between (atom (t/between (t/now) (t/instant jwt-expiry)))
                    t-between (e/watch !t-between)
                    !interval-running (atom nil) interval-running (e/watch !interval-running)
                    positive-duration? (fn [d] (t/< (t/new-duration 0 :seconds) d))
                    _ (when-not interval-running
                        (println "Setting interval")
                        (reset! !interval-running
                                (js/setInterval #(reset! !t-between
                                                         (t/between (t/now) (t/instant jwt-expiry))) 1000)))
                    _ (println "the running interval" interval-running)
                    time-dist [(t/days t-between)
                               (t/hours t-between)
                               (t/minutes t-between)
                               (t/seconds t-between)]
                    [days hours minutes seconds] time-dist
                    message (cond
                              (< 1 days) (str days " days")
                              (< 24 hours) (str days " day")
                              (and (< hours 24) (< 2 hours)) (str hours " hours")
                              (< 1 hours) (str hours " hour and " minutes " minutes")
                              :else (str minutes " minutes " (mod seconds 60) "seconds"))]
                (dom/p (dom/text "Positive duration: " (positive-duration? t-between)))
                (when-not (positive-duration? t-between)
                  (set! (.-href js/window.location) "/auth"))
                (dom/p (dom/text t-between))
                (dom/p (dom/text message)))))))

     (dom/button
      (dom/props {:class "px-4 py-2 bg-black text-white rounded"})
      (dom/on "click" (e/fn [e] (set! (.-href js/window.location) "/logout")))
      (dom/text "Sign out"))
                          ;;
     (let [!email (atom nil) email (e/watch !email)]
       (dom/div
        (dom/props {:class "flex gap-4"})
        (dom/input
         (dom/props {:class "px-4 py-2 border rounded"
                     :placeholder "Enter email"
                     :value email})
         (dom/on "keyup" (e/fn [e]
                           (if-some [v (not-empty (.. e -target -value))]
                             (reset! !email v)
                             (reset! !email nil)))))
        (dom/button (dom/props {:class "px-4 py-2 rounded bg-black hover:bg-slate-800 text-white"})
                    (dom/on "click" (e/fn [_]
                                      (when-let [email @!email]
                                        (e/server
                                         (auth/send-confirmation-code "kunnskap@digdir.cloud" (auth/generate-confirmation-code email))
                                         nil))))
                    (dom/text "Send Code"))
        (dom/button
         (dom/props {:class "px-4 py-2 rounded bg-black hover:bg-slate-800 text-white"})
         (dom/on "click" (e/fn [_]
                           (when-let [email @!email]
                             (e/server
                              (let [current-user (:user-id (auth/verify-token (get-in e/http-request [:cookies "auth-token" :value])))
                                    admin-id (e/offload #(fetch-user-id conn current-user))]
                                (e/offload #(auth/create-new-user {:email email
                                                                   :creator-id admin-id}))
                                nil)
                              (auth/generate-confirmation-code email)
                              nil))))
         (dom/text "Generate Code"))))

     (dom/p (dom/text "Auth db"))
     (dom/pre (dom/text (rhizome/pretty-print (e/server (e/offload #(auth/all-accounts auth-conn))))))


     (dom/p (dom/text "Active Confirmation Codes"))
     (dom/ul (dom/props {:class "flex flex-col gap-2"})
             (e/for-by identity [user-code (e/server (map (fn [[key value]] [key (update value :expiry str)])
                                                          (e/watch auth/confirmation-codes)))]

                       (dom/li (dom/props {:class "flex"})
                               (dom/p (dom/text user-code))
                               (dom/button
                                (dom/props {:class "px-2 py-1 rounded bg-black text-white"})
                                (dom/on "click" (e/fn [_]
                                                  (e/server
                                                   (swap! auth/confirmation-codes dissoc (first user-code))
                                                   nil)))
                                (dom/text "Remove")))))))))
