(ns chat-app.webauthn
  #?(:clj (:import java.util.Base64
                   [java.nio ByteBuffer]
                   [java.security Signature KeyPairGenerator KeyFactory]
                   [java.nio.charset StandardCharsets]
                   [java.security.spec ECPoint ECPublicKeySpec ECGenParameterSpec]
                   [java.security.interfaces ECPublicKey]))
  (:require #?(:clj [clojure.data.json :as json])
            #?(:clj [clj-cbor.core :as cbor])
            #?(:clj [pandect.algo.sha256 :as sha])
            #?(:clj [datahike.core :as d])
            #?(:cljs [goog.dom :as gdom])
            [nano-id.core :refer [nano-id]]))

(def challenge-settings {:timeout 60000
                         :prune-interval 10000})

#?(:clj (defonce !create-opts (atom nil)))
#?(:clj (defonce !session-challenges (atom {})))
#?(:clj (defonce !registered-users (atom {})))

#?(:cljs (defonce !created-key (atom nil)))
;; Security focused libraries like Duo issues alerts with repeatadly failed challenges
;; {:user-session {:created-at "timestamp of when challenge was issued"
;;                 :challenge "the challenge id"}}

#?(:cljs (defn str->Uint8Array [s]
           (js/Uint8Array. (clj->js (map #(.charCodeAt % 0) (seq s))))))

#?(:cljs (defn Uint8Array->str [arr]
           (apply str (map #(js/String.fromCharCode %) arr))))

#?(:cljs (defn str->base64 [s]
           (js/btoa s)))

#?(:cljs (defn base64->str [base64]
           (js/atob base64)))

#?(:cljs (defn prepare-for-creation [auth-options]
           (clj->js (-> auth-options
                        (update-in [:challenge] str->Uint8Array)
                        (update-in [:user :id] str->Uint8Array)))))

#?(:cljs (defn prepare-for-login [auth-options]
           (println "This is the challenge before prepped" (:challenge auth-options))
           (clj->js (-> auth-options
                        (update-in [:allowCredentials 0 :id] #(js/Uint8Array. %))
                        (update-in [:challenge] str->Uint8Array)))))

#?(:cljs (defn array-buffer->json [^js/ArrayBuffer buffer]
           (let [decoder (js/TextDecoder. "utf-8")
                 json-str (.decode decoder (js/Uint8Array. buffer))]
             (js/JSON.parse json-str))))

#?(:cljs (defn array-buffer->base64 [^js/ArrayBuffer buffer]
           (let [uint8-array (js/Uint8Array. buffer)]
             (js/btoa (apply str (map #(js/String.fromCharCode %) uint8-array))))))

#?(:cljs (defn serialize-public-key-credential [^js pkc]
           (let [raw-id (array-buffer->base64 (.-rawId pkc))
                 client-data-json (array-buffer->base64 (.-clientDataJSON (.-response pkc)))
                 attestation-object (array-buffer->base64 (.-attestationObject (.-response pkc)))]
             {:id (.-id pkc)
              :rawId raw-id
              :type (.-type pkc)
              :authenticatorAttachment (.-authenticatorAttachment pkc)
              :response {:clientDataJSON client-data-json
                         :attestationObject attestation-object}})))


#?(:cljs (defn serialize-login-key-credential [^js pkc]
           (let [auth-assert-resp (.-response pkc)
                 client-data-json (array-buffer->base64 (.-clientDataJSON auth-assert-resp))
                 signature (array-buffer->base64 (.-signature auth-assert-resp))
                 user-handle (array-buffer->base64 (.-userHandle auth-assert-resp))
                 authenticator-data (array-buffer->base64 (.-authenticatorData auth-assert-resp))
                 raw-id (array-buffer->base64 (.-rawId pkc))]
             {:id (.-id pkc)
              :rawId raw-id
              :type (.-type pkc)
              :response {:clientDataJSON client-data-json
                         :signature signature
                         :user-handle user-handle
                         :authenticator-data authenticator-data}})))

#?(:cljs (defn create-credential [opts cb]
           (-> (.create js/navigator.credentials (clj->js {:publicKey opts}))
               (.then (fn [credential]
                        (cb credential)))
               (.catch (fn [error]
                         (js/console.error "Error creating credential:" error))))))

#?(:cljs (defn get-credential [opts cb]
           (-> (.get js/navigator.credentials (clj->js {:publicKey opts}))
               (.then (fn [credential]
                        (cb credential)))
               (.catch (fn [error]
                         (js/console.error "Error creating credential:" error))))))

;; Backend

#?(:clj (defn prune-expired [state-atom timeout]
          (let [now (System/currentTimeMillis)]
            (swap! state-atom
                   #(into {} (filter (fn [[_ {:keys [created-at]}]]
                                       (>= created-at (- now timeout))) %))))))

#?(:clj (defn start-pruning-loop []
          (println "Starting prune loop")
          (future
            (while true
              (Thread/sleep (:prune-interval challenge-settings))
              ;; (println "pruning challenges")
              (prune-expired !session-challenges (:timeout challenge-settings))))))

#?(:clj (start-pruning-loop))

#?(:clj (defn bytes-to-uint16 [byte-seq]
          (let [buffer (java.nio.ByteBuffer/wrap (byte-array byte-seq))]
            (.getShort buffer 0))))

#?(:clj (defn parse-auth-data [auth-data]
          (let [id-len-bytes (subvec (vec auth-data) 53 55)
                credential-id-length (bytes-to-uint16 id-len-bytes)
                credential-id (subvec (vec auth-data) 55 (+ 55 credential-id-length))
                public-key-bytes (subvec (vec auth-data) (+ 55 credential-id-length))
                public-key-object (cbor/decode (byte-array public-key-bytes))]
            {:credential-id credential-id
             :public-key public-key-object})))

#?(:clj (defn parse-credential [data]
          (let [base64->bytes #(.decode (Base64/getDecoder) %)
                base64->str #(String. (base64->bytes %) "UTF-8")
                decode-attestation-object #(clojure.walk/keywordize-keys (cbor/decode (base64->bytes %)))
                parsed-data (-> data
                                (update-in [:response :clientDataJSON] #(json/read-str (base64->str %) :key-fn keyword))
                                (update-in [:response :clientDataJSON :challenge] base64->str)
                                (update-in [:response :attestationObject] decode-attestation-object)
                                (update-in [:response :attestationObject :authData] parse-auth-data))]
            parsed-data)))

#?(:clj (defn parse-credential-login [data]
          (let [base64->bytes #(.decode (Base64/getDecoder) %)
                bytes->str #(String. % "UTF-8")
                base64->str #(String. (base64->bytes %) "UTF-8")
                resp-bytes (update-vals (:response data) base64->bytes)
                client-data-hash (sha/sha256-bytes (:clientDataJSON resp-bytes))
                authenticator-data (:authenticator-data resp-bytes)
                concat-bytes (byte-array (concat authenticator-data client-data-hash))
                parsed-data (-> data
                                (assoc :response resp-bytes)
                                (update-in [:response :clientDataJSON] #(json/read-str (bytes->str %) :key-fn keyword))
                                (update-in [:response :clientDataJSON :challenge] base64->str)
                                (update-in [:response :user-handle] bytes->str)
                                (assoc :concat-bytes concat-bytes))]
            parsed-data)))

#?(:clj (defn verify-challenge [session-id credential-data]
          (let [session-challenge (get-in @!session-challenges [session-id :challenge])
                resp-challenge (get-in credential-data [:response :clientDataJSON :challenge])]
            (cond
              (= resp-challenge session-challenge) {:status :success}
              (nil? session-challenge) {:status :error
                                        :message "No challenge found in session"
                                        :session-id session-id}
              (not= resp-challenge session-challenge) {:status :error
                                                       :message "Challenge is different to session challenge"
                                                       :expected session-challenge
                                                       :received resp-challenge}))))

#?(:clj
   (defn create-public-key-options
     "Generates a WebAuthn `publicKeyCredentialCreationOptions` map used for registering a new credential.
      The map includes a challenge, relying party (RP) information, user information, and cryptographic options.
      Optionally supports user verification, authenticator type selection, attestation level, and timeout settings.

      Arguments:
      - `session-id`: Unique identifier for the user's session.
      - `options`: Map containing:
        - `:name` (string): The user's account name.
        - `:display-name` (string): A human-readable display name for the user.
        - `:rp-name` (string): Name of the relying party (RP, e.g., your website).
        - `:rp-id` (string): Relying party identifier (typically your domain).
        - `:authenticatorSelection` (map): Map specifying authenticator options such as:
           - `:authenticatorAttachment` (string): Choose between 'platform' or 'cross-platform'.
           - `:userVerification` (string): Level of user verification required ('required', 'preferred', or 'discouraged').
        - `:attestation` (optional string): Level of attestation ('none', 'indirect', or 'direct'). Default is 'none'.
        - `:timeout` (optional number): Time (in ms) for the user to respond to the credential creation request."

     [session-id {:keys [name display-name rp-name rp-id authenticatorSelection attestation timeout]}]
     (let [challenge (nano-id)
           create-opts {:challenge challenge
                        :rp {:name rp-name
                             :id rp-id}
                        :user {:id (nano-id)
                               :name name
                               :displayName display-name}
                        :pubKeyCredParams [{:alg -7 :type "public-key"} ;Most common and considered most efficient and secure
                                           {:alg -257 :type "public-key"}] ;RS256 is necessary for compatibility with Microsoft Windows platform 
                        :authenticatorSelection authenticatorSelection
                        :timeout (or timeout (:timeout challenge-settings))
                        ;; :attestation (or attestation "none") ; or directnot confirmed to work
                        }]
       ;; Store the challenge for session validation
       (reset! !create-opts create-opts)
       (swap! !session-challenges assoc session-id {:created-at (System/currentTimeMillis)
                                                    :challenge challenge
                                                    :type :register
                                                    :name name})
       create-opts)))

;; Extra helper. People could do this themselves
#?(:clj (defn handle-new-user-creds
          "Takes a callback to handle the auth data when challenge is verified.
           When the challenge is verified calls the callback and returns {:success true}
           When the challenge fails returns a map with the {:status :error} and helpful information, see verify-challenge."
          [session-id creds cb]
          (let [parsed-credential-obj (parse-credential creds)
                auth-data (get-in parsed-credential-obj [:response :attestationObject :authData])
                username (get-in @!session-challenges [session-id :name])
                result (verify-challenge session-id parsed-credential-obj)]
            (case (:status result)
              :success (do
                         (swap! !session-challenges dissoc session-id)
                         (cb username auth-data)
                         {:success true})
              :error result))))

#?(:clj (defn create-public-key-request-options [session-id name {:keys [challenge rp-id allowed-credentials]}]
          (let [challenge (nano-id)]
            (swap! !session-challenges assoc session-id {:created-at (System/currentTimeMillis)
                                                         :challenge challenge
                                                         :type :login
                                                         :name name})
            {:challenge challenge
          ;;  :rpId rp-id  ;; Same relying party ID used during registration
             :allowCredentials (mapv (fn [cred-id]
                                       {:type "public-key"
                                        :id cred-id})
                                     allowed-credentials) ;; List of previously registered credential IDs
             :timeout 60000  ;; Optional timeout setting (e.g., 60 seconds)
          ;;  :userVerification "preferred"
})))  ;; Optional user verification requirement

#?(:clj (defn verify-signature-bytes [public-key message-bytes signature-bytes]
          (let [signature-instance (Signature/getInstance "SHA256withECDSA")]
            (.initVerify signature-instance public-key)
            (.update signature-instance message-bytes)
            (.verify signature-instance signature-bytes))))

#?(:clj (defn cose->ec-public-key [cose-map]
          (let [[x y] [(BigInteger. 1 (byte-array (get cose-map -2)))
                       (BigInteger. 1 (byte-array (get cose-map -3)))]
                point (ECPoint. x y)  ;; Create ECPoint (x, y)

                  ;; Initialize the key pair generator for EC curve P-256 (secp256r1)
                kpg (KeyPairGenerator/getInstance "EC")
                _ (.initialize kpg (ECGenParameterSpec. "secp256r1"))
                temp-key-pair (.generateKeyPair kpg)
                temp-public-key (.getPublic temp-key-pair)
                param-spec (.getParams ^ECPublicKey temp-public-key)
                key-factory (KeyFactory/getInstance "EC")
                key-spec (ECPublicKeySpec. point param-spec)]

              ;; Generate key
            (.generatePublic key-factory key-spec))))

#?(:clj (defn verify-credentials [session-id cose-map credentials]
          (let [creds (parse-credential-login credentials)
                result (verify-challenge session-id creds)]
            (case (:status result)
              :success (do
                         (swap! !session-challenges dissoc session-id)
                         (let [public-key (cose->ec-public-key cose-map)
                               concat-bytes (:concat-bytes creds)
                               sig (get-in creds [:response :signature])]
                           (verify-signature-bytes public-key concat-bytes sig)))
              :error result))))

;; New additions

#?(:clj (defn byte-array-to-base64 [byte-array]
          (let [encoder (Base64/getEncoder)]
            (.encodeToString encoder byte-array))))

#?(:clj (defn base64-to-byte-array [base64-str]
          (let [decoder (Base64/getDecoder)]
            (.decode decoder base64-str))))

#?(:clj (defn get-user-key [db email]
          (d/q '[:find ?key .
                 :in $ ?email
                 :where
                 [?e :user/email ?email]
                 [?e :user/key ?key]]
               db email)))

#?(:clj (defn map-to-base64 [m]
          (let [json-str (json/write-str m)
                json-bytes (.getBytes json-str "UTF-8")]
            (byte-array-to-base64 json-bytes))))

#?(:cljs (defn get-query-params []
           (let [url-params (js/URLSearchParams. (.-search js/window.location))]
             (into {} (for [k (js->clj (.keys url-params))]
                        [k (.get url-params k)])))))

#?(:cljs (defn handle-passkey-click []
           (let [req-key (get (get-query-params) "request")
                 parsed-req-key  (js->clj (js/JSON.parse (base64->str req-key))
                                          :keywordize-keys true)
                 prepared-request (prepare-for-login parsed-req-key)]
             (js/console.log "Passkey button clicked!")
             (println req-key)
             (println (base64->str req-key))
             (println "hey: " (keys parsed-req-key))
             (println "parsed req key: " parsed-req-key)
             (println "prepped: " prepared-request)
             (get-credential prepared-request #(println "Hello key:" %))
             #_(prepare-for-login (base64->str req-key)))
  ;; Add your passkey-related logic here
           ))

#?(:cljs (defn init []
           (let [button (gdom/getElement "get-passkey")]
             (when button
               (.addEventListener button "click" handle-passkey-click)))))

;; Ensure the function runs when the script is loaded
#?(:cljs (init))

#?(:cljs (println "This is the webauthn code loaded"))


  
