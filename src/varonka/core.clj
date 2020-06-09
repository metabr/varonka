(ns varonka.core
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.string :refer [trim replace starts-with?]]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [org.httpkit.server :as server]
            [clj-http.client :as client]
            [net.cgrand.enlive-html :as html]
            [irclj.core :as irc]
            [irclj.events :refer [stdout-callback]]))

(def connection (ref {}))
(def greetings (atom {}))

(def port
  (or (System/getenv "VARONKA_PORT")
      10927))

(def irc-server
  (or (System/getenv "VARONKA_IRC_SERVER")
      "irc.freenode.net"))

(def irc-port 
  (or (System/getenv "VARONKA_IRC_PORT")
      7000))

(defn random-nickname []
  (let [cs (map char (range 97 123))
        postfix (take 4 (repeatedly #(rand-nth cs)))]
    (str "varonka-" (reduce str postfix))))

(def nick
  (or (System/getenv "VARONKA_NICK")
      (random-nickname)))

(def channel 
  (or (System/getenv "VARONKA_CHANNEL")
      "#varonka"))

(def greetings-path
  (or (System/getenv "VARONKA_GREETINGS")
      "./default-greetings.edn"))

(def user-agent "varonka/0.0.1")

(defn check-head-content-type [url content-type]
  (if (string? content-type)
    (-> (client/head url {:headers {"User-Agent" user-agent}})
        :headers :content-type
        (starts-with? content-type))
    true))

(defn fetch-url [url content-type]
  (if (check-head-content-type url content-type)
    (html/html-resource (java.net.URL. url))))

(defn page-title [url prefix]
  (try
    (if-let [page (fetch-url url "text/html")]
      (let [title (first (html/select page [:title]))]
        (str prefix (apply trim (:content title)))))
    (catch Exception e (println "caught exception fetching" url \newline (.getMessage e)))))

(def url-re #"http[s]?\:\/\/[a-zA-Z0-9\-\.]+\.[a-zA-Z]{2,3}(\/\S*)?")

(defn process-url [text prefix]
  (if-let [result (re-find url-re text)]
    (let [url (-> (first result)
                  (replace #"\"$" "")
                  (replace #"…$" ""))
          title (page-title url prefix)]
      (page-title url prefix))))

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
      (irc/message conn target
                   (condp re-matches text
                     mularka-re "муларка!"
                     mularka-long-re "МУЛАРКА!!!"
                     coffee-re (rand-nth coffee-responses)
                     water-re "🌊"
                     nil)))))

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
    (irc/message conn joined-channel greeting)))

(def callbacks
  {:raw-log stdout-callback
   :privmsg privmsg-callback
   :join join-callback})

(defn load-greetings! []
  (try
    (println "Loading greetings from" greetings-path)
    (reset! greetings (edn/read-string (slurp greetings-path)))
    "OK"
    (catch Exception e
      (println "caught exception loading greetings: " (.getMessage e))
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
        :ssl? true))
    (println "Joining channel" channel)
    (irc/join @connection channel)
    (println "Connected.")))

(defn quit! []
  (irc/message @connection channel "пака")
  (irc/quit @connection)
  (shutdown-agents))

(defroutes app
  (GET "/status" [] "OK")
  (POST "/reload" [] (fn [_] (load-greetings!)))
  (route/not-found "🔦"))

(defn set-user-agent! []
  (System/setProperty "http.agent" user-agent))

(defn -main [& args]
  (set-user-agent!)
  (println "Connecting...")
  (future (connect!))
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable quit!))
  (server/run-server app {:port port}))
