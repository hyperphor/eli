(ns hyperphor.eli.core
  (:gen-class)
  (:require [hyperphor.way.server :as server]
            [hyperphor.way.config :as config]
            [hyperphor.eli.handler :as handler]
            [hyperphor.multitool.cljcore :as ju]
            [taoensso.timbre :as log]
            [environ.core :as env]))

(defn -main
  [& args]
  (config/read-config "config.edn")
  (let [port (or (first args) (env/env :port))]
    (log/info "Starting eli server on port" port)
    (server/start (Integer. port) (handler/app))
    ;; No-op on a real server (Heroku etc).
    (ju/open-url (format "http://localhost:%s" port))))
