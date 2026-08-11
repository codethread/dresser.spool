(ns ct.spools.dresser-test
  "Tests for the ct.spools.dresser convention-convergence spool: template
  and aspect-registry data, workflow compilation in setup and verify-only
  modes, target-root resolution, receipt semantics, and end-to-end fixture
  runs against a disposable weaver world."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :as spool]
            [millstrand.api.vocab.alpha :as vocab]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [ct.spools.dresser :as dresser]
            [ct.spools.dresser.aspects :as aspects]
            [ct.spools.dresser-edges-test]
            [ct.spools.dresser-fixtures :as fixtures]
            [ct.spools.dresser.receipt :as receipt]
            [ct.spools.dresser.specs :as specs]
            [ct.spools.dresser.target :as target]
            [ct.spools.dresser.templates :as templates]
            [ct.spools.dresser.workflows :as dresser-workflows]
            [millhouse.spools.workflow :as workflow]
            [millstrand.test.alpha :as t])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.security MessageDigest)))

(deftest dresser-exports-form-authored-lifecycle-surface
  (is (ifn? (ns-resolve 'ct.spools.dresser 'dresser-op)))
  (is (ifn? dresser/seed-dresser-vocabulary!))
  (is (nil? (ns-resolve 'ct.spools.dresser 'contribute)))
  (is (nil? (ns-resolve 'ct.spools.dresser 'reconcile))))

(defn- with-runtime [f]
  (t/with-weaver-world [ctx (fixtures/world-options)]
    (weaver-runtime/with-runtime-binding
      (:runtime ctx)
      #(do
         (fixtures/activate-workflow! (:runtime ctx))
         (fixtures/activate-serial-shell! (:runtime ctx))
         (f (:runtime ctx) (:config-dir ctx))))))

(def expected-template-names
  #{"millstrand/config.json"
    "millstrand/spools.edn"
    "millstrand/init-minimal.clj"
    "millstrand/init-layered.clj"
    "millstrand/gitignore"
    "spool-repo/deps.edn"
    "spool-repo/src-ns.clj"
    "spool-repo/test-main.clj"
    "spool-repo/gitignore"
    "spool-repo/readme"
    "spool-repo/agents.md"
    "spool-repo/cljfmt.edn"
    "spool-repo/splint.edn"
    "spool-repo/makefile"
    "spool-repo/quality.mk"
    "spool-repo/quality-aliases.edn"
    "spool-repo/docs.mk"
    "spool-repo/mkdocs-hooks.py"
    "spool-repo/mkdocs.yml"
    "spool-repo/generate-api-docs.clj"
    "spool-repo/quality.yml"
    "spool-repo/pages.yml"
    "millstrand-dir/deps.edn"
    "millstrand-dir/makefile"
    "millstrand-dir/agents.md"
    "millstrand-dir/claude.md"})

(def parameterized-template-names
  #{"spool-repo/deps.edn"
    "spool-repo/src-ns.clj"
    "spool-repo/test-main.clj"
    "spool-repo/readme"
    "spool-repo/mkdocs.yml"
    "spool-repo/generate-api-docs.clj"
    "spool-repo/quality.yml"})

(defn- thrown-data [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo exception
      (ex-data exception))))

(defn- thrown-exception [f]
  (try
    (f)
    nil
    (catch Throwable exception
      exception)))

(deftest advance-input-keys-fail-with-an-actionable-error
  (let [keywordize-input (ns-resolve 'ct.spools.dresser 'keywordize-input)
        exception (thrown-exception #(keywordize-input {42 "invalid"}))]
    (is (= "Dresser advance input keys must be keywords, strings, or symbols"
           (ex-message exception)))
    (is (= [42] (:invalid-keys (ex-data exception))))
    (is (= #{:keyword :string :symbol}
           (:allowed-key-types (ex-data exception))))))

(deftest v1-template-registry-is-complete
  (is (= expected-template-names (set (keys templates/templates)))))

(def sample-params
  "One repo's worth of every template parameter, for rendering assertions.

  Templates declare which parameters they consume, so a template rendered with
  this map may ignore most of it, but none of them can be missing."
  {:name "acme"
   :millstrand-sha "fb6c9057d594bfa4b5ea8531b9774b5e9a23a4b4"
   :repo-name "codethread/acme.spool"
   :git-branch "main"
   :site-name "acme.spool Docs"
   :site-description "Source documentation projection for the ct.spools.acme spool"})

(deftest parameterized-templates-render-declared-params
  (doseq [template-name parameterized-template-names]
    (testing template-name
      (let [content (templates/template template-name sample-params)]
        (is (str/includes? content "acme"))
        (doseq [param (keys sample-params)]
          (is (not (str/includes? content (str "<" (name param) ">"))))))))
  (is (str/includes? (templates/template "spool-repo/deps.edn" sample-params)
                     "ct.spools.acme-test"))
  (is (str/includes? (templates/template "spool-repo/mkdocs.yml" sample-params)
                     "repo_url: https://github.com/codethread/acme.spool")))

(deftest static-reference-template-is-exact
  (is (= "{\"configFormat\":\"alpha\"}\n"
         (templates/template "millstrand/config.json")))
  (is (str/includes? (templates/template "millstrand/gitignore") ".cpcache/\n")))

(def expected-template-hashes
  "SHA-256 of every template's canonical rendering, recorded before the templates
  moved out of Clojure string literals into `resources/`.

  aspects/material-data hashes these exact renderings, so a single changed byte
  here changes every release fingerprint derived from source and severs release
  lineage from its ground truth. Re-record a hash only alongside a deliberate
  aspect version and release-version bump."
  {"millstrand/config.json" "1f1d316f79607c6e45befa3bce78dfc0cf9b9736a25af794850ade05d70b8008"
   "millstrand/spools.edn" "7bf8d08965e745a5a45696c04ff23dbbdab390a8c62b64bcca540aac0d5c1196"
   "millstrand/init-minimal.clj" "d7b19c0f2868fa6fdf7fbf9d3db5a39faa90a9006eb1fb792f8b4e7f110db51e"
   "millstrand/init-layered.clj" "3ce849d14f03afe1c690b57e80cfe32f2572aec2a955b7d10df43d4b90f5b596"
   "millstrand/gitignore" "d8d19488fe52e731338acc28e1d719a7b23c170755450d813c42c245e1931f66"
   "spool-repo/deps.edn" "1e36d02d564cea64ecc254741bbb8e640a8163a6b95a3e442b12e25ee910ddc5"
   "spool-repo/src-ns.clj" "62e3d453dba6de042b886c1e569be9ca92051149e1fec5f697943a519e0ac86b"
   "spool-repo/test-main.clj" "17f97f5682347a75a725f4f82e72b472c43cf8a99820e60f1f11015d7839a6d6"
   "spool-repo/gitignore" "056f5cfa3c8446008026c3dac0e8d36c4dc5dae569d911447a611dd3b277cafa"
   "spool-repo/readme" "45febac0d02e3a960aeb7575e52f62000cccf4670c9d1c96fd3d1285d9ceffe8"
   "spool-repo/agents.md" "ce9b0f577499252e88d408ef04b2907e48d67e14a6e5f003960538136ad60be5"
   "spool-repo/cljfmt.edn" "ca9c9d6d0341cbe6cbad764ac82ac0ad306f925f145c490cfb83e2e06ef2a9c0"
   "spool-repo/splint.edn" "9e777a191b3aae2ef63d691430d4875a0cc43f2b7130874fed060e53c01bda94"
   "spool-repo/makefile" "811b289b4f701599523183f7c53df8f30184c4419483c1b17856bdbb3756b97e"
   "spool-repo/quality.mk" "23277f69afd856e58af1bc9d8710ba396b0b1304c90134cc1a4252c2f18800e1"
   "spool-repo/quality-aliases.edn" "0f6a74de4c653b713cd54095ce165b26e22583b21dc8c67ae16467efcb329781"
   "spool-repo/docs.mk" "634f00b0c7bac4bac2d759bcf929dc9bc5cb1add38f9bf5db589a0d8fc1b02ae"
   "spool-repo/mkdocs-hooks.py" "0dd1baf0db0b6227cf71c425a58608dbab6e13a49948ff3de189536e5aa69df6"
   "spool-repo/mkdocs.yml" "ed209733435b83ea5867c7c984134c2d45cefeb06603140a54482e65d208bf27"
   "spool-repo/generate-api-docs.clj" "4a6e6212eefb4a8b1293e3d71325a11824be521e900d786ccd9d29d22fb07d11"
   "spool-repo/quality.yml" "d81e587536ed9ff09c77e55af12397eda358adfda2f9df50c19001dcd3581419"
   "spool-repo/pages.yml" "fc679d78c588d239c9f30620e6b538d3f9879d7dd4fe81e3798d5432bb8ac6ae"
   "millstrand-dir/deps.edn" "510249ea4b5005a42495aab90264e30f526f958e2ad07b02055ae230f228e5a9"
   "millstrand-dir/makefile" "913ec67cf6b6b1cd100bbf75abb812ce05087afbe9bc6f16298dd18303dd9418"
   "millstrand-dir/agents.md" "6c5246e8bbb15a546bbf3b64658d7b2d42e3764d1b8c3cb67cbae5691b7e941e"
   "millstrand-dir/claude.md" "b522bb280d94c7342b986727501f88e80d2c9984e9e16d7a43f598ea2e3657a3"})

(defn- sha-256-hex [^String content]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes content StandardCharsets/UTF_8))]
    (str/join (map #(format "%02x" (bit-and % 0xff)) digest))))

(deftest template-contents-match-recorded-hashes
  (is (= (set (keys expected-template-hashes)) (set (keys templates/templates)))
      "record a hash for every template key")
  (doseq [[template-name entry] templates/templates]
    (testing template-name
      ;; Rendered the way aspects/material-data renders them, so the hash guards
      ;; exactly the bytes the release fingerprint covers.
      (is (= (get expected-template-hashes template-name)
             (sha-256-hex (if (fn? entry)
                            (entry templates/fingerprint-params)
                            entry)))))))

(deftest missing-template-resources-fail-loudly
  (let [resource-content (ns-resolve 'ct.spools.dresser.templates 'resource-content)
        missing (thrown-data #(resource-content "spool-repo/absent"))]
    (is (= "spool-repo/absent" (:template missing)))
    (is (= "ct/spools/dresser/templates/spool-repo/absent" (:resource missing)))))

(deftest template-lookups-fail-with-data
  (let [unknown (thrown-data #(templates/template "not-a-template"))
        missing (thrown-data #(templates/template "spool-repo/deps.edn"))
        partial (thrown-data #(templates/template "spool-repo/mkdocs.yml"
                                                  {:name "acme"}))
        quality-blank-name (thrown-exception #(templates/template "spool-repo/quality.yml"
                                                                  {:name " "
                                                                   :millstrand-sha
                                                                   "fb6c9057d594bfa4b5ea8531b9774b5e9a23a4b4"}))
        quality-invalid-sha (thrown-exception #(templates/template "spool-repo/quality.yml"
                                                                   {:name "acme"
                                                                    :millstrand-sha "not-a-sha"}))]
    (is (= "not-a-template" (:template unknown)))
    (is (contains? (:known unknown) "millstrand/config.json"))
    (is (= "spool-repo/deps.edn" (:template missing)))
    (is (= :name (:required missing)))
    (is (= {} (:params missing)))
    ;; A template consuming more than :name names the parameter it is short of,
    ;; rather than emitting an unsubstituted placeholder into an owned file.
    (is (= :repo-name (:required partial)))
    (is (= "Dresser template input has an invalid shape"
           (ex-message quality-blank-name)))
    (is (= "Dresser template input has an invalid shape"
           (ex-message quality-invalid-sha)))
    (is (some? (get-in (ex-data quality-invalid-sha)
                       [:explain :clojure.spec.alpha/problems])))))

(deftest template-lines-fit-review-width
  (doseq [[template-name entry] templates/templates
          :let [content (if (fn? entry) (entry sample-params) entry)]
          [line-number line] (map-indexed vector (str/split-lines content))]
    (testing (str template-name ":" (inc line-number))
      (is (<= (count line) 180)))))

(def expected-aspect-ids
  {"spool-repo/repo-skeleton"
   {:setup [:write-deps :write-src-test :write-readme :write-gitignore]
    :gates [:test-suite :readme-sections]}
   "spool-repo/millstrand-workspace"
   {:setup [:write-workspace] :gates [:workspace-files]}
   "spool-repo/agent-docs"
   {:setup [:write-agents-md] :gates [:agents-md]}
   "spool-repo/quality"
   {:setup [:write-quality-config :write-makefiles] :gates [:fmt-check :lint]}
   "spool-repo/docs"
   {:setup [:write-docs-pipeline :link-docs-projection]
    :gates [:docs-files :docs-targets :docs-check]}
   "spool-repo/ci"
   {:setup [:write-workflows]
    :checkpoints [:pages-source]
    :gates [:workflow-files :workflow-targets]}
   "millstrand-dir/workspace"
   {:setup [:write-workspace] :gates [:workspace-files :init-header]}
   "millstrand-dir/quality"
   {:setup [:write-quality-tooling] :gates [:fmt-check :lint]}
   "millstrand-dir/agent-docs"
   {:setup [:write-agent-docs] :gates [:agent-docs-files]}})

(deftest aspect-registry-is-valid
  (is (= (set (keys expected-aspect-ids)) (set (keys aspects/registry))))
  (is (= {"spool-repo/repo-skeleton" 8
          "spool-repo/millstrand-workspace" 3
          "spool-repo/agent-docs" 1
          "spool-repo/quality" 4
          "spool-repo/docs" 1
          "spool-repo/ci" 1
          "millstrand-dir/workspace" 3
          "millstrand-dir/quality" 2
          "millstrand-dir/agent-docs" 1}
         (into {} (map (fn [[key entry]] [key (:version entry)])) aspects/registry)))
  (doseq [[aspect-key entry] aspects/registry
          :let [[_ flavour aspect-name] (re-matches #"([^/]+)/([^/]+)" aspect-key)]]
    (testing aspect-key
      (is (#{"spool-repo" "millstrand-dir"} flavour))
      (is (some? aspect-name))
      (is (integer? (:version entry)))
      (is (= (get expected-aspect-ids aspect-key)
             (cond-> {:setup (mapv :id (:setup entry))
                      :gates (mapv :id (:gates entry))}
               (:checkpoints entry)
               (assoc :checkpoints (mapv :id (:checkpoints entry))))))
      (doseq [dep (:deps entry)]
        (is (contains? aspects/registry dep))
        (is (str/starts-with? dep (str flavour "/"))))
      (doseq [template-name (mapcat :templates (:setup entry))]
        (is (contains? templates/templates template-name)))
      (doseq [gate (:gates entry)]
        (is (vector? (:argv gate)))
        (is (every? string? (:argv gate)))))))

(deftest aspect-ordering-and-dependency-closure
  (let [spool-order (aspects/flavour-aspects "spool-repo")
        millstrand-order (aspects/flavour-aspects "millstrand-dir")]
    (is (< (.indexOf spool-order "spool-repo/repo-skeleton")
           (.indexOf spool-order "spool-repo/quality")))
    (is (< (.indexOf millstrand-order "millstrand-dir/workspace")
           (.indexOf millstrand-order "millstrand-dir/quality")))
    (is (< (.indexOf millstrand-order "millstrand-dir/workspace")
           (.indexOf millstrand-order "millstrand-dir/agent-docs"))))
  (is (= ["spool-repo/repo-skeleton" "spool-repo/quality"]
         (aspects/close-under-deps "spool-repo" ["spool-repo/quality"])))
  (is (= "missing" (:aspect (thrown-data
                             #(aspects/close-under-deps "spool-repo" ["missing"])))))
  (is (= "missing" (:aspect (thrown-data #(aspects/aspect "missing"))))))

(deftest material-fingerprint-is-stable-and-sensitive
  (let [topology-a (array-map :b 2 :a 1)
        topology-b (array-map :a 1 :b 2)
        baseline (aspects/fingerprint topology-a)]
    (is (= baseline (aspects/fingerprint topology-b)))
    (is (not= baseline (aspects/fingerprint {:a 1 :b 3})))
    (is (not=
         baseline
         (with-redefs [aspects/registry
                       (update-in aspects/registry
                                  ["spool-repo/repo-skeleton" :inspect]
                                  str " changed")]
           (aspects/fingerprint topology-a))))))

(defn- aspect-description [aspect-key verify-only]
  (let [params {:root "/tmp/x" :verify-only verify-only}]
    (workflow/describe (dresser-workflows/aspect-workflow aspect-key) params)))

(defn- expected-aspect-dependencies [entry]
  (let [setup-ids (mapv :id (:setup entry))
        setup-dependencies (map vector (cons :conflict setup-ids))
        checkpoint-ids (mapv :id (:checkpoints entry))
        checkpoint-dependencies (map vector
                                     (cons (or (peek setup-ids) :conflict)
                                           checkpoint-ids))
        gate-ids (mapv :id (:gates entry))
        gate-dependencies (map vector
                               (cons (or (peek checkpoint-ids)
                                         (peek setup-ids)
                                         :conflict)
                                     gate-ids))]
    (into {:inspect [] :conflict [:inspect]}
          (concat (map vector setup-ids setup-dependencies)
                  (map vector checkpoint-ids checkpoint-dependencies)
                  (map vector gate-ids gate-dependencies)))))

(defn- expected-verify-dependencies [gate-ids]
  (into {}
        (map vector gate-ids (cons [] (map vector gate-ids)))))

(deftest aspect-workflows-describe-both-modes
  (doseq [[aspect-key entry] aspects/registry]
    (testing aspect-key
      (let [setup-description (aspect-description aspect-key false)
            verify-description (aspect-description aspect-key true)
            setup-steps (:steps setup-description)
            verify-steps (:steps verify-description)
            setup-ids (mapv :id (:setup entry))
            checkpoint-ids (mapv :id (:checkpoints entry))
            gate-ids (mapv :id (:gates entry))
            expected-ids (into [:inspect :conflict]
                               (concat setup-ids checkpoint-ids gate-ids))
            dependencies (expected-aspect-dependencies entry)]
        (is (= expected-ids (mapv :id setup-steps)))
        (is (= dependencies
               (into {} (map (juxt :id :depends-on)) setup-steps)))
        (is (= "checkpoint" (:role (second setup-steps))))
        (is (= (set gate-ids)
               (into #{} (keep #(when (= "shell" (:gate %)) (:id %)))
                     setup-steps)))
        (is (= gate-ids (mapv :id verify-steps)))
        (is (= (expected-verify-dependencies gate-ids)
               (into {} (map (juxt :id :depends-on)) verify-steps)))
        (is (every? #(= "shell" (:gate %)) verify-steps))))))

(deftest conflict-checkpoint-declares-policy-inputs
  (let [checkpoint (-> (aspect-description "spool-repo/repo-skeleton" false)
                       :steps
                       second)
        choices (into {} (map (juxt :key identity)) (:choices checkpoint))]
    (is (= "conflict" (name (:id checkpoint))))
    (is (= #{"clean" "apply-plan" "abort"} (set (keys choices))))
    (is (nil? (:input-spec (choices "clean"))))
    (is (= {"spec" "ct.spools.dresser.specs/conflict-decisions-input"
            "doc" "Summary of per-file keep/merge/replace decisions."}
           (:input-spec (choices "apply-plan"))))
    (is (= ":abort" (:next (choices "abort"))))
    ;; The abort choice and the workflow it routes to answer to one spec, so a
    ;; reason the checkpoint accepts is a reason the continuation can start on.
    (is (= {"spec" "ct.spools.dresser.specs/abort-workflow-input"
            "doc" "Why convention convergence was aborted."}
           (:input-spec (choices "abort"))))))

(deftest registry-checkpoints-render-between-setup-and-gates
  (let [steps (:steps (aspect-description "spool-repo/ci" false))
        checkpoint (first (filter #(= :pages-source (:id %)) steps))
        choices (into {} (map (juxt :key identity)) (:choices checkpoint))]
    (is (= "checkpoint" (:role checkpoint)))
    (is (= [:write-workflows] (:depends-on checkpoint)))
    (is (= [:pages-source] (:depends-on (first (filter #(= :workflow-files (:id %))
                                                       steps)))))
    (is (= #{"enabled" "deferred" "abort"} (set (keys choices))))
    (is (nil? (:input-spec (choices "deferred"))))
    (is (= {"spec" "ct.spools.dresser.specs/pages-enabled-input"
            "doc" "The published site URL, e.g. https://codethread.github.io/kanban.spool/."}
           (:input-spec (choices "enabled"))))
    (is (= ":abort" (:next (choices "abort"))))
    ;; A checkpoint answers for state no gate can read, so a verify-only run —
    ;; which carries gates alone — must not ask the question again.
    (is (not (contains? (set (map :id (:steps (aspect-description "spool-repo/ci" true))))
                        :pages-source)))))

(defn- compiled-step-map [definition params]
  (let [payload (workflow/compile definition params)]
    (into {} (map (juxt :ref identity)) (rest (:strands payload)))))

(deftest aspect-workflows-compile-required-attributes
  (doseq [[aspect-key entry] aspects/registry
          :let [[flavour] (str/split aspect-key #"/" 2)
                params {:root "/tmp/x" :verify-only false}
                strands (compiled-step-map
                         (dresser-workflows/aspect-workflow aspect-key)
                         params)
                expected-common {"dresser/flavour" flavour
                                 "dresser/aspect" aspect-key
                                 "dresser/version" (:version entry)
                                 "dresser/root" "/tmp/x"}]]
    (testing aspect-key
      (doseq [{:keys [attributes]} (vals strands)]
        (is (= expected-common (select-keys attributes (keys expected-common)))))
      (is (str/includes? (get-in strands [:inspect :attributes "workflow/instruction"])
                         "Target root: /tmp/x"))
      (doseq [{:keys [id templates]} (:setup entry)]
        (let [instruction (get-in strands [id :attributes "workflow/instruction"])]
          (is (str/includes? instruction "Target root: /tmp/x"))
          (doseq [template-key templates]
            (is (str/includes? instruction template-key)))))
      (is (= "conflict-policy"
             (get-in strands [:conflict :attributes "workflow/decision-point"])))
      (doseq [{:keys [id argv timeout-secs]} (:gates entry)]
        (is (= {"dresser/gate-id" (name id)
                "workflow/gate" "shell"
                "shell/argv" argv
                "shell/cwd" "/tmp/x"
                "shell/timeout-secs" timeout-secs}
               (select-keys (get-in strands [id :attributes])
                            ["dresser/gate-id"
                             "workflow/gate"
                             "shell/argv"
                             "shell/cwd"
                             "shell/timeout-secs"])))))))

;; Umbrellas reach their aspects by registered name, so describing one needs the
;; live registry the module publishes — unlike an aspect definition, which
;; carries no calls and describes anywhere.
(deftest flavour-workflow-describes-full-and-selected-aspects
  (with-runtime
    (fn [runtime _]
      (fixtures/activate-dresser! runtime)
      (let [params {:root "/tmp/x"}
            full (workflow/describe :spool-repo params)
            subset-definition (dresser-workflows/flavour-workflow
                               "spool-repo" ["spool-repo/millstrand-workspace"])
            subset (workflow/describe subset-definition params)
            verify (workflow/describe subset-definition
                                      (assoc params :verify-only true))
            full-gates (into #{} (keep #(when (= "shell" (:gate %)) (:id %)))
                             (:steps full))
            subset-gates (into #{} (keep #(when (= "shell" (:gate %)) (:id %)))
                               (:steps subset))
            expected-full (into #{}
                                (mapcat (fn [aspect-key]
                                          (let [prefix (second (str/split aspect-key #"/" 2))]
                                            (map #(keyword (str prefix "--" (name (:id %))))
                                                 (:gates (aspects/aspect aspect-key))))))
                                (aspects/flavour-aspects "spool-repo"))]
        (is (= expected-full full-gates))
        (is (= #{:millstrand-workspace--workspace-files} subset-gates))
        (is (= #{:millstrand-workspace--workspace-files :millstrand-workspace}
               (set (map :id (:steps verify)))))
        (is (= #{:millstrand-workspace--inspect
                 :millstrand-workspace--conflict
                 :millstrand-workspace--write-workspace
                 :millstrand-workspace--workspace-files
                 :millstrand-workspace}
               (set (map :id (:steps subset)))))
        (let [payload (workflow/compile subset-definition params)
              root (first (:strands payload))
              gate (some #(when (= :millstrand-workspace--workspace-files (:ref %)) %)
                         (:strands payload))]
          (is (= {"dresser/flavour" "spool-repo"
                  "dresser/root" "/tmp/x"}
                 (select-keys (:attributes root)
                              ["dresser/flavour" "dresser/root"])))
          (is (= "/tmp/x" (get-in gate [:attributes "shell/cwd"]))))))))

(deftest topology-is-deterministic-and-release-is-pinned
  (let [topology (dresser-workflows/describe-topology)]
    (is (= (sort (keys aspects/registry)) (vec (keys topology))))
    (doseq [[_ modes] topology]
      (is (= #{:setup :verify-only} (set (keys modes))))
      (is (seq (get-in modes [:setup :steps])))
      (is (every? #(= "step" (:role %))
                  (get-in modes [:verify-only :steps]))))
    (is (= (aspects/releases aspects/release-version)
           (aspects/fingerprint topology))
        "bump the aspect version AND release-version, then re-pin releases")))

(deftest release-lineage-preserves-v1-receipt-classification
  (let [release-one "03cc25f420a0ef5b961d909205af6c0f2990819f0d858c6797a58ce1390ae498"
        registry-view {:release aspects/release-version
                       :fingerprint (aspects/releases aspects/release-version)
                       :releases aspects/releases
                       :aspects (into {} (map (fn [[key entry]]
                                                [key (:version entry)]))
                                      aspects/registry)}
        receipt {:dresser/release 1
                 :dresser/fingerprint release-one
                 :aspects {"spool-repo/repo-skeleton"
                           {:version 1 :release 1 :applied-at "2026-07-14"}
                           "spool-repo/agent-docs"
                           {:version 1 :release 1 :applied-at "2026-07-14"}}}
        classification (receipt/plan-classification receipt registry-view)]
    (is (= release-one (get aspects/releases 1)))
    (is (= :pending (get classification "spool-repo/repo-skeleton")))
    (is (= :current (get classification "spool-repo/agent-docs")))))

(defn- registered-definition [wf-name]
  (some-> (dresser-workflows/workflow-definitions wf-name)
          requiring-resolve
          deref))

(deftest dresser-workflows-declare-stable-resolvable-names
  (is (= 12 (count dresser-workflows/workflow-definitions)))
  (doseq [aspect-key (keys aspects/registry)]
    (is (contains? dresser-workflows/workflow-definitions
                   (dresser-workflows/registered-name aspect-key))
        aspect-key))
  (doseq [wf-name (keys dresser-workflows/workflow-definitions)]
    (let [definition (registered-definition wf-name)]
      (is (map? definition) (str wf-name))
      (is (seq (:steps definition)) (str wf-name))
      (is (specs/non-blank-string? (:doc definition)) (str wf-name))
      (is (qualified-keyword? (:param-spec definition)) (str wf-name)))))

(deftest dresser-workflow-entrypoints-match-how-each-name-is-reached
  ;; Umbrellas begin runs, the abort stage is only ever routed to, and an aspect
  ;; is only ever expanded inline by its umbrella.
  (is (= #{:start} (:entrypoints (registered-definition :spool-repo))))
  (is (= #{:start} (:entrypoints (registered-definition :millstrand-dir))))
  (is (= #{:continue} (:entrypoints (registered-definition :abort))))
  (doseq [aspect-key (keys aspects/registry)]
    (is (= #{:call}
           (:entrypoints (registered-definition
                          (dresser-workflows/registered-name aspect-key))))
        aspect-key)))

(defn- temp-directory ^Path []
  (Files/createTempDirectory "dresser-test-" (make-array FileAttribute 0)))

(defn- delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (io/delete-file file true)))

(defmacro with-temp-dir [[binding] & body]
  `(let [~binding (temp-directory)]
     (try
       (do ~@body)
       (finally (delete-tree! ~binding)))))

(defn- git-root! ^Path [^Path parent name]
  (let [root (Files/createDirectory (.resolve parent name)
                                    (make-array FileAttribute 0))]
    (Files/createDirectory (.resolve root ".git") (make-array FileAttribute 0))
    root))

(deftest target-root-resolution-is-canonical-and-fail-loud
  (with-temp-dir [parent]
    (let [root (git-root! parent "repo")
          link (.resolve parent "linked-repo")]
      (Files/createSymbolicLink link root (make-array FileAttribute 0))
      (is (= (str (.toRealPath root (make-array java.nio.file.LinkOption 0)))
             (target/resolve-root link))))
    (let [missing (.resolve parent "missing")
          regular-file (.resolve parent "file")
          non-git (Files/createDirectory (.resolve parent "plain")
                                         (make-array FileAttribute 0))]
      (spit (.toFile regular-file) "not a directory")
      (doseq [[path reason] [[missing :missing]
                             [regular-file :not-directory]
                             [non-git :not-git-root]]]
        (testing (name reason)
          (let [data (thrown-data #(target/resolve-root path))]
            (is (= (str path) (:path data)))
            (is (= reason (:reason data))))))
      (let [exception (thrown-exception #(target/resolve-root (str "bad" \u0000 "path")))
            data (ex-data exception)]
        (is (= :unresolvable (:reason data)))
        (is (= "java.nio.file.InvalidPathException" (:cause-type data)))
        (is (str/includes? (:cause-message data) "Nul character"))
        (is (= [:exists :directory :git-worktree-root]
               (:allowed-root-constraints data)))
        (is (instance? java.nio.file.InvalidPathException (.getCause exception)))))))

(deftest dresser-run-identities-are-stable-and-separated
  (with-temp-dir [parent]
    (let [root-a (git-root! parent "alpha")
          root-b (git-root! parent "beta")
          setup (target/run-id "spool-repo" root-a)
          verify (target/verify-run-id "spool-repo" root-a)]
      (is (= setup (target/run-id "spool-repo" root-a)))
      (is (re-matches #"dresser-spool-repo-alpha-[0-9a-f]{8}" setup))
      (is (re-matches #"dresser-verify-spool-repo-alpha-[0-9a-f]{8}" verify))
      (is (not= setup verify))
      (is (not= setup (target/run-id "millstrand-dir" root-a)))
      (is (not= setup (target/run-id "spool-repo" root-b))))))

(deftest receipt-codec-round-trips-and-rejects-invalid-data
  (with-temp-dir [root]
    (let [value {:dresser/release 1
                 :dresser/fingerprint "abc"
                 :aspects {"spool-repo/repo-skeleton"
                           {:version 1 :release 1 :applied-at "2026-07-14"}}}]
      (is (nil? (receipt/read-receipt root)))
      (is (= value (receipt/write-receipt! root value)))
      (is (= value (receipt/read-receipt root))))
    (spit (io/file (.toFile root) ".millstrand" "conventions.edn") "[")
    (is (= :invalid-edn (:reason (thrown-data #(receipt/read-receipt root)))))
    (spit (io/file (.toFile root) ".millstrand" "conventions.edn") "[]")
    (let [data (thrown-data #(receipt/read-receipt root))]
      (is (= :invalid-shape (:reason data)))
      (is (map? (:explain data))))))

(deftest ms-receipt-path-is-equivalent-to-millstrand
  (with-temp-dir [root]
    (let [value {:dresser/release 1
                 :dresser/fingerprint "alias"
                 :aspects {}}
          alias (io/file (.toFile root) ".ms")]
      (.mkdirs alias)
      (is (= value (receipt/write-receipt! root value)))
      (is (= value (receipt/read-receipt root)))
      (is (.exists (io/file alias "conventions.edn")))
      (is (not (.exists (io/file (.toFile root) ".millstrand")))))))

(deftest receipt-selection-fails-loudly-when-both-workspace-markers-exist
  (with-temp-dir [root]
    (.mkdirs (io/file (.toFile root) ".millstrand"))
    (.mkdirs (io/file (.toFile root) ".ms"))
    (doseq [operation [#(receipt/read-receipt root)
                       #(receipt/write-receipt! root {:dresser/release 1
                                                      :dresser/fingerprint "ambiguous"
                                                      :aspects {}})]]
      (let [data (thrown-data operation)]
        (is (= :ambiguous-workspace (:reason data)))
        (is (= [(str (io/file (.toFile root) ".millstrand"))
                (str (io/file (.toFile root) ".ms"))]
               (:workspace-markers data)))))))

(deftest malformed-receipt-plan-input-yields-structured-spec-error
  (let [malformed {:dresser/fingerprint "release-one"
                   :aspects {"spool-repo/a"
                             {:version 1 :release 1 :applied-at "2026-07-14"}}}
        registry {:release 1
                  :fingerprint "release-one"
                  :releases {1 "release-one"}
                  :aspects {"spool-repo/a" 1}}
        exception (thrown-exception #(receipt/plan-classification malformed registry))]
    (is (instance? clojure.lang.ExceptionInfo exception))
    (is (= malformed (:value (ex-data exception))))
    (is (map? (:explain (ex-data exception))))))

(deftest failed-atomic-receipt-move-preserves-previous-file
  (with-temp-dir [root]
    (let [previous {:dresser/release 1 :dresser/fingerprint "one" :aspects {}}
          replacement {:dresser/release 2 :dresser/fingerprint "two" :aspects {}}
          write-with-move (ns-resolve 'ct.spools.dresser.receipt
                                      'write-receipt-with-move!)]
      (receipt/write-receipt! root previous)
      (is (thrown? RuntimeException
                   (write-with-move root replacement
                                    (fn [& _]
                                      (throw (RuntimeException. "move failed"))))))
      (is (= previous (receipt/read-receipt root)))
      (is (= #{"conventions.edn"}
             (set (map #(.getName %) (.listFiles (io/file (.toFile root) ".millstrand")))))))))

(deftest merge-aspect-records-explicit-provenance
  (is (= {:dresser/release 3
          :dresser/fingerprint "feed"
          :aspects {"spool-repo/repo-skeleton"
                    {:version 2 :release 3 :applied-at "2026-07-14"}}}
         (receipt/merge-aspect nil
                               "spool-repo/repo-skeleton"
                               {:version 2}
                               3
                               "feed"
                               "2026-07-14"))))

(deftest receipt-plan-classification-matrix
  (let [registry {:release 2
                  :fingerprint "release-two"
                  :releases {1 "release-one" 2 "release-two"}
                  :aspects {"spool-repo/a" 2 "spool-repo/b" 1}}
        classify #(receipt/plan-classification % registry)
        stamped (fn [release fingerprint aspects]
                  {:dresser/release release
                   :dresser/fingerprint fingerprint
                   :aspects aspects})]
    (is (= {"spool-repo/a" :new "spool-repo/b" :new} (classify nil))
        "absent receipt")
    (is (= :pending
           (get (classify (stamped 1 "release-one"
                                   {"spool-repo/a" {:version 1
                                                    :release 1
                                                    :applied-at "2026-07-14"}}))
                "spool-repo/a"))
        "aspect version behind")
    (is (= :current
           (get (classify (stamped 1 "release-one"
                                   {"spool-repo/a" {:version 2
                                                    :release 1
                                                    :applied-at "2026-07-14"}}))
                "spool-repo/a"))
        "equal version and known matching lineage")
    (is (= :divergent
           (get (classify (stamped 1 "forked-release-one"
                                   {"spool-repo/a" {:version 2
                                                    :release 1
                                                    :applied-at "2026-07-14"}}))
                "spool-repo/a"))
        "equal version and same-integer fork")
    (is (= :divergent
           (get (classify (stamped 0 "unknown"
                                   {"spool-repo/a" {:version 2
                                                    :release 0
                                                    :applied-at "2026-07-14"}}))
                "spool-repo/a"))
        "equal version and unknown release")
    (is (= :ahead
           (get (classify (stamped 2 "release-two"
                                   {"spool-repo/a" {:version 3
                                                    :release 2
                                                    :applied-at "2026-07-14"}}))
                "spool-repo/a"))
        "aspect version ahead")
    (is (= :ahead
           (get (classify (stamped 3 "future"
                                   {"spool-repo/a" {:version 2
                                                    :release 3
                                                    :applied-at "2026-07-14"}}))
                "spool-repo/a"))
        "receipt release ahead")
    (is (= :removed
           (get (classify (stamped 2 "release-two"
                                   {"spool-repo/removed"
                                    {:version 1
                                     :release 2
                                     :applied-at "2026-07-14"}}))
                "spool-repo/removed"))
        "receipt aspect absent from registry")))

(deftest unsupported-dresser-subcommand-fails-with-alternatives
  (let [exception (thrown-exception
                   #((ns-resolve 'ct.spools.dresser 'dresser-op)
                     {:op/args {:subcommand ["explode"]}}))
        data (ex-data exception)]
    (is (= "Unsupported dresser subcommand" (ex-message exception)))
    (is (= ["explode"] (:subcommand data)))
    (is (= #{["about"] ["aspects"] ["template"] ["plan"] ["start"] ["verify"]
             ["next"] ["advance"] ["stamp"]}
           (set (:allowed data))))))

(defn- git-init-root! ^Path [^Path parent name]
  (let [root (.resolve parent name)
        {:keys [exit err]} (sh/sh "git" "init" "--quiet" (str root))]
    (when-not (zero? exit)
      (throw (ex-info "git init failed" {:root (str root) :exit exit :stderr err})))
    root))

(deftest activation-is-idempotent-and-declares-the-complete-op-surface
  (with-runtime
    (fn [runtime _]
      (let [first-activation (fixtures/activate-dresser! runtime)
            second-activation (fixtures/activate-dresser! runtime)
            op (weaver/resolve-op runtime 'dresser)
            subcommands (get-in op [:arg-spec :subcommands])
            help (weaver/op! runtime 'help ["dresser"])
            declaration (->> (vocab/declarations runtime {:kind :attr-namespace})
                             (filter #(= "dresser" (:name %)))
                             first)]
        (is (= :applied (get-in first-activation [:modules :dresser :status])))
        (is (= :unchanged (get-in second-activation [:modules :dresser :status])))
        (is (= #{"about" "aspects" "template" "plan" "start" "verify"
                 "next" "advance" "stamp"}
               (set (keys subcommands))))
        (is (= #{"about" "aspects" "template" "plan" "start" "verify"
                 "next" "advance" "stamp"}
               (set (map :name (get-in help [:node :children])))))
        (is (= {"about" :read "aspects" :read "template" :read "plan" :read
                "start" :mutating "verify" :mutating "next" :read
                "advance" :mutating "stamp" :mutating}
               (update-vals subcommands :hook-class)))
        (is (every? #(= :standard (:deadline-class %)) (vals subcommands)))
        (is (= ["dresser/flavour" "dresser/aspect" "dresser/version" "dresser/root"
                "dresser/gate-id"]
               (:keys declaration)))
        (is (= (set (keys dresser-workflows/workflow-definitions))
               (set (keys (workflow/workflows)))))))))

(deftest form-authored-modules-publish-owner-complete-partitions
  (with-runtime
    (fn [runtime _]
      (fixtures/activate-dresser! runtime)
      (is (= (set (keys dresser-workflows/workflow-definitions))
             (set (keys (workflow/workflows)))))
      (is (= 'ct.spools.dresser/dresser-op
             (:fn (weaver/resolve-op runtime 'dresser)))))))

(defn- with-runtime-without-executor [f]
  (t/with-weaver-world [ctx (fixtures/world-options)]
    (weaver-runtime/with-runtime-binding
      (:runtime ctx)
      #(do
         (fixtures/activate-workflow! (:runtime ctx))
         (f (:runtime ctx))))))

(deftest module-activation-fails-loudly-without-a-shell-executor
  (with-runtime-without-executor
    (fn [rt]
      (let [data (thrown-data #(fixtures/activate-dresser! rt))]
        (is (= :dresser (:module/key data)))))))

(deftest module-removal-by-omission-retracts-definitions-and-op
  ;; The deletion-by-omission proof: a full refresh re-collects from startup
  ;; files, where these test declarations do not appear, so the kernel retracts
  ;; each form-authored partition without a callback participating.
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "omission")]
      (with-runtime-without-executor
        (fn [runtime]
          (workflow/register-executor! :shell (constantly nil))
          (fixtures/activate-dresser! runtime)
          (weaver/op! runtime 'dresser
                      ["start" "millstrand-dir" (str root)
                       "--aspects" "millstrand-dir/agent-docs"])
          (is (= (set (keys dresser-workflows/workflow-definitions))
                 (set (keys (workflow/workflows)))))
          (let [result (runtime/refresh! runtime)]
            (is (= :removed (get-in result [:modules :dresser :status])))
            (is (= :removed (get-in result [:modules :dresser-workflows :status]))))
          (is (empty? (filter #(contains? (set (keys dresser-workflows/workflow-definitions)) %)
                              (keys (workflow/workflows)))))
          (is (= 'dresser (:operation (thrown-data
                                       #(weaver/resolve-op runtime 'dresser))))))))))

(deftest read-only-ops-return-declared-shapes
  (with-runtime
    (fn [runtime _]
      (fixtures/activate-dresser! runtime)
      (let [about (weaver/op! runtime 'dresser ["about"])
            registry (weaver/op! runtime 'dresser ["aspects"])
            rendered (weaver/op! runtime 'dresser
                                 ["template" "spool-repo/deps.edn"
                                  "--param" "name=acme"])]
        (is (= #{:receipt :plan :verify :stamp} (set (keys (:semantics about)))))
        (is (= #{"spool-repo" "millstrand-dir"} (set (keys (:flavours about)))))
        (is (seq (:quickstart about)))
        (is (= aspects/release-version (:release registry)))
        (is (= (aspects/releases aspects/release-version) (:fingerprint registry)))
        (is (= (set (keys aspects/registry)) (set (keys (:aspects registry)))))
        (is (every? #(contains? % :gates) (vals (:aspects registry))))
        (is (= {:name "acme"} (:params rendered)))
        (is (str/includes? (:content rendered) "ct.spools.acme-test"))))))

(deftest plan-op-resolves-root-and-reports-receipt-states-and-provenance
  (with-temp-dir [parent]
    (let [root (git-init-root! parent "fresh")]
      (with-runtime
        (fn [runtime _]
          (fixtures/activate-dresser! runtime)
          (let [fresh (weaver/op! runtime 'dresser ["plan" (str root)])]
            (is (= (str (.toRealPath root (make-array java.nio.file.LinkOption 0)))
                   (:root fresh)))
            (is (= (set (keys aspects/registry)) (set (keys (:aspects fresh)))))
            (is (every? #{:new} (vals (:aspects fresh))))
            (is (= :unstamped (get-in fresh [:provenance :verdict]))))
          (let [aspect-key "spool-repo/repo-skeleton"
                aspect-version (get-in aspects/registry [aspect-key :version])
                fingerprint (aspects/releases aspects/release-version)]
            (receipt/write-receipt!
             root
             {:dresser/release aspects/release-version
              :dresser/fingerprint fingerprint
              :aspects {aspect-key {:version 0
                                    :release aspects/release-version
                                    :applied-at "2026-07-14"}}})
            (is (= :pending
                   (get-in (weaver/op! runtime 'dresser ["plan" (str root)])
                           [:aspects aspect-key])))
            (receipt/write-receipt!
             root
             {:dresser/release aspects/release-version
              :dresser/fingerprint fingerprint
              :aspects {aspect-key {:version aspect-version
                                    :release aspects/release-version
                                    :applied-at "2026-07-14"}}})
            (let [known (weaver/op! runtime 'dresser ["plan" (str root)])]
              (is (= :current (get-in known [:aspects aspect-key])))
              (is (= :known (get-in known [:provenance :verdict]))))
            (receipt/write-receipt!
             root
             {:dresser/release aspects/release-version
              :dresser/fingerprint "forked"
              :aspects {aspect-key {:version aspect-version
                                    :release aspects/release-version
                                    :applied-at "2026-07-14"}}})
            (let [divergent (weaver/op! runtime 'dresser ["plan" (str root)])]
              (is (= :divergent (get-in divergent [:aspects aspect-key])))
              (is (= :divergent (get-in divergent [:provenance :verdict]))))
            (receipt/write-receipt!
             root
             {:dresser/release aspects/release-version
              :dresser/fingerprint fingerprint
              :aspects {aspect-key {:version (inc aspect-version)
                                    :release aspects/release-version
                                    :applied-at "2026-07-14"}}})
            (is (= :ahead
                   (get-in (weaver/op! runtime 'dresser ["plan" (str root)])
                           [:aspects aspect-key])))
            (receipt/write-receipt!
             root
             {:dresser/release (inc aspects/release-version)
              :dresser/fingerprint "future"
              :aspects {aspect-key {:version aspect-version
                                    :release (inc aspects/release-version)
                                    :applied-at "2026-07-14"}}})
            (let [ahead (weaver/op! runtime 'dresser ["plan" (str root)])]
              (is (= :ahead (get-in ahead [:aspects aspect-key])))
              (is (= :ahead (get-in ahead [:provenance :verdict]))))
            (receipt/write-receipt!
             root
             {:dresser/release aspects/release-version
              :dresser/fingerprint fingerprint
              :aspects {"spool-repo/removed" {:version 1
                                              :release aspects/release-version
                                              :applied-at "2026-07-14"}}})
            (is (= :removed
                   (get-in (weaver/op! runtime 'dresser ["plan" (str root)])
                           [:aspects "spool-repo/removed"])))))))))

(defn- with-runtime-without-shell [f]
  (t/with-weaver-world [ctx (fixtures/world-options)]
    (weaver-runtime/with-runtime-binding
      (:runtime ctx)
      #(do
         (fixtures/activate-workflow! (:runtime ctx))
         (workflow/register-executor! :shell (constantly nil))
         (f (:runtime ctx))))))

(deftest lifecycle-ops-address-setup-and-verify-runs
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "lifecycle")]
      (with-runtime-without-shell
        (fn [runtime]
          (fixtures/activate-dresser! runtime)
          (let [setup (weaver/op! runtime 'dresser
                                  ["start" "millstrand-dir" (str root)
                                   "--aspects" "millstrand-dir/agent-docs"])
                setup-ready (weaver/op! runtime 'dresser
                                        ["next" "millstrand-dir" (str root)])]
            (is (= (:ready setup) setup-ready))
            (is (= 1 (count setup-ready)))
            (is (str/starts-with? (:title (first setup-ready)) "Inspect "))
            (is (= :active-run
                   (let [data (thrown-data
                               #(weaver/op! runtime 'dresser
                                            ["start" "millstrand-dir" (str root)]))]
                     (when (= (target/run-id "millstrand-dir" root) (:run-id data))
                       :active-run))))
            (weaver/op! runtime 'dresser
                        ["advance" "millstrand-dir" (str root)
                         "--step" (:id (first setup-ready))])
            (let [checkpoint (first (weaver/op! runtime 'dresser
                                                ["next" "millstrand-dir" (str root)]))
                  after-choice (weaver/op! runtime 'dresser
                                           ["advance" "millstrand-dir" (str root)
                                            "--step" (:id checkpoint)
                                            "--choice" "apply-plan"
                                            "--input" "decisions=replace"])]
              (is (= "checkpoint" (:role checkpoint)))
              (is (= "Write layered workspace"
                     (get-in after-choice [:ready 0 :title]))))
            (let [verify (weaver/op! runtime 'dresser
                                     ["verify" "millstrand-dir" (str root)
                                      "--aspects" "millstrand-dir/agent-docs"])
                  verify-ready (weaver/op! runtime 'dresser
                                           ["next" "millstrand-dir" (str root) "--verify"])]
              (is (= (:ready verify) verify-ready))
              (is (= #{"shell"} (set (map :gate verify-ready))))
              (is (not= (target/run-id "millstrand-dir" root)
                        (target/verify-run-id "millstrand-dir" root))))
            (is (= "unknown"
                   (:aspect (thrown-data
                             #(weaver/op! runtime 'dresser
                                          ["verify" "millstrand-dir" (str root)
                                           "--aspects" "unknown"])))))))))))

(deftest this-repo-passes-non-recursive-self-hosting-verification
  ;; repo-skeleton would recurse through clojure -M:test. quality and
  ;; repo-skeleton are covered directly by make fmt-check lint test instead.
  (let [root (.getCanonicalPath (io/file "."))
        selected "spool-repo/millstrand-workspace,spool-repo/agent-docs"
        run-id (target/verify-run-id "spool-repo" root)]
    (fixtures/with-dresser-runtime
      (fn [runtime _]
        (weaver/op! runtime 'dresser
                    ["verify" "spool-repo" root "--aspects" selected])
        (fixtures/assert-done! (fixtures/wait-for-attention! runtime run-id))
        (let [gates (filterv #(= "shell" (spool/attr-get % :workflow/gate))
                             (fixtures/latest-molecule-strands runtime run-id))]
          (is (= #{"spool-repo/millstrand-workspace" "spool-repo/agent-docs"}
                 (set (map #(spool/attr-get % :dresser/aspect) gates))))
          (is (every? #(= "closed" (:state %)) gates))
          (is (every? #(= "shell" (spool/attr-get % :workflow/outcome-by)) gates))
          (is (every? #(zero? (spool/attr-get % :shell/exit-code)) gates))
          (is (every? #(nil? (spool/attr-get % :gate/error)) gates)))))))

(defn- evidence-workflow [aspect-key gates]
  (apply workflow/workflow
         "Poured stamp evidence fixture"
         (map (fn [{:keys [id title]}]
                (workflow/gate id title :shell
                               :attributes {"dresser/aspect" aspect-key
                                            "dresser/gate-id" (name id)
                                            "shell/argv" ["true"]
                                            "shell/cwd" "/tmp"
                                            "shell/timeout-secs" 30}))
              gates)))

(defn- violation-types [data gate-id]
  (into #{}
        (keep #(when (= gate-id (:gate %)) (:violation %)))
        (:violations data)))

(deftest stamp-refuses-missing-expected-gate
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "missing-gate")
          aspect-key "millstrand-dir/workspace"
          run-id (target/run-id "millstrand-dir" root)
          only-gate [(first (:gates (aspects/aspect aspect-key)))]]
      (with-runtime-without-shell
        (fn [_]
          (workflow/start! run-id (evidence-workflow aspect-key only-gate) {}
                           {:family "dresser"})
          (workflow/complete! run-id
                              {:by "shell"
                               :attributes {"shell/exit-code" 0}})
          (let [data (thrown-data #(dresser/stamp! aspect-key root))]
            (is (contains? (violation-types data "init-header") :missing-gate))
            (is (nil? (receipt/read-receipt root)))))))))

(deftest stamp-refuses-gate-id-mismatch-even-when-title-matches
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "wrong-gate-id")
          aspect-key "millstrand-dir/agent-docs"
          run-id (target/run-id "millstrand-dir" root)
          expected-gate (first (:gates (aspects/aspect aspect-key)))
          wrong-gate [(assoc expected-gate :id :old-agent-docs-files)]]
      (with-runtime-without-shell
        (fn [_]
          (workflow/start! run-id (evidence-workflow aspect-key wrong-gate) {}
                           {:family "dresser"})
          (workflow/complete! run-id
                              {:by "shell"
                               :attributes {"shell/exit-code" 0}})
          (let [data (thrown-data #(dresser/stamp! aspect-key root))]
            (is (contains? (violation-types data "agent-docs-files") :missing-gate))
            (is (contains? (violation-types data "old-agent-docs-files")
                           :unexpected-gate))
            (is (nil? (receipt/read-receipt root)))))))))

(deftest stamp-without-setup-history-is-structured-evidence-failure
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "no-history")
          aspect-key "millstrand-dir/agent-docs"]
      (with-runtime-without-shell
        (fn [_]
          (let [exception (thrown-exception #(dresser/stamp! aspect-key root))
                data (ex-data exception)]
            (is (str/includes? (ex-message exception) "stamp evidence failed"))
            (is (nil? (:molecule data)))
            (is (= [{:violation :missing-molecule
                     :run-id (target/run-id "millstrand-dir" root)}]
                   (:violations data)))
            (is (nil? (receipt/read-receipt root)))))))))

(deftest stamp-refuses-force-closed-gate
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "human-gate")
          aspect-key "millstrand-dir/agent-docs"
          run-id (target/run-id "millstrand-dir" root)
          gates (:gates (aspects/aspect aspect-key))]
      (with-runtime-without-shell
        (fn [_]
          (workflow/start! run-id (evidence-workflow aspect-key gates) {}
                           {:family "dresser"})
          (workflow/complete! run-id
                              {:by "human"
                               :attributes {"shell/exit-code" 0}})
          (let [data (thrown-data #(dresser/stamp! aspect-key root))]
            (is (contains? (violation-types data "agent-docs-files") :outcome-by))
            (is (= "human"
                   (:actual (some #(when (= :outcome-by (:violation %)) %)
                                  (:violations data)))))))))))

(deftest millstrand-dir-e2e-stamps-all-aspects-without-touching-host-tree
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "millstrand-dir")]
      (spit (.toFile (.resolve root "HOST.txt")) "host-owned\n")
      (let [before (fixtures/snapshot-outside-millstrand root)]
        (with-runtime
          (fn [runtime _]
            (fixtures/activate-dresser! runtime)
            (weaver/op! runtime 'dresser ["start" "millstrand-dir" (str root)])
            (let [state (fixtures/drive-millstrand-dir! runtime root)]
              (is (= :done (:reason state)) (pr-str state)))
            (doseq [aspect-key (aspects/flavour-aspects "millstrand-dir")]
              (let [result (weaver/op! runtime 'dresser
                                       ["stamp" aspect-key (str root)])]
                (is (= aspect-key (:aspect result)))
                (is (= :current (:plan result)))
                (is (re-matches #"\d{4}-\d{2}-\d{2}"
                                (get-in result [:entry :applied-at])))))
            (let [stamp (receipt/read-receipt root)
                  planned (weaver/op! runtime 'dresser ["plan" (str root)])]
              (is (= aspects/release-version (:dresser/release stamp)))
              (is (= (aspects/releases aspects/release-version)
                     (:dresser/fingerprint stamp)))
              (is (= (set (aspects/flavour-aspects "millstrand-dir"))
                     (set (keys (:aspects stamp)))))
              (is (every? #{:current}
                          (map (:aspects planned)
                               (aspects/flavour-aspects "millstrand-dir")))))))
        (is (= before (fixtures/snapshot-outside-millstrand root)))))))

(deftest red-gate-recovery-refuses-old-green-evidence-then-stamps
  (fixtures/with-temp-dir [parent]
    (let [root (fixtures/git-init-root! parent "recovery")
          aspect-key "millstrand-dir/workspace"]
      (with-runtime
        (fn [runtime _]
          (fixtures/activate-dresser! runtime)
          (weaver/op! runtime 'dresser
                      ["start" "millstrand-dir" (str root)
                       "--aspects" aspect-key])
          (let [state (fixtures/drive-millstrand-dir! runtime root)]
            (is (= :done (:reason state)) (pr-str state)))
          (is (= :current (:plan (dresser/stamp! aspect-key root))))
          (weaver/op! runtime 'dresser
                      ["start" "millstrand-dir" (str root)
                       "--aspects" aspect-key])
          (let [state (fixtures/drive-millstrand-dir!
                       runtime root
                       {:before-advance
                        (fn [step]
                          (when (= "Write layered workspace" (:title step))
                            (spit (io/file (str root) ".millstrand" "init.clj")
                                  ";; deliberately broken\n")))})
                failed-gate (:gate state)
                refusal (thrown-data #(dresser/stamp! aspect-key root))]
            (is (= :stalled (:reason state)))
            (is (= "Check init header" (:title failed-gate)))
            (is (some? (spool/attr-get failed-gate :gate/error)))
            (is (contains? (violation-types refusal "init-header") :gate-error))
            (fixtures/write-step-files! root "Write layered workspace")
            ;; The executor re-arms on gate/error absence, so the clear is a
            ;; nil-patch removal, not a blank overwrite.
            (weaver/update! runtime (:id failed-gate)
                            {:attributes {"gate/error" nil}})
            (is (= :done (:reason (fixtures/drive-millstrand-dir! runtime root))))
            (is (= :current (:plan (dresser/stamp! aspect-key root))))))))))

(defn -main
  "Run the standalone dresser.spool test suite."
  [& _args]
  (let [summary (run-tests 'ct.spools.dresser-test
                           'ct.spools.dresser-edges-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
