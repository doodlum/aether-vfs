(ns aether.director.source.steam.cm
  "The Steam client protocol, through JavaSteam (aether-vfs's aether-steam
  cm.rs, auth.rs, login.rs, ticket.rs, licenses.rs): a connection to a
  Steam CM, logged on anonymously or with a saved refresh token; depot keys,
  manifest request codes, CDN servers and tokens, branch manifests (PICS),
  encrypted app tickets, ownership; and the QR and password logins.

  Requests run one at a time per session, each under a deadline; a timeout
  or a dropped connection reconnects and retries once, a refusal never.
  The refresh token is the only credential kept (by the caller, see
  aether.director.source.steam); it never appears in errors."
  (:require [aether.director.source.net :as net]
            [clojure.string :as str])
  (:import (in.dragonbra.javasteam.enums EOSType EResult)
           (in.dragonbra.javasteam.steam.authentication AuthPollResult AuthSession AuthSessionDetails IAuthenticator
                                                       IChallengeUrlChanged QrAuthSession SteamAuthentication)
           (in.dragonbra.javasteam.steam.cdn Server Server$ConnectionProtocol)
           (in.dragonbra.javasteam.steam.handlers.steamapps PICSProductInfo PICSRequest SteamApps)
           (in.dragonbra.javasteam.steam.handlers.steamapps.callback AppOwnershipTicketCallback DepotKeyCallback
                                                                    EncryptedAppTicketCallback PICSProductInfoCallback)
           (in.dragonbra.javasteam.steam.handlers.steamcontent CDNAuthToken SteamContent)
           (in.dragonbra.javasteam.steam.handlers.steamuser LogOnDetails SteamUser)
           (in.dragonbra.javasteam.steam.handlers.steamuser.callback LoggedOnCallback)
           (in.dragonbra.javasteam.steam.steamclient SteamClient)
           (in.dragonbra.javasteam.steam.steamclient.callbackmgr CallbackManager)
           (in.dragonbra.javasteam.steam.steamclient.callbacks ConnectedCallback DisconnectedCallback)
           (in.dragonbra.javasteam.types AsyncJobMultiple$ResultSet KeyValue)
           (in.dragonbra.javasteam.util.compat Consumer)
           (java.util.concurrent CompletableFuture ExecutionException TimeUnit TimeoutException)
           (java.util.concurrent.locks ReentrantLock)
           (kotlinx.coroutines CoroutineScope Deferred GlobalScope)
           (kotlinx.coroutines.future FutureKt)))

(set! *warn-on-reflection* true)

(def defaults {:connect-timeout-ms 45000 :rpc-timeout-ms 30000 :cell-id 0})

(def login-id
  "The logon id of a logged-in session. Steam keeps one session per account
  and logon id; the default derives from the local IP, as the desktop
  client's does on the same machine."
  0x41657468) ; "Aeth"

(defn- consumer ^Consumer [f] (reify Consumer (accept [_ x] (f x))))

(defn- await!
  "The value of a future within `ms`, or a :timeout error."
  [^CompletableFuture f ms what]
  (try (.get f (long ms) TimeUnit/MILLISECONDS)
       (catch TimeoutException _ (throw (net/error :timeout (str "Steam: " what " timed out after " (quot (long ms) 1000) " s"))))
       (catch ExecutionException e
         (throw (net/error :network (str "Steam: " what ": " (some-> (.getCause e) .getMessage)))))))

(defn- deferred ^CompletableFuture [^Deferred d] (FutureKt/asCompletableFuture d))

(def ^:private ^CoroutineScope scope GlobalScope/INSTANCE)

(defn- apps ^SteamApps [^SteamClient c] (.getHandler c SteamApps))
(defn- content ^SteamContent [^SteamClient c] (.getHandler c SteamContent))

;; --- connecting -------------------------------------------------------------------------------------

(defn- connect*
  "A connected, logged-on client: {:client :manager :thread :running}."
  [{:keys [account refresh-token connect-timeout-ms]}]
  (let [client (SteamClient.)
        connected (CompletableFuture.)
        logged-on (CompletableFuture.)
        running (atom true)
        manager (doto (CallbackManager. client)
                  (.subscribe ConnectedCallback (consumer (fn [_] (.complete connected true))))
                  (.subscribe DisconnectedCallback (consumer (fn [_]
                                                               (.complete connected false)
                                                               (.completeExceptionally logged-on (ex-info "disconnected" {})))))
                  (.subscribe LoggedOnCallback (consumer (fn [cb] (.complete logged-on cb)))))
        t (Thread/startVirtualThread ^Runnable #(while @running (try (.runWaitCallbacks manager 200) (catch Throwable _))))]
    (try
      (.connect client)
      (when-not (await! connected connect-timeout-ms "connecting") (throw (net/error :network "Steam: the connection was refused")))
      (let [^SteamUser user (.getHandler client SteamUser)]
        (if refresh-token
          (.logOn user (doto (LogOnDetails.)
                         (.setUsername account)
                         (.setAccessToken refresh-token)
                         (.setShouldRememberPassword true)
                         (.setClientOSType EOSType/Win11)
                         ;; not the default, which derives from the local IP as
                         ;; the desktop client's does: Steam would replace that
                         ;; session with this one, and the game being served
                         ;; couldn't reach Steam
                         (.setLoginID (int login-id))))
          (.logOnAnonymous user)))
      (let [^LoggedOnCallback cb (await! logged-on connect-timeout-ms "logging on")
            r (.getResult cb)]
        (cond
          (= r EResult/OK) {:client client :manager manager :thread t :running running :cell-id (.getCellID cb)}
          (#{EResult/InvalidPassword EResult/AccessDenied EResult/Expired EResult/Revoked} r)
          (throw (net/error :login-expired (str "Steam refused the saved login for " account "; log in to Steam again")))
          :else (throw (net/error :protocol (str "Steam: logon failed: " r)))))
      (catch Throwable e
        (reset! running false)
        (try (.disconnect client) (catch Throwable _))
        (throw e)))))

(defn close [{:keys [state]}]
  (when-let [{:keys [^SteamClient client running]} @state]
    (reset! running false)
    (try (.disconnect client) (catch Throwable _))
    (reset! state nil)))

(defn session
  "A Steam session: anonymous, or logged on with `creds`
  ({:account :refresh-token}). Connects on first use."
  [& [creds opts]]
  (merge defaults opts {:creds creds :state (atom nil) :lock (ReentrantLock.)}))

(defn- call
  "(f client cell-id) on the connected session, one at a time; a timeout or
  a dropped connection reconnects and tries once more."
  [{:keys [creds state lock] :as s} f]
  (net/with-lock lock
    (loop [attempt 1]
      (let [conn (or @state (reset! state (connect* (merge s creds))))
            r (try {:ok (f (:client conn) (:cell-id conn))}
                   (catch clojure.lang.ExceptionInfo e
                     (if (and (< attempt 2) (#{:timeout :network} (net/kind e)))
                       (do (close s) {:again true})
                       (throw e))))]
        (if (:again r) (recur (inc attempt)) (:ok r))))))

;; --- requests ------------------------------------------------------------------------------------------

(defn depot-key
  "Depot `depot`'s 32-byte key (for `app`)."
  ^bytes [{:keys [rpc-timeout-ms] :as s} app depot]
  (call s (fn [^SteamClient c _]
            (let [^DepotKeyCallback cb (await! (.toFuture (.getDepotDecryptionKey (apps c) (int depot) (int app)))
                                               rpc-timeout-ms "depot key")]
              (if (= EResult/OK (.getResult cb))
                (.getDepotKey cb)
                (throw (net/error :access-denied (str "Steam denied access to depot " depot " (" (.getResult cb) ")"))))))))

(defn manifest-request-code
  "The request code for manifest `gid` of `depot` (0 when none is needed)."
  [{:keys [rpc-timeout-ms] :as s} app depot gid]
  (call s (fn [^SteamClient c _]
            (try (long (or (await! (deferred (.getManifestRequestCode (content c) (int depot) (int app) (long gid)
                                                                      "public" nil scope))
                                   rpc-timeout-ms "manifest request code")
                           0))
                 (catch clojure.lang.ExceptionInfo e
                   (throw (if (= :network (net/kind e))
                            (net/error :manifest-unavailable (str "Steam won't serve manifest " gid " of depot " depot))
                            e)))))))

(defn select-servers
  "CDN servers usable for `app`, DepotDownloader-style: CDN or SteamCache
  type, not China-only, not a proxy, allowed for the app; by weighted load."
  [servers app]
  (->> servers
       (filter (fn [{:keys [type china-only proxy allowed]}]
                 (and (#{"CDN" "SteamCache"} type) (not china-only) (not proxy)
                      (or (empty? allowed) (some #{app} allowed)))))
       (sort-by :weighted-load)
       (map (fn [{:keys [host vhost https]}]
              (let [h (first (str/split (if (str/blank? vhost) host vhost) #":"))]
                {:host h :port (if https 443 80) :https (boolean https)})))
       distinct
       vec))

(defn cdn-servers
  "Content servers for `app`: [{:host :port :https}], best first."
  [{:keys [rpc-timeout-ms] :as s} app]
  (call s (fn [^SteamClient c cell]
            (let [servers (await! (deferred (.getServersForSteamPipe (content c) (Integer/valueOf (int (or cell 0)))
                                                                    (Integer/valueOf 30) scope))
                                  rpc-timeout-ms "CDN servers")]
              (select-servers (for [^Server v servers]
                                {:type (.getType v) :host (.getHost v) :vhost (.getVHost v)
                                 :https (= Server$ConnectionProtocol/HTTPS (.getProtocol v))
                                 :china-only (.getSteamChinaOnly v) :proxy (.getUseAsProxy v)
                                 :allowed (vec (or (.getAllowedAppIds v) [])) :weighted-load (.getWeightedLoad v)})
                              app)))))

(defn cdn-token
  "A CDN auth token for `host` (nil when Steam gives none)."
  [{:keys [rpc-timeout-ms] :as s} app depot host]
  (call s (fn [^SteamClient c _]
            (let [^CDNAuthToken t (await! (deferred (.getCDNAuthToken (content c) (int app) (int depot) (str host) scope))
                                          rpc-timeout-ms "CDN token")]
              (when (and (= EResult/OK (.getResult t)) (not (str/blank? (.getToken t))))
                (str/replace-first (.getToken t) #"^\?" ""))))))

(defn- kv-children [^KeyValue kv] (seq (.getChildren kv)))

(defn branch-manifests
  "[[depot gid] …] of `app`'s branch (\"public\"), from PICS."
  [{:keys [rpc-timeout-ms] :as s} app branch]
  (call s (fn [^SteamClient c _]
            (let [job (.picsGetProductInfo (apps c) (PICSRequest. (int app)) nil false)
                  ^AsyncJobMultiple$ResultSet results (await! (.toFuture job) (* 2 (long rpc-timeout-ms)) "app info")
                  infos (for [^PICSProductInfoCallback cb (.getResults results)
                              [_ ^PICSProductInfo p] (.getApps cb)] p)
                  ^PICSProductInfo info (first (filter #(= (int app) (.getId ^PICSProductInfo %)) infos))]
              (when-not info (throw (net/error :not-found (str "Steam has no app " app))))
              (vec (for [^KeyValue d (kv-children (.get (.getKeyValues info) "depots"))
                         :let [depot (parse-long (.getName d))
                               gid (some-> (.get (.get (.get d "manifests") branch) "gid") .getValue parse-long)]
                         :when (and depot gid)]
                     [depot gid]))))))

(defn encrypted-app-ticket
  "An encrypted app ticket for `app` (bytes), e.g. for Bethesda.net."
  ^bytes [{:keys [rpc-timeout-ms] :as s} app]
  (call s (fn [^SteamClient c _]
            (let [^EncryptedAppTicketCallback cb (await! (.toFuture (.requestEncryptedAppTicket (apps c) (int app) (byte-array 4)))
                                                         rpc-timeout-ms "app ticket")]
              (case (str (.getResult cb))
                "OK" (.toByteArray (.getEncryptedAppTicket cb))
                "AccessDenied" (throw (net/error :not-owned (str "this Steam account doesn't own app " app)))
                (throw (net/error :protocol (str "Steam: app ticket: " (.getResult cb)))))))))

(defn owns-app?
  "Whether the account owns `app` (by its ownership ticket)."
  [{:keys [rpc-timeout-ms] :as s} app]
  (call s (fn [^SteamClient c _]
            (let [^AppOwnershipTicketCallback cb (await! (.toFuture (.getAppOwnershipTicket (apps c) (int app)))
                                                         (* 8 (long rpc-timeout-ms)) "ownership")]
              (and (= EResult/OK (.getResult cb)) (pos? (alength ^bytes (.getTicket cb))))))))

;; --- logging in ---------------------------------------------------------------------------------------------

(defn- authenticator
  "Bridges JavaSteam's Steam Guard prompts to `prompter` fns:
  :device-code (fn [previous-wrong?] code), :email-code (fn [domain previous-wrong?] code),
  :confirm (fn [] true to wait for approval in the mobile app)."
  ^IAuthenticator [{:keys [device-code email-code confirm]}]
  (reify IAuthenticator
    (getDeviceCode [_ wrong?] (CompletableFuture/completedFuture (str (device-code wrong?))))
    (getEmailCode [_ domain wrong?] (CompletableFuture/completedFuture (str (email-code domain wrong?))))
    (acceptDeviceConfirmation [_] (CompletableFuture/completedFuture (boolean (if confirm (confirm) true))))))

(defn- poll ^AuthPollResult [^AuthSession a timeout-ms]
  (try (.get (.pollingWaitForResult a) (long timeout-ms) TimeUnit/MILLISECONDS)
       (catch TimeoutException _ (throw (net/error :auth-session-expired "the Steam login timed out; start again")))
       (catch ExecutionException e
         (let [m (str (some-> (.getCause e) .getMessage))]
           (throw (net/error (cond (str/includes? m "InvalidPassword") :invalid-password
                                   (re-find #"TwoFactorCodeMismatch|InvalidLoginAuthCode" m) :invalid-guard-code
                                   :else :login)
                             (str "Steam login: " m)))))))

(defn- with-auth-client [timeout-ms f]
  (let [s (session nil {:connect-timeout-ms timeout-ms})]
    (try (call s (fn [^SteamClient c _] (f c))) (finally (close s)))))

(defn login-qr!
  "Logs in by QR: (show-qr challenge-url) is called with each challenge (a
  secret: never log it); returns {:account :refresh-token :guard-data}."
  [{:keys [show-qr device-name timeout-ms] :or {device-name "aether-director" timeout-ms 300000}}]
  (with-auth-client 45000
    (fn [^SteamClient c]
      (let [details (AuthSessionDetails.)
            _ (set! (.-deviceFriendlyName details) device-name)
            ^QrAuthSession a (.get (.beginAuthSessionViaQR ^SteamAuthentication (.getAuthentication c) details))]
        (show-qr (.getChallengeUrl a))
        (.setChallengeUrlChanged a (reify IChallengeUrlChanged
                                     (onChanged [_ q] (show-qr (.getChallengeUrl ^QrAuthSession q)))))
        (let [r (poll a timeout-ms)]
          {:account (.getAccountName r) :refresh-token (.getRefreshToken r) :guard-data (.getNewGuardData r)})))))

(defn login-password!
  "Logs in with an account and password, answering Steam Guard through
  `prompter` (see authenticator); `guard-data` from an earlier login skips
  the e-mail code. Returns {:account :refresh-token :guard-data}."
  [account ^String password & [{:keys [guard-data device-name timeout-ms] :or {device-name "aether-director" timeout-ms 300000} :as prompter}]]
  (with-auth-client 45000
    (fn [^SteamClient c]
      (let [details (AuthSessionDetails.)]
        (set! (.-username details) account)
        (set! (.-password details) password)
        (set! (.-deviceFriendlyName details) device-name)
        (set! (.-persistentSession details) true)
        (set! (.-guardData details) guard-data)
        (set! (.-authenticator details) (authenticator prompter))
        (let [a (.get (.beginAuthSessionViaCredentials ^SteamAuthentication (.getAuthentication c) details))
              r (poll a timeout-ms)]
          {:account (or (.getAccountName r) account) :refresh-token (.getRefreshToken r)
           :guard-data (or (.getNewGuardData r) guard-data)})))))
