(ns mee6.util.router
  (:require [bide.core :as r]
            [beicon.core :as rx]
            [potok.core :as ptk]))

(defonce +router+ nil)
(defonce +default-route+ :home)

;; --- Update Location (Event)

(deftype UpdateLocation [id params]
  ptk/UpdateEvent
  (update [_ state]
    (let [route (merge {:id id}
                       (when params
                         {:params params}))]
      (assoc state :route route))))

(defn update-location?
  [v]
  (instance? UpdateLocation v))

(defn update-location
  [name params]
  (UpdateLocation. name params))

;; --- Navigate (Event)

(deftype Navigate [id params]
  ptk/EffectEvent
  (effect [_ state stream]
    (r/navigate! +router+ id params)))

(defn navigate
  ([id] (navigate id nil))
  ([id params]
   {:pre [(keyword? id)]}
   (Navigate. id params)))

;; --- Public Api

(defn init
  [store routes {:keys [default] :or {default +default-route+}}]
  (let [opts {:on-navigate #(ptk/emit! store (update-location %1 %2))
              :default default}
        router (-> (r/router routes)
                   (r/start! opts))]
     (set! +router+ router)
     router))

(defn route-for
  "Given a location handler and optional parameter map, return the URI
  for such handler and parameters."
  ([id]
   (if +router+
     (r/resolve +router+ id)
     ""))
  ([id params]
   (if +router+
     (r/resolve +router+ id params)
     "")))
