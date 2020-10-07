(ns varonka.core
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.core.async :refer [thread]]
            [clojure.java.shell :refer [sh]]
            [clojure.string :refer [trim replace starts-with? split]]
            [clojure.tools.logging :refer [debug info warn error]]
            [compojure.core :refer :all]
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
   (if (check-head-content-type url content-type)
     (html/html-resource (java.net.URL. url))))
  ([url]
   (fetch-url url "text/html")))

(defn page-title [url prefix]
  (try
    (if-let [page (fetch-url url "text/html")]
      (let [title (first (html/select page [:title]))]
        (str prefix (apply trim (:content title)))))
    (catch Exception e (error "caught exception fetching" url \newline (.getMessage e)))))

(def url-re #"http[s]?\:\/\/[a-zA-Z0-9\-\.]+\.[a-zA-Z]{2,3}(\/\S*)?")
(def youtube-re #"(youtube\.com)|(youtu\.be)")
(def twitter-re #"http[s]?\:\/\/twitter\.com\/")
(def mobile-twitter-re #"http[s]?\:\/\/mobile\.twitter\.com\/")

(defn youtube-title [url prefix]
  (let [result (sh "youtube-dl" "--get-title" url)]
    (if (== 0 (:exit result))
      (str prefix (trim (:out result)))
      (warn "Failed to get title for" url \newline
            "Result:" result))))

(defn tweet-text [url prefix]
  (try
    (if-let [page (fetch-url url)]
      (let [text (-> page (html/select [:div.tweet-text])
                     first html/text trim)]
        (str prefix text)))
    (catch Exception e (error "caught exception fetching" url \newline (.getMessage e)))))

(defn process-url [text prefix]
  (if-let [result (re-find url-re text)]
    (let [url (-> (first result)
                  (replace #"\"$" "")
                  (replace #"…$" ""))
          title (page-title url prefix)]
      (cond
        (re-find youtube-re url) (youtube-title url prefix)
        (re-find twitter-re url) (tweet-text (replace url twitter-re "https://mobile.twitter.com/") prefix)
        (re-find mobile-twitter-re url) (tweet-text url prefix)
        :default (page-title url prefix)))))

(def mularka-re #"^[у|У]{8,}$")
(def mularka-long-re #"^[у|У]{24,}$")

(def coffee-re #"^[к|К]+[о|О]+[ф|Ф]+[е|Е]+.*")
(def coffee-responses ["☕" "🍰" "☕" "🧁" "☕" "🥐" "☕" "🍪" "☕"])

(def water-re #"^[в|В][о|О][Д|д][Ы|ы]+.*")

(defn privmsg-callback [conn {:keys [target text] :as t} & s]
  (let [target (if (= target nick)
                 (:nick t)
                 target)]
    (if-let [msg (process-url text "⤷ ")]
      (do
        (irc/message conn target msg)
        (irc/message conn target (process-url msg "  ⤷ ")))
      (let [msg (condp re-matches text
                  mularka-re "муларка!"
                  mularka-long-re "МУЛАРКА!!!"
                  coffee-re (rand-nth coffee-responses)
                  water-re "🌊"
                  nil)]
        (if msg (irc/message conn target msg))))))

(defn join-callback [conn t & _]
  (let [joined-nick (:nick t)
        joined-channel (first (:params t))
        filter-fn
        (fn [{nick-pattern :nick}]
          (re-matches (re-pattern nick-pattern) joined-nick))
        greeting
        (if (= joined-nick nick)
          "преветик"
          (if-let [match (first (filter filter-fn @greetings))]
            (:message match)))]
    (if greeting
      (irc/message conn joined-channel greeting))))

(defn raw-callback [_ t s]
  (case t
    :write
    (info ">> " s)
    :read
    (do (reset! last-activity (System/currentTimeMillis))
        (info s))))

(def callbacks
  {:raw-log raw-callback
   :privmsg privmsg-callback
   :join join-callback})

(defn load-greetings! []
  (try
    (do
      (debug "Loading greetings from" greetings-path)
      (reset! greetings (edn/read-string (slurp greetings-path)))
      "OK")
    (catch Exception e
      (error "caught exception loading greetings: " (.getMessage e))
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
  (debug "Joining channels" channels)
  (Thread/sleep 1000)
  (run! #(irc/join @connection %) channels)
  (debug "Connected."))

(defn quit! []
  (run! #(irc/message @connection % "пака") channels)
  (irc/quit @connection)
  (shutdown-agents))

(defn reconnect! []
  (irc/kill @connection)
  (connect!))

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
      (if (> diff ping-timeout)
        (do (warn "PING timeout exceeded, reconnecting!")
            (reset! last-activity cur)
            (reconnect!))))
    (Thread/sleep 10000)))

(defn -main [& args]
  (set-user-agent!)
  (debug "Connecting...")
  (future (connect!))
  (thread (ping-timeout-watcher))
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable quit!))
  (server/run-server app {:port port}))
