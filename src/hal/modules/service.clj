(ns hal.modules.service
  (:require [hal.ssh :as ssh]))

(defn get-last-log
  [host service]
  (:out (ssh/run host (str "journalctl -r -n 10 -u " service))))

(defn run
  [{:keys [host service] :as ctx}]
  (let [{:keys [exit] :as result} (ssh/run host (str "systemctl status " service))]
    {:status (if (= exit 0) :up :down)
     :last-log (get-last-log host service)}))

(defn check
  [ctx {:keys [status] :as result}]
  (if (= status :up)
    :green
    :red))
