(ns ct.spools.dresser-edges-test
  "End-to-end contract edges for dresser run identity, routing, evidence, and plan."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millstrand.api.spool.alpha :as spool]
            [millstrand.api.weaver.alpha :as weaver]
            [ct.spools.dresser :as dresser]
            [ct.spools.dresser.aspects :as aspects]
            [ct.spools.dresser-fixtures :as fixtures]
            [ct.spools.dresser.receipt :as receipt]
            [ct.spools.dresser.target :as target]
            [millhouse.spools.workflow :as workflow]))

(defn- op! [runtime & args]
  (weaver/op! runtime 'dresser (vec args)))

(defn- advance-ready! [runtime flavour root & args]
  (let [step (first (apply op! runtime "next" flavour (str root) args))]
    (apply op! runtime "advance" flavour (str root)
           (concat args ["--step" (:id step)]))))

(defn- receipt-for [release fingerprint aspect-key]
  {:dresser/release release
   :dresser/fingerprint fingerprint
   :aspects {aspect-key {:version (:version (aspects/aspect aspect-key))
                         :release release
                         :applied-at "2026-07-14"}}})

(deftest full-spool-repo-run-stamps-all-aspects-and-plans-current
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "fixture")
          aspect-keys (aspects/flavour-aspects "spool-repo")]
      (fixtures/seed-spool-repo! root)
      (fixtures/with-dresser-runtime
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root))
          (fixtures/assert-done! (fixtures/drive-spool-repo! runtime root))
          (let [stamp (fixtures/stamp-and-assert-current! root aspect-keys)
                planned (op! runtime "plan" (str root))]
            (is (= aspects/release-version (:dresser/release stamp)))
            (is (= (aspects/releases aspects/release-version)
                   (:dresser/fingerprint stamp)))
            (is (= (set aspect-keys) (set (keys (:aspects stamp)))))
            (is (every? #{:current} (map (:aspects planned) aspect-keys)))))))))

(deftest full-flavour-start-names-a-registered-definition-and-a-subset-does-not
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "start-target")
          full-run (target/run-id "spool-repo" root)
          subset-run (target/verify-run-id "spool-repo" root)]
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root))
          (op! runtime "verify" "spool-repo" (str root)
               "--aspects" "spool-repo/agent-docs")
          (is (= "spool-repo"
                 (spool/attr-get (workflow/current-root full-run)
                                 :workflow/definition-name)))
          ;; A `call` takes no `:condition`, so a subset of a flavour is a
          ;; definition the registry cannot hold: dresser builds and pours the
          ;; value, which names no registered definition.
          (is (nil? (spool/attr-get (workflow/current-root subset-run)
                                    :workflow/definition-name))))))))

(deftest quality-subset-pulls-dependency-and-pours-only-closed-selection
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "subset")
          run-id (target/run-id "spool-repo" root)
          expected #{"spool-repo/repo-skeleton" "spool-repo/quality"}]
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root)
               "--aspects" "spool-repo/quality")
          (is (= expected (fixtures/poured-aspects runtime run-id)))
          (is (str/starts-with? (:title (first (op! runtime "next" "spool-repo"
                                                    (str root))))
                                "Inspect repo-skeleton")))))))

(deftest both-flavours-run-concurrently-on-one-root-and-are-drivable
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "concurrent")
          spool-run (target/run-id "spool-repo" root)
          millstrand-run (target/run-id "millstrand-dir" root)]
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root)
               "--aspects" "spool-repo/agent-docs")
          (op! runtime "start" "millstrand-dir" (str root)
               "--aspects" "millstrand-dir/workspace")
          (is (not= spool-run millstrand-run))
          (is (= #{spool-run millstrand-run}
                 (set (map #(spool/attr-get % :workflow/run-id)
                           (workflow/active-runs "dresser")))))
          (advance-ready! runtime "spool-repo" root)
          (advance-ready! runtime "millstrand-dir" root)
          (is (= "checkpoint"
                 (:role (first (op! runtime "next" "spool-repo" (str root))))))
          (is (= "checkpoint"
                 (:role (first (op! runtime "next" "millstrand-dir" (str root)))))))))))

(deftest second-start-on-active-flavour-root-mode-fails-loudly
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "active")
          run-id (target/run-id "spool-repo" root)]
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root)
               "--aspects" "spool-repo/agent-docs")
          (let [exception (fixtures/capture-exception
                           #(op! runtime "start" "spool-repo" (str root)))]
            (is (some? exception))
            (is (str/includes? (ex-message exception) "Active workflow run already exists"))
            (is (= run-id (:run-id (ex-data exception))))))))))

(deftest repeated-run-uses-fresh-molecule-and-rejects-stale-green-gates
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "molecule")
          aspect-key "spool-repo/agent-docs"
          run-id (target/run-id "spool-repo" root)]
      (fixtures/write-spool-repo-step-files! root "Write AGENTS.md")
      (fixtures/with-dresser-runtime
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root) "--aspects" aspect-key)
          (fixtures/assert-done! (fixtures/drive-spool-repo! runtime root))
          (is (= :current (:plan (dresser/stamp! aspect-key root))))
          (op! runtime "start" "spool-repo" (str root) "--aspects" aspect-key)
          (let [history (workflow/run-history run-id)
                exception (fixtures/capture-exception #(dresser/stamp! aspect-key root))
                violations (set (map :violation (:violations (ex-data exception))))]
            (is (= 2 (count history)))
            (is (apply not= (map #(get-in % [:root :id]) history)))
            (is (str/includes? (ex-message exception) "stamp evidence failed"))
            (is (seq (set/intersection
                      #{:gate-not-closed :outcome-by :exit-code}
                      violations))
                "molecule-one green gates are stale for molecule two")))))))

(deftest apply-plan-records-decisions-and-kept-customization-can-converge
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "keep")
          aspect-key "spool-repo/agent-docs"
          custom "# Local agent policy\n\n<!-- mill:millstrand-prime -->\nKeep this local rule.\n"
          agents-file (io/file (str root) "AGENTS.md")]
      (spit agents-file custom)
      (fixtures/with-dresser-runtime
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root) "--aspects" aspect-key)
          (advance-ready! runtime "spool-repo" root)
          (let [checkpoint (first (op! runtime "next" "spool-repo" (str root)))]
            (op! runtime "advance" "spool-repo" (str root)
                 "--step" (:id checkpoint)
                 "--choice" "apply-plan"
                 "--input" "decisions=keep AGENTS.md local policy"))
          (let [setup (first (op! runtime "next" "spool-repo" (str root)))]
            (is (= "Write AGENTS.md" (:title setup)))
            (op! runtime "advance" "spool-repo" (str root)
                 "--step" (:id setup)))
          (fixtures/assert-done! (fixtures/wait-for-attention! runtime
                                                               (target/run-id "spool-repo" root)))
          (let [choice (some #(when (= :choice (:type %)) %)
                             (:events (first (workflow/run-history
                                              (target/run-id "spool-repo" root)))))
                decisions (or (get-in choice [:input :decisions])
                              (get-in choice [:input "decisions"]))]
            (is (= "apply-plan" (:outcome choice)))
            (is (= "keep AGENTS.md local policy" decisions))
            (is (= custom (slurp agents-file)))
            (is (= :current (:plan (dresser/stamp! aspect-key root))))))))))

(deftest abort-routes-to-terminal-workflow-and-runs-no-gates
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "abort")
          run-id (target/run-id "spool-repo" root)]
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (op! runtime "start" "spool-repo" (str root)
               "--aspects" "spool-repo/agent-docs")
          (advance-ready! runtime "spool-repo" root)
          (let [checkpoint (first (op! runtime "next" "spool-repo" (str root)))]
            (op! runtime "advance" "spool-repo" (str root)
                 "--step" (:id checkpoint)
                 "--choice" "abort"
                 "--input" "reason=fixture conflict"))
          (is (= "Record dresser abort"
                 (:title (first (op! runtime "next" "spool-repo" (str root))))))
          (let [result (advance-ready! runtime "spool-repo" root)
                gates (filter #(= "shell" (spool/attr-get % :workflow/gate))
                              (fixtures/all-run-strands runtime run-id))]
            (is (true? (:done result)))
            (is (= 2 (count (workflow/run-history run-id))))
            (is (seq gates))
            (is (every? #(nil? (spool/attr-get % :shell/exit-code)) gates))
            (is (every? #(not= "shell" (spool/attr-get % :workflow/outcome-by))
                        gates))))))))

(deftest plan-op-classifies-bogus-fingerprint-and-future-release
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "plan")
          aspect-key "spool-repo/repo-skeleton"
          release aspects/release-version]
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (receipt/write-receipt! root (receipt-for release "bogus" aspect-key))
          (let [plan (op! runtime "plan" (str root))]
            (is (= :divergent (get-in plan [:aspects aspect-key])))
            (is (= :divergent (get-in plan [:provenance :verdict]))))
          (let [future (inc release)]
            (receipt/write-receipt! root (receipt-for future "future" aspect-key))
            (let [plan (op! runtime "plan" (str root))]
              (is (= :ahead (get-in plan [:aspects aspect-key])))
              (is (= :ahead (get-in plan [:provenance :verdict]))))))))))

(deftest verify-on-converged-fixture-pours-only-gates-and-uses-verify-addressing
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "verify")
          aspect-key "spool-repo/agent-docs"
          run-id (target/verify-run-id "spool-repo" root)
          release aspects/release-version
          fingerprint (aspects/releases release)]
      (fixtures/write-spool-repo-step-files! root "Write AGENTS.md")
      (receipt/write-receipt! root (receipt-for release fingerprint aspect-key))
      (fixtures/with-dresser-runtime
        {:real-shell? false}
        (fn [runtime _]
          (is (= :current (get-in (op! runtime "plan" (str root))
                                  [:aspects aspect-key])))
          (op! runtime "verify" "spool-repo" (str root) "--aspects" aspect-key)
          (let [strands (fixtures/latest-molecule-strands runtime run-id)
                ready (op! runtime "next" "spool-repo" (str root) "--verify")]
            (is (= 1 (count ready)))
            (is (= #{"shell"} (set (map :gate ready))))
            (is (not-any? #(or (str/starts-with? (:title %) "Inspect ")
                               (= "checkpoint" (spool/attr-get % :workflow/role))
                               (str/starts-with? (:title %) "Write "))
                          strands))
            (let [result (op! runtime "advance" "spool-repo" (str root)
                              "--verify" "--step" (:id (first ready)))]
              (is (true? (:done result)))
              (is (empty? (op! runtime "next" "spool-repo" (str root)
                               "--verify"))))))))))
