(ns cldwalker.schema.cli
  "Common fns for babashka/clojure CLIs")

(defn error
  "Print error message(s) and exit"
  [& msgs]
  (apply println "Error:" msgs)
  (System/exit 1))
