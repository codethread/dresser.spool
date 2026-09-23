(ns ct.spools.dresser-process-fixture
  "Real-command terminal facts for disposable worlds without a Mill channel.

  Mirrors the Workflow spool's shell test seam: the real shell executor still
  validates requests and commits outcomes, but native process custody is local
  to this fixture. This does not test the Weaver-to-Mill transport."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [millstrand.api.process.alpha :as process])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn with-process-custody
  "Run f with local terminal facts backed by real command output.

  Keep the seam installed until the disposable runtime shuts down, so its
  executor reconciliation sees the same records throughout the test."
  [f]
  (let [root (Files/createTempDirectory "dresser-custody-" (make-array FileAttribute 0))
        records (atom {})]
    (try
      (with-redefs [process/launch!
                    (fn [_ owner key {:keys [argv cwd]}]
                      (let [{:keys [exit out err]} (apply sh/sh (concat argv [:dir cwd]))
                            stdout (io/file (str root) (str key "-stdout"))
                            stderr (io/file (str root) (str key "-stderr"))
                            handle (str "dresser-custody-" key)
                            record {:handle handle :owner owner :key key
                                    :phase :terminal
                                    :exit {:code exit :signal nil}
                                    :output {:stdout-ref (str stdout)
                                             :stderr-ref (str stderr)}}]
                        (spit stdout out)
                        (spit stderr err)
                        (swap! records assoc handle record)
                        record))
                    process/get (fn [_ handle] (get @records handle))
                    process/list-owned (fn [_ owner]
                                         (filterv #(= owner (:owner %)) (vals @records)))
                    process/acknowledge! (fn [_ _ handle]
                                           (swap! records dissoc handle)
                                           {:acknowledged true :handle handle})]
        (f))
      (finally
        (doseq [file (reverse (file-seq (.toFile root)))]
          (io/delete-file file))))))
