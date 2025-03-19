(ns chat-app.auth
  (:require [buddy.sign.jwt :as jwt]
            [tick.core :as t]
            [clojure.string :as str]
            ;; [babashka.http-client :as http]
            [clj-http.client :as http2]
            [clojure.data.json :as json]
            [nano-id.core :refer [nano-id]]
            [datahike.api :as d]
            [hiccup2.core :as h]
            [models.db :as db :refer [cfg delayed-connection dh-schema]]))

;; ### Key Metrics to Track for Security
;; 1. **Failed login attempts**: Monitor for brute force attempts.
;; 2. **IP addresses**: Track unusual IPs or geolocation changes.
;; 3. **Session duration**: Detect abnormally long sessions.
;; 4. **Successful logins**: Correlate with user behavior or unusual patterns.
;; 5. **Password reset requests**: Frequent requests can indicate compromise.
;; 6. **Account creation activity**: Watch for bots or bulk account creation.
;; 7. **Access to admin areas**: Monitor elevated permissions usage.

;; ### Common Administration Functions
;; - [ ] **Create new account**: Adding users with roles.
;; - [ ] **Reset password**: Manual or user-initiated.
;; - [ ] **Disable account**: Temporary or permanent deactivation.
;; - [ ] **Update roles/permissions**: Adjust user access.


;; DB transactions
(defn create-new-user [{:keys [email creator-id] :as data}]
  (let [conn @delayed-connection]
    (println "called create new user with: " email " and data " data)
    (if-not (d/entity @conn [:user/email email])
      (let [user-id (nano-id)]
        (println "creating new user with: " email " and data " data)
        (d/transact conn [{:user/id user-id
                           :user/email email
                           :user/created (str (t/now))
                           :user/created-by (or creator-id user-id)}]))
      {:error "User already exists"})))

;; DB queries
(defn accounts-created-by [db email]
  (d/q '[:find (pull ?created-account [*])
         :in $ ?creator-email
         :where
         [?creator :user/email ?creator-email]
         [?creator :user/id ?creator-id]
         [?created-account :user/created-by ?creator-id]
         [(not= ?created-account ?creator)]]
       db email))

(defn all-accounts [db]
  (d/q '[:find (pull ?e [*])
         :where
         [?e :user/id]
         [?e :user/email]
         [?e :user/created-by]]
       db))


(def secret "cd5e984eaf41-16f2b708-9af6-497f")

(defn admin-user? [email]
  (let [admins-env (or (System/getenv "ADMIN_USER_EMAILS") "")
        admins (set (map str/trim (str/split admins-env #" ")))]
    (println "ADMIN_USER_EMAILS: " admins)
    (contains? admins email)))

(defn domain-whitelist [email]
  (let [domains (set (map str/trim (str/split (or (System/getenv "ALLOWED_DOMAINS") "") #" ")))]
    (println "ALLOWED_DOMAINS: " domains)
    (contains? domains email)))

(defn approved-domain? [email]
  (let [[_local-part domain] (str/split email #"@")]
    (boolean (domain-whitelist (str "@" domain)))))

(def confirmation-codes (atom {}))

(defn generate-confirmation-code [email]
  (let [confirmation-code (format "%06d" (rand-int 1000000))]
    (swap! confirmation-codes assoc email {:code confirmation-code
                                           :expiry (t/>> (t/now) (t/new-duration 10 :minutes))})
    confirmation-code))

(defn valid-code? [email confirmation-code]
  (let [{:keys [code expiry]} (get @confirmation-codes email)]
    (and (t/< (t/now) expiry) (= code confirmation-code))))

(defn create-expiry [{:keys [multiplier timespan]}]
  (-> (t/now)
      (t/>> (t/new-duration multiplier timespan))
      (t/inst)
      (.getTime)))

(defn create-token [user-id expiry]
  (jwt/sign {:user-id user-id
             :expiry (/ expiry 1000)} secret))

(defn verify-token [token]
  (try
    (let [{:keys [user-id expiry]} (jwt/unsign token secret)
          current-time (/ (.getTime (t/inst (t/now))) 1000)]
      (if (< current-time expiry)
        {:valid true
         :user-id user-id
         :expiry (* expiry 1000)}
        {:valid false
         :reason "Token expired"}))
    (catch Exception e
      {:valid false :reason (str "Invalid token: " (.getMessage e))})))

;; Confirmation code

(def postmark-url "https://api.postmarkapp.com/email")
(def email-server-token (read-string (System/getenv "POSTMARK_API_KEY")))


(defn send-confirmation-code [from to code]
  (http2/post postmark-url
              {:headers {"Accept" "application/json"
                         "Content-Type" "application/json"
                         "X-Postmark-Server-Token" email-server-token}
               :body (json/write-str
                      {:From from
                       :To to
                       :Subject (str "digdir.cloud - pålogging")
                       :TextBody (str "For å gjennomføre påloggingen, skriv følgende kode i nettleseren: "
                                      code "\n\n"
                                      "Trenger du assistanse? Vennligst ta kontakt med oss på hjelp@digdir.cloud.")
                       :HtmlBody (str (h/html
                                       [:html
                                        [:body
                                         [:p "For å gjennomføre påloggingen, skriv følgende kode i nettleseren: "]
                                         [:p {:style "font-size: 24px; font-weight: bold; color: #2D3748;"} code]
                                         [:p 
                                          [:span "Trenger du assistanse? Vennligst ta kontakt med oss: "]
                                          [:a {:href "mailto:hjelp@digdir.cloud"} "hjelp@digdir.cloud"]
                                          ]]]))
                       :MessageStream "outbound"})}))



;; Email Validation

(defn starts-or-ends-with? [s s-check]
  (or (str/starts-with? s s-check)
      (str/ends-with? s s-check)))

(defn consecutive-dots? [local]
  (re-find #"\.\." local))

(defn split-local-domain [email]
  (str/split email #"@"))

(defn alphanumeric? [s]
  (boolean (re-find #"^[a-zA-Z0-9.-]+$" s)))

(defn numeric-tld? [tld]
  (not (boolean (re-find #"[a-zA-Z]" tld))))

(defn validate-email? [email]
  (let [email-parts (split-local-domain email)
        errors (atom [])
        check (fn [pred? error-msg] (when pred? (swap! errors conj error-msg)))]

    (check (not (= 2 (count email-parts))) "Missing or too many @")
    (check (consecutive-dots? email) "Consecutive .")

    (when (= 2 (count email-parts))
      (check (some #(or
                     (starts-or-ends-with? %  "-")
                     (starts-or-ends-with? %  "."))
                   email-parts) "- or . found at the beginning or end of parts of email")
      (check (some #(not (alphanumeric? %)) email-parts) "parts of the email are not alphanumeric")
      (let [domain-parts (str/split (second email-parts) #"\.")
            not-valid-domain? (or
                               (not (<= 2 (count domain-parts)))
                               (some str/blank? domain-parts))
            tld (when-not not-valid-domain? (last domain-parts))]
        (check not-valid-domain? "domain is missing a part")
        (when-not not-valid-domain?
          (check (numeric-tld? tld) "numeric tld"))))

    @errors))


  


