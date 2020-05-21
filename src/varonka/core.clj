(ns varonka.core
  (:gen-class)
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [org.httpkit.server :as server]
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
  (html/html-resource (java.net.URL. url)))

(defn page-title [url]
  (try
    (let [page (fetch-url url)
          title (first (html/select page [:title]))]
      (apply clojure.string/trim (:content title)))
    (catch Exception e (println "caught exception fetching" url \newline (.getMessage e)))))

(def url-re #"http[s]?\:\/\/[a-zA-Z0-9\-\.]+\.[a-zA-Z]{2,3}(\/\S*)?")

(defn privmsg-callback [conn t & s]
  (if-let [res (re-find url-re (:text t))]
    (let [url (first res)]
      (irc/message @connection (:target t) (page-title url)))))

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

(defroutes app
  (GET "/status" [] "OK")
  (route/not-found "🔦"))

(defn -main [& args]
  (println "Connecting...")
  (future (connect!))
  (server/run-server app {:port 8080}))
