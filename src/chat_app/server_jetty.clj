(ns chat-app.server-jetty
  "Electric integrated into a sample ring + jetty app."
  (:require 
   [chat-app.auth :as auth] 
   [models.db :as db :refer [delayed-connection]]
   [hiccup2.core :as h]
   [ruuter.core :as ruuter]
   [datahike.core :as d]
   ;; Electric
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [contrib.assert :refer [check]]
   [hyperfiddle.electric-ring-adapter :as electric-ring]
   [ring.adapter.jetty :as ring]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.cookies :as cookies]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.util.response :as res]
   [chat-app.webauthn :as webauthn])
  (:import
   (org.eclipse.jetty.server.handler.gzip GzipHandler)
   (org.eclipse.jetty.websocket.server.config JettyWebSocketServletContainerInitializer JettyWebSocketServletContainerInitializer$Configurator)))


;; TODO: add the actual expiry we want on the cookie
(defn set-http-only-cookie
  "Helper function to set an HTTP-only cookie with the JWT token."
  [response jwt-token]
  (assoc-in response [:cookies "auth-token"]
            {:value jwt-token
             :path "/"
             :http-only true
             :same-site :strict
             :max-age 200000}))

(defn remove-http-only-cookie [response]
  (assoc-in response [:cookies "auth-token"]
            {:value ""
             :path "/"
             :http-only true
             :same-site :strict
             :max-age 0})) ;; Set max-age to 0 to remove the cookie


(defn confirm-email-page [email]
  (res/response
   (str (h/html
         [:html 
          [:head
           [:meta {:charset "utf-8"}]
           [:title "Sjekk din innboks"]
           [:link {:rel "stylesheet"
                   :href "/styles.css"}]
           [:link {:rel "icon"
                   :type "image/svg+xml"
                   :href "digdir_icon.svg"}]]
          [:body {:class "flex justify-center items-center bg-slate-100"}
           [:div {:class "flex flex-col gap-4"}
            [:h1 {:class "text-4xl font-bold text-center"} "Sjekk innboksen din"]
            [:p "Vi har sendt din påloggingskode til: " [:b email] "."]
            [:p  "Koden er gyldig i et begrenset tidsperiode."]
            [:form {:action "/auth/confirm-email"
                    :method "post"
                    :class "flex flex-col gap-4"}
             [:input {:type "hidden" :name "email" :value email}]

             [:input {:type "text" :name "confirmation-code" :placeholder "6-sifret kode"
                      :required true :maxlength "6" :pattern "\\d{6}" :title "Skriv din 6-sifret kode"
                      :autofocus true}]
             [:button {:type "submit"
                       :class "px-4 py-2 bg-black hover:bg-slate-800 text-white rounded"}
              "Logg på"]]
            #_[:p "Codes: " (str @auth/confirmation-codes)]]]]))))

(defn auth-model [{:keys [title action]}]
  (str (h/html
        [:html
         [:head
          [:title title]
          [:link {:rel "stylesheet"
                  :href "/styles.css"}]
          [:link {:rel "icon"
                  :type "image/svg+xml"
                  :href "digdir_icon.svg"}]]
         [:body {:class "flex justify-center items-center bg-slate-100"}
          [:div {:class "p-8 shadow bg-white flex flex-col gap-4 rounded-lg w-96"}
           [:h1 {:class "text-2xl font-bold text-center"} title]
           [:form {:action "/auth"
                   :method "post"
                   :class "flex flex-col gap-4"}
            
            [:input {:type "email" :id "email" :name "email" :placeholder "E-post adresse" :required true :autofocus true}]
            [:button {:type "submit"
                      :class "px-4 py-2 bg-black hover:bg-slate-800 text-white rounded"}
             "Fortsett"]]
           (if (= action "/auth")
             [:p "Har du en konto allerede? " [:a {:href "/login"
                                                   :class "text-green-500"} "Sign in"]]
             [:p "Trenger du en brukerkonto? " [:a {:href "/auth"
                                                    :class "text-green-500"} "Sign up"]])]]])))


(defn email-signup []
  (res/response
   (auth-model {:title "Opprett en konto"
                :action "/auth"})))

(defn login []
  (res/response
   (auth-model {:title "Velkommen tilbake"
                :action "/login"})))

(def routes 
  [{:path "/auth"
    :method :get
    :response (email-signup)}

   {:path "/auth"
    :method :post
    :response (fn [ring-req]
                (let [conn @delayed-connection 
                      email (get-in ring-req [:params "email"])]
                  (if email
                    (if (auth/approved-domain? email)
                      (if-let [passkey (webauthn/get-user-key  @conn email)]
                        (do
                          (println  "passkey: " passkey)
                          (let [cose-map (edn/read-string passkey)]
                            (println "this is the cose map"
                                     (-> cose-map
                                         (update-in [:public-key -2] webauthn/base64-to-byte-array)
                                         (update-in [:public-key -3] webauthn/base64-to-byte-array)))
                            (when-let [credentials (:credential-id cose-map)]
                              ;; TODO: use something more secure to isolating the session request
                              (let [session-id email
                                    key-request (webauthn/map-to-base64
                                                 (webauthn/create-public-key-request-options session-id email
                                                                                             {:rp-id "localhost"
                                                                                              :allowed-credentials [credentials]}))]
                                (println "This is request options as: " key-request)
                                (res/redirect (str "/auth/passkey?email=" email "&request=" key-request))))))
                        (do
                          (println "this is the email passed to the backend: " email)
                          (auth/create-new-user {:email email})

                          ;; either: just generate a code
                          ;; (auth/generate-confirmation-code email)
                          
                          ;; or: generate a code and sent it by email
                          ;; TODO: use a static sender email, rather than a matching email (required during test mode)
                          (auth/send-confirmation-code "kunnskap@digdir.cloud" email (auth/generate-confirmation-code email))

                          (res/redirect (str "/auth/confirm-email?email=" email))))
                      (res/redirect "/not-approved"))
                    (res/status (res/response "<html><body><h1>Error</h1><p>Email address is required.</p></body></html>") 400))))}

   {:path "/not-approved"
    :method :get
    :response (res/response (str (h/html
                                  [:html
                                   [:body
                                    [:h1 "Påloggingsfeil"]
                                    [:p "Beklager, den oppgitte epost adresse har ikke tilgang."]]])))}

   {:path "/login"
    :method :get
    :response (login)}
   
   {:path "/logout"
    :method :get
    :response (let [response (res/redirect "/")]
                (remove-http-only-cookie response))}
   
    {:path "/auth/passkey"
     :method :get
     :response (res/response (str (h/html
                                   [:html 
                                    [:body
                                     [:h1 "Passkey"] 
                                     [:button {:id "get-passkey"
                                               :class "px-4 py-2 bg-black hover:bg-slate-800 text-white rounded"}
                                      "Login with passkey"]
                                     #_[:script {:src "/js/webauthn.js"}]]])))}

   {:path  "/auth/confirm-email"
    :method :get
    :response (fn [ring-req]
                (let [email (get-in ring-req [:query-params "email"])]
                  (if email
                    (confirm-email-page email)
                    (res/status (res/response "<html><body><h1>Error</h1><p>Email address is missing.</p></body></html>") 400))))}

   {:path  "/auth/confirm-email"
    :method :post
    :response (fn [ring-req]
                (let [{:strs [email confirmation-code]} (:params ring-req)]
                  (if (and email confirmation-code)
                    (if (auth/valid-code? email confirmation-code)
                      (let [jwt-token (auth/create-token email (auth/create-expiry {:multiplier 48
                                                                                    :timespan :hours}))
                            response (res/redirect "/")]
                        (swap! auth/confirmation-codes dissoc email)
                        (set-http-only-cookie response jwt-token))
                      (res/response (str (h/html
                                          [:html
                                           [:head
                                            [:meta {:charset "utf-8"}]] 
                                           [:body
                                            [:h1 "Error"]
                                            [:p "Koden er ugyldig eller utgått."]]]))))
                    (res/status (res/response "<html><body><h1>Error</h1><p>Mangler epost eller bekreftelseskoden.</p></body></html>") 400))))}])



(defn wrap-auth
  "A basic path-based routing middleware"
  [next-handler]
  (fn [ring-req] 
    (let [auth-token (get-in ring-req [:cookies "auth-token" :value])
          valid-token? (:valid (auth/verify-token auth-token))
          uri (:uri ring-req)
          auth-route? (contains? (set (map :path routes)) uri)
          static-url? (re-find #"\.(css|js|png|jpg|jpeg|gif|ico|svg)$" uri)]
      (cond
        (= uri "/logout") (let [response (res/redirect "/")]
                            (remove-http-only-cookie response))
        valid-token? (next-handler ring-req)
        static-url? (next-handler ring-req)
        auth-route? (ruuter/route routes ring-req)
        (not valid-token?) (res/redirect "/login")
        :else (res/not-found "Not found")))))



;;; Electric integration

(defn electric-websocket-middleware
  "Open a websocket and boot an Electric server program defined by `entrypoint`.
  Takes:
  - a ring handler `next-handler` to call if the request is not a websocket upgrade (e.g. the next middleware in the chain),
  - a `config` map eventually containing {:hyperfiddle.electric/user-version <version>} to ensure client and server share the same version,
    - see `hyperfiddle.electric-ring-adapter/wrap-reject-stale-client`
  - an Electric `entrypoint`: a function (fn [ring-request] (e/boot-server {} my-ns/My-e-defn ring-request))
  "
  [next-handler config entrypoint]
  ;; Applied bottom-up
  (-> (electric-ring/wrap-electric-websocket next-handler entrypoint) ; 5. connect electric client
    ; 4. this is where you would add authentication middleware (after cookie parsing, before Electric starts)
    (cookies/wrap-cookies) ; 3. makes cookies available to Electric app
    (electric-ring/wrap-reject-stale-client config) ; 2. reject stale electric client
    (wrap-params))) ; 1. parse query params

(defn get-modules [manifest-path]
  (when-let [manifest (io/resource manifest-path)]
    (let [manifest-folder (when-let [folder-name (second (rseq (str/split manifest-path #"\/")))]
                            (str "/" folder-name "/"))]
      (->> (slurp manifest)
        (edn/read-string)
        (reduce (fn [r module] (assoc r (keyword "hyperfiddle.client.module" (name (:name module)))
                                 (str manifest-folder (:output-name module)))) {})))))

(defn template
  "In string template `<div>$:foo/bar$</div>`, replace all instances of $key$
with target specified by map `m`. Target values are coerced to string with `str`.
  E.g. (template \"<div>$:foo$</div>\" {:foo 1}) => \"<div>1</div>\" - 1 is coerced to string."
  [t m] (reduce-kv (fn [acc k v] (str/replace acc (str "$" k "$") (str v))) t m))

;;; Template and serve index.html

(defn wrap-index-page
  "Server the `index.html` file with injected javascript modules from `manifest.edn`.
`manifest.edn` is generated by the client build and contains javascript modules
information."
  [next-handler config]
  (fn [ring-req]
    (if-let [response (res/resource-response (str (check string? (:resources-path config)) "/index.html"))]
      (if-let [bag (merge config (get-modules (check string? (:manifest-path config))))]
        (-> (res/response (template (slurp (:body response)) bag)) ; TODO cache in prod mode
          (res/content-type "text/html") ; ensure `index.html` is not cached
          (res/header "Cache-Control" "no-store")
          (res/header "Last-Modified" (get-in response [:headers "Last-Modified"])))
        (-> (res/not-found (pr-str ::missing-shadow-build-manifest)) ; can't inject js modules
          (res/content-type "text/plain")))
      ;; index.html file not found on classpath
      (next-handler ring-req))))

(defn not-found-handler [_ring-request]
  (-> (res/not-found "Not found")
    (res/content-type "text/plain")))

(defn http-middleware [config]
  ;; these compose as functions, so are applied bottom up
  (-> not-found-handler
    (wrap-index-page config) ; 3. otherwise fallback to default page file
    (wrap-resource (:resources-path config)) ; 2. serve static file from classpath
    (wrap-content-type) ; 1. detect content (e.g. for index.html)
    (wrap-auth)
    ))

(defn middleware [config entrypoint]
  (-> (http-middleware config)  ; 2. otherwise, serve regular http content
    (electric-websocket-middleware config entrypoint))) ; 1. intercept websocket upgrades and maybe start Electric

(defn- add-gzip-handler!
  "Makes Jetty server compress responses. Optional but recommended."
  [server]
  (.setHandler server
    (doto (GzipHandler.)
      #_(.setIncludedMimeTypes (into-array ["text/css" "text/plain" "text/javascript" "application/javascript" "application/json" "image/svg+xml"])) ; only compress these
      (.setMinGzipSize 1024)
      (.setHandler (.getHandler server)))))

(defn- configure-websocket!
  "Tune Jetty Websocket config for Electric compat." [server]
  (JettyWebSocketServletContainerInitializer/configure
    (.getHandler server)
    (reify JettyWebSocketServletContainerInitializer$Configurator
      (accept [_this _servletContext wsContainer]
        (.setIdleTimeout wsContainer (java.time.Duration/ofSeconds 60))
        (.setMaxBinaryMessageSize wsContainer (* 100 1024 1024)) ; 100M - temporary
        (.setMaxTextMessageSize wsContainer (* 100 1024 1024))   ; 100M - temporary
        ))))

(defn start-server! [entrypoint
                     {:keys [port host]
                      :or   {port 8080, host "0.0.0.0"}
                      :as   config}]
  (let [server     (ring/run-jetty (middleware config entrypoint)
                     (merge {:port         port
                             :join?        false
                             :configurator (fn [server]
                                             (configure-websocket! server)
                                             (add-gzip-handler! server))}
                       config))]
    (log/info "👉" (str "http://" host ":" (-> server (.getConnectors) first (.getPort))))
    server))


(comment
  (require '[datahike.core :as d])

  (d/pull auth/conn)

  (user-key "test@digdir.no")
  
  ;;
  )