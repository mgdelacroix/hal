(defproject mee6 "0.1.0-SNAPSHOT"
  :description "A simple monitoring tool"
  :url ""
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :javac-options ["-target" "1.8" "-source" "1.8" "-Xlint:-options"]
  :dependencies [[org.clojure/clojure "1.9.0"]]
  :aliases {"brepl" ["with-profiles" "+back" "repl"]
            "frepl" ["with-profiles" "+front" "figwheel"]})
