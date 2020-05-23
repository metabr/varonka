(ns varonka.core
  (:gen-class)
  (:require [clojure.string :refer [trim replace starts-with?]]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [org.httpkit.server :as server]
            [clj-http.client :as client]
            [net.cgrand.enlive-html :as html]
            [irclj.core :as irc]
            [irclj.events :refer [stdout-callback]]))

(def connection (ref {}))

(def irc-server
  (or (System/getenv "VARONKA_IRC_SERVER")
      "irc.freenode.net"))

(def irc-port 
  (or (System/getenv "VARONKA_IRC_PORT")
      7000))

(def nick
  (or (System/getenv "VARONKA_NICK")
      "varonka"))

(def channel 
  (or (System/getenv "VARONKA_CHANNEL")
      "#varonka"))

(defn fetch-url [url]
  (if (-> (client/head url)
          :headers
          :content-type
          (starts-with? "text/html"))
    (html/html-resource (java.net.URL. url))))

(defn page-title [url prefix]
  (try
    (if-let [page (fetch-url url)]
      (let [title (first (html/select page [:title]))]
        (str prefix (apply trim (:content title)))))
    (catch Exception e (println "caught exception fetching" url \newline (.getMessage e)))))

(def url-re #"http[s]?\:\/\/[a-zA-Z0-9\-\.]+\.[a-zA-Z]{2,3}(\/\S*)?")

(defn process-url [conn target text prefix]
  (if-let [result (re-find url-re text)]
    (let [url (first result)
          title (page-title url prefix)]
      (irc/message conn target title)
      title)))

(defn privmsg-callback [conn t & s]
  (if-let [title (process-url conn (:target t) (:text t) "⤷ ")]
    (process-url conn (:target t) title "  ⤷ ")))

(def callbacks
  {:raw-log stdout-callback
   :privmsg privmsg-callback})

(defn connect! []
  (dosync
    (ref-set
      connection
      (irc/connect
        irc-server irc-port nick
        :pass (System/getenv "VARONKA_PASS")
        :callbacks callbacks
        :ssl? true))
    (println "Joining channel" channel)
    (irc/join @connection channel)
    (irc/message @connection channel "превед")
    (println "Connected.")))

(defn quit! []
  (irc/message @connection channel "пака")
  (irc/quit @connection)
  (shutdown-agents))

(defroutes app
  (GET "/status" [] "OK")
  (route/not-found "🔦"))

(defn -main [& args]
  (println "Connecting...")
  (future (connect!))
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable quit!))
  (server/run-server app {:port 8080}))
