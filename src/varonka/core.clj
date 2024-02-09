(ns varonka.core
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.core.async :refer [thread]]
            [clojure.java.shell :refer [sh]]
            [clojure.string :refer [trim replace starts-with? split]]
            [io.pedestal.log :as log]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [org.httpkit.server :as server]
            [clj-http.client :as client]
            [net.cgrand.enlive-html :as html]
            [irclj.core :as irc]))

(def connection (ref {}))
(def greetings (atom {}))
(def last-activity (atom (System/currentTimeMillis)))

(def port
  (if-let [p (System/getenv "VARONKA_PORT")]
    (Integer/parseInt p)
    10927))

(def irc-server
  (or (System/getenv "VARONKA_IRC_SERVER")
      "irc.freenode.net"))

(def irc-port
  (if-let [p (System/getenv "VARONKA_IRC_PORT")]
    (Integer/parseInt p)
    7000))

(def ssl?
  (if (System/getenv "VARONKA_NOSSL")
    false
    true))

(defn random-nickname []
  (let [cs (map char (range 97 123))
        postfix (take 4 (repeatedly #(rand-nth cs)))]
    (str "varonka-" (reduce str postfix))))

(def nick
  (or (System/getenv "VARONKA_NICK")
      (random-nickname)))

(def channels
  (split
   (or (System/getenv "VARONKA_CHANNELS")
       "#varonka")
   #","))

(def greetings-path
  (or (System/getenv "VARONKA_GREETINGS")
      "./default-greetings.edn"))

(def ping-timeout
  (* 1000
     (if-let [t (System/getenv "VARONKA_PING_TIMEOUT")]
       (Integer/parseInt t)
       180)))

(def user-agent "varonka/0.0.1")

(defn check-head-content-type [url content-type]
  (if (string? content-type)
    (-> (client/head url {:headers {"User-Agent" user-agent}})
        :headers :content-type
        (starts-with? content-type))
    true))

(defn fetch-url
  ([url content-type]
   (when (check-head-content-type url content-type)
     (html/html-resource (java.net.URL. url))))
  ([url]
   (fetch-url url "text/html")))

(defn page-title [url prefix]
  (try
    (when-let [page (fetch-url url "text/html")]
      (let [title (first (html/select page [:title]))]
        (str prefix (apply trim (:content title)))))
    (catch Exception e
      (log/error :page-title {:message (.getMessage e) :url url}))))

(def url-re #"http[s]?\:\/\/[a-zA-Z0-9\-\.]+\.[a-zA-Z]{2,3}(\/\S*)?")
(def youtube-re #"(youtube\.com)|(youtu\.be)")
(def twitter-re #"http[s]?\:\/\/.*[\.]?twitter\.com\/")

(defn youtube-title [url prefix]
  (let [result (sh "yt-dlp" "--get-title" url)]
    (if (== 0 (:exit result))
      (str prefix (trim (:out result)))
      (log/warn :youtube-title {:message "Failed to obtain title" :url url :result result}))))

(defn process-url [text prefix]
  (when-let [result (re-find url-re text)]
    (let [url (-> (first result)
                  (replace #"\"$" "")
                  (replace #"…$" ""))]
      (cond
        (re-find youtube-re url) (youtube-title url prefix)
        (re-find twitter-re url) nil
        :else (page-title url prefix)))))

(def mularka-re #"^[у|У]{8,}$")
(def mularka-long-re #"^[у|У]{24,}$")

(def coffee-re #"^[к|К]+[о|О]+[ф|Ф]+[е|Е]+.*")
(def coffee-responses ["☕" "🍰" "☕" "🧁" "☕" "🥐" "☕" "🍪" "☕"])

(def water-re #"^[в|В][о|О][Д|д][Ы|ы]+.*")

(defn privmsg-callback [conn {:keys [target text] :as t} & _]
  (let [target (if (= target nick)
                 (:nick t)
                 target)]
    (if-let [msg (process-url text "⤷ ")]
      (do
        (irc/message conn target msg)
        (irc/message conn target (process-url msg "  ⤷ ")))
      (when-let [msg (condp re-matches text
                       mularka-re "муларка!"
                       mularka-long-re "МУЛАРКА!!!"
                       coffee-re (rand-nth coffee-responses)
                       water-re "🌊"
                       nil)]
        (irc/message conn target msg)))))

(defn join-callback [conn t & _]
  (let [joined-nick (:nick t)
        joined-channel (first (:params t))
        filter-fn
        (fn [{nick-pattern :nick}]
          (re-matches (re-pattern nick-pattern) joined-nick))
        greeting
        (if (= joined-nick nick)
          "преветик"
          (when-let [match (first (filter filter-fn @greetings))]
            (:message match)))]
    (when greeting
      (irc/message conn joined-channel greeting))))

(defn raw-callback [_ t s]
  (case t
    :write
    (log/info :raw-callback/write {:message s})
    :read
    (do (reset! last-activity (System/currentTimeMillis))
        (log/info :raw-callback/read {:message s}))))

(def callbacks
  {:raw-log raw-callback
   :privmsg privmsg-callback
   :join join-callback})

(defn load-greetings! []
  (try
    (log/debug :load-greetings! {:path greetings-path})
    (reset! greetings (edn/read-string (slurp greetings-path)))
    "OK"
    (catch Exception e
      (log/error :load-greetings! {:message (.getMessage e)})
      "ERROR")))

(defn connect! []
  (dosync
   (load-greetings!)
   (ref-set
    connection
    (irc/connect
     irc-server irc-port nick
     :pass (System/getenv "VARONKA_PASS")
     :callbacks callbacks
     :ssl? ssl?)))
  (log/debug :connect! {:message "connecting" :channels channels})
  (Thread/sleep 1000)
  (run! #(irc/join @connection %) channels)
  (log/debug :connect! {:message "done"}))

(defn quit! []
  (run! #(irc/message @connection % "пака") channels)
  (irc/quit @connection)
  (shutdown-agents))

(defn reconnect! []
  (try
    (irc/kill @connection)
    (connect!)
    (catch Exception e
      (log/error :reconnect! {:message (.getMessage e)})
      (Thread/sleep 10000)
      (reconnect!))))

(defroutes app
  (GET "/status" [] "OK")
  (POST "/reload" [] (fn [_] (load-greetings!) (str @greetings \newline)))
  (POST "/reconnect" [] (fn [_] (reconnect!) "done"))
  (route/not-found "🔦"))

(defn set-user-agent! []
  (System/setProperty "http.agent" user-agent))

(defn ping-timeout-watcher []
  (while true
    (let [cur (System/currentTimeMillis)
          diff (- cur @last-activity)]
      (when (> diff ping-timeout)
        (log/warn :ping-timeout-watcher
                  {:message "PING timeout exceeded, reconnecting!"
                   :cur cur :diff diff
                   :last-activity @last-activity :ping-timeout ping-timeout})
        (reset! last-activity cur)
        (reconnect!)))
    (Thread/sleep 10000)))

(defn -main [& _]
  (set-user-agent!)
  (log/debug :-main "Connecting...")
  (future (connect!))
  (thread (ping-timeout-watcher))
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable quit!))
  (server/run-server app {:port port}))
