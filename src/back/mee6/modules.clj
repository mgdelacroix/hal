(ns mee6.modules
  "Dynamic module loading and running."
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [cheshire.core :as json]
            [datoteka.core :as fs]
            [cuerdas.core :as str]
            [mee6.exceptions :as exc]
            [mee6.config :as cfg]
            [mee6.uuid :as uuid]
            [mee6.util.crypto :as crypto]))

(defn- resolve-from-classpath
  [module]
  {:pre [(string? module)]}
  (io/resource (str/istr "scripts/~{module}")))

(defn- resolve-from-userpath
  [module]
  {:pre [(string? module)]}
  (letfn [(resolve [path]
            (let [path (fs/join path module)]
              (when (fs/regular-file? path)
                (reduced path))))]
    (reduce #(resolve %2) nil (:modules cfg/config []))))

(defn- resolve-script
  [module]
  (let [script (or (resolve-from-classpath module)
                   (resolve-from-userpath module))]
    (if (nil? script)
      (exc/raise :type :engine-error
                 :message (str/istr "Script not found for module '~{module}'."))
      script)))

(defn- run-script
  "Executes a local script by name on remote host."
  [{:keys [uri] :as host} script args]
  (let [content (slurp script)
        tmpname (crypto/digest-data content)
        command (str/istr "cat > /tmp/~{tmpname} <<EOF\n~{content}\nEOF\n\n"
                          "chmod +x /tmp/~{tmpname}\n\n"
                          "/tmp/~{tmpname} ~{args}\n\n")]
    (if (= host :mee6.engine/localhost)
      (shell/sh "bash" "-c" command)
      (shell/sh "timeout" "5" "ssh" "-q" uri command))))

(defn- run-user-script
  [{:keys [uri] :as host} script]
  (let [command (str/istr "cat > /tmp/.mee6_user_script <<EOF\n~{script}\nEOF\n\n"
                          "bash /tmp/.mee6_user_script")]
    (if (= host :mee6.engine/localhost)
      (shell/sh "bash" "-c" command)
      (shell/sh "timeout" "5" "ssh" "-q" uri command))))

(s/def ::status #{"green" "red"})
(s/def ::local map?)
(s/def ::script-output (s/keys :req-un [::status ::local]))

(defn- prepare-kwargs
  [data]
  (letfn [(prepare-key [key]
            (str "--" (name key)))
          (prepare-val [val]
            (if (string? val)
              (-> (str/replace val "'" "\\'")
                  (str/surround "'"))
              (prepare-val (json/encode val))))

          (process-keyval [acc key val]
            (let [pkey (prepare-key key)]
              (if (sequential? val)
                (into acc (->> (map prepare-val val)
                               (map #(vector pkey %))))
                (conj acc [pkey (prepare-val val)]))))]
    (reduce-kv process-keyval [] data)))

(defn- prepare-arguments
  [check local]
  (let [initial (-> (json/encode {:params check :local (or local {})})
                    (str/replace "'" "\\'")
                    (str/surround "'"))
        kwargs  (prepare-kwargs (dissoc check :name :host :tags :cron))]
    (->> (into [initial] (mapcat identity kwargs))
         (str/join " "))))

(defn- execute-module
  "Resolve and execute check."
  [{:keys [module host] :as check} local]
  (let [local (or local {})
        args (prepare-arguments check local)
        script (resolve-script module)
        {:keys [exit out err] :as result} (run-script host script args)]
    (if-not (zero? exit)
      (exc/raise :type :execution-error
                 :message "Error running the script."
                 :stdout out
                 :stderr err)
      (try
        (let [out (json/decode out true)]
          (if (s/valid? ::script-output out)
            [(keyword (:status out)) (:local out)]
            (exc/raise :type :module-error
                       :message "Output does not conform with mee6's spec."
                       :output out
                       :hint (s/explain-str ::script-output out))))
        (catch com.fasterxml.jackson.core.JsonParseException e
          (exc/raise :type :module-error
                     :message "Output is not a valid json."
                     :output out))))))

(declare execute-user-script)

(defn execute
  "Resolve and execute check."
  [{:keys [module host] :as check} local]
  (if (= module "user-script")
    (execute-user-script check local)
    (execute-module check local)))

;; --- Special case: user script

(defn- parse-kvline
  [state line]
  (let [[key value] (map str/trim (str/split line #":" 2))]
    (assoc! state (keyword "script" key) value)))

(defn- parse-kvlines
  [kvlines]
  (persistent!
   (reduce parse-kvline (transient {}) kvlines)))

(defn- process-script-output
  [output]
  (let [[stdout kvlines] (split-with #(not= % "---") (str/lines output))]
    {:stdout (str/join "\n" stdout)
     :kvpairs (parse-kvlines (rest kvlines))}))

(defn- execute-user-script
  "Resolve and execute check."
  [{:keys [file host args] :as check} local]
  (let [{:keys [exit] :as result} (run-user-script host (slurp (io/file file)))
        local (-> (:out result)
                  (process-script-output)
                  (assoc :exitcode exit))
        status (case exit 0 :green 1 :red :grey)]
    [status local]))
