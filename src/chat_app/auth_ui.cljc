(ns chat-app.auth-ui
  (:require [chat-app.rhizome :as rhizome]
            [chat-app.webauthn :as webauthn]
            [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
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
            (dom/p (dom/text "HTTP Only cookie expiry: " jwt-expiry))))))

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
