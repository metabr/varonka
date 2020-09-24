(defproject varonka "0.1.0-SNAPSHOT"
  :description "irc bot"
  :url "https://github.com/metabr/varonka"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.3.610"]
                 [compojure "1.6.1"]
                 [clj-http "3.10.1"]
                 [http-kit "2.3.0"]
                 [enlive "1.1.6"]
                 [irclj "0.5.0-alpha4"]]
  :main ^:skip-aot varonka.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
