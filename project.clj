(defproject varonka "0.1.0-SNAPSHOT"
  :description "irc bot"
  :url "https://github.com/metabr/varonka"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/core.async "1.6.681"]
                 [org.slf4j/slf4j-log4j12 "1.7.30"]
                 [compojure "1.7.1"]
                 [clj-http "3.13.0"]
                 [http-kit/http-kit "2.8.0"]
                 [enlive "1.1.6"]
                 [irclj "0.5.0-alpha4"]
                 [io.pedestal/pedestal.log "0.7.1"]]
  :main ^:skip-aot varonka.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
