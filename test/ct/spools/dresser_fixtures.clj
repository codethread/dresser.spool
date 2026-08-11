(ns ct.spools.dresser-fixtures
  "Filesystem and workflow-driving helpers for dresser's disposable-world e2e tests."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.test :refer [is]]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :as spool]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [ct.spools.dresser :as dresser]
            [ct.spools.dresser.receipt :as receipt]
            [ct.spools.dresser.target :as target]
            [ct.spools.dresser.templates :as templates]
            [millhouse.spools.executors.shell :as shell-executor]
            [millhouse.spools.workflow :as workflow]
            [millstrand.test.alpha :as t])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.time LocalDate)))

(def ^:private max-driver-steps 64)
(def ^:private attention-timeout-ms 30000)

(defn world-options
  "Return disposable-world options that approve this checkout as a spool root."
  []
  {:storage :sqlite-memory
   :spools-edn {:spools {'codethread/dresser
                         {:local/root (.getCanonicalPath (io/file "."))}
                         'io.millstrand/millstrand
                         {:local/root (.getCanonicalPath (io/file "../millstrand"))}
                         'millhouse.spools/workflow
                         {:local/root (.getCanonicalPath (io/file "../millhouse.spool/spools/workflow"))}
                         'millhouse.spools.executors/shell
                         {:local/root (.getCanonicalPath (io/file "../millhouse.spool/spools/shell-executor"))}}}})

(defn activate-module!
  "Activate a form-authored spool module on a bare test runtime from the JVM
  source target. Production carries `:spools` guards instead. Throws with the full
  refresh result unless the module's outcome is applied or unchanged, so a
  fixture failure names the refusal instead of cascading into unrelated
  assertions. Returns the refresh result."
  [rt key ns-sym & {:keys [after spools]}]
  (let [result (runtime/module! rt key (cond-> {:ns ns-sym}
                                         after (assoc :after after)
                                         spools (assoc :spools spools)))
        status (get-in result [:modules key :status])]
    (when-not (contains? #{:applied :unchanged} status)
      (throw (ex-info "Spool module activation failed"
                      {:module/key key :module/status status :result result})))
    result))

(defn activate-workflow!
  "Activate the workflow spool module on a bare test runtime."
  [rt]
  (activate-module! rt :workflow 'millhouse.spools.workflow
                    :spools ['millhouse.spools/workflow]))

(defn activate-serial-shell!
  "Activate the real shell executor module."
  [rt]
  (activate-module! rt :shell 'millhouse.spools.executors.shell
                    :after [:workflow]
                    :spools ['millhouse.spools.executors/shell
                             'millhouse.spools/workflow]))

(defn activate-dresser-workflows!
  "Activate Dresser's public workflow-definition module."
  [rt]
  (activate-module! rt :dresser-workflows 'dresser
                    :after [:workflow]
                    :spools ['codethread/dresser]))

(defn activate-dresser!
  "Activate Dresser's CLI and vocabulary module after its definitions."
  [rt]
  (activate-dresser-workflows! rt)
  (activate-module! rt :dresser 'ct.spools.dresser
                    :after [:workflow :dresser-workflows]
                    :spools ['codethread/dresser]))

(defn with-dresser-runtime
  "Run f in a disposable weaver world with dresser and either real or inert shell.

  f receives runtime and config-dir."
  ([f]
   (with-dresser-runtime {:real-shell? true} f))
  ([{:keys [real-shell?] :or {real-shell? true}} f]
   (t/with-weaver-world [ctx (world-options)]
     (weaver-runtime/with-runtime-binding
       (:runtime ctx)
       #(let [rt (:runtime ctx)]
          (activate-workflow! rt)
          (if real-shell?
            (activate-serial-shell! rt)
            (workflow/register-executor! :shell (constantly nil)))
          (activate-dresser! rt)
          (binding [dresser/*current-date* (LocalDate/of 2026 7 14)]
            (f rt (:config-dir ctx))))))))

(defn temp-directory ^Path []
  (Files/createTempDirectory "dresser-e2e-" (make-array FileAttribute 0)))

(defn delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (io/delete-file file true)))

(defmacro with-temp-dir [[binding] & body]
  `(let [~binding (temp-directory)]
     (try
       (do ~@body)
       (finally (delete-tree! ~binding)))))

(defn git-init-root! ^Path [^Path parent name]
  (let [root (.resolve parent name)
        {:keys [exit err]} (sh/sh "git" "init" "--quiet" (str root))]
    (when-not (zero? exit)
      (throw (ex-info "git init failed" {:root (str root) :exit exit :stderr err})))
    root))

(defn- write-content! [root relative content]
  (let [file (io/file (str root) relative)]
    (io/make-parents file)
    (spit file content)))

(defn- write-template!
  ([root relative template-key]
   (write-template! root relative template-key nil))
  ([root relative template-key params]
   (write-content! root relative (templates/template template-key (or params {})))))

(defn- fixture-name [root]
  (-> (io/file (str root)) .getName (str/replace #"[^A-Za-z0-9_-]" "-")))

(defn- sibling-millstrand-root []
  (.getCanonicalPath (io/file "../millstrand")))

(defn- spool-repo-deps [root]
  (-> (templates/template "spool-repo/deps.edn" {:name (fixture-name root)})
      (str/replace "../millstrand" (sibling-millstrand-root))))

(defn- docs-params
  "One plausible repo's docs parameters, the way a driving agent would fill them."
  [name]
  {:name name
   :repo-name (str "codethread/" name ".spool")
   :git-branch "main"
   :site-name (str name ".spool Docs")
   :site-description (str "Source documentation projection for the ct.spools." name " spool")})

(defn- symlink!
  "Project a repo file into the docs collection the way the setup step asks.

  The generated API page does not exist until the gate's `make api-docs` runs,
  so this link is deliberately allowed to dangle until then. Seeding and then
  driving a run writes every step's files twice, so replace rather than create."
  [root link target]
  (let [file (io/file (str root) link)
        path (.toPath file)]
    (io/make-parents file)
    (Files/deleteIfExists path)
    (Files/createSymbolicLink path
                              (.toPath (io/file target))
                              (make-array FileAttribute 0))))

(defn- merge-quality-aliases! [root]
  (let [deps-file (io/file (str root) "deps.edn")
        deps (edn/read-string (slurp deps-file))
        quality (edn/read-string (templates/template "spool-repo/quality-aliases.edn"))]
    (with-open [writer (io/writer deps-file)]
      (pp/pprint (update deps :aliases merge quality) writer))))

(defn write-spool-repo-step-files!
  "Write the spool-repo files owned by one setup step from canonical templates."
  [root title]
  (let [name (fixture-name root)
        params {:name name
                :millstrand-sha "fb6c9057d594bfa4b5ea8531b9774b5e9a23a4b4"}
        ns-path (str/replace name "-" "_")]
    (case title
      "Write deps.edn"
      (write-content! root "deps.edn" (spool-repo-deps root))

      "Write source and test"
      (do
        (write-template! root (str "src/ct/spools/" ns-path ".clj")
                         "spool-repo/src-ns.clj" params)
        (write-template! root (str "test/ct/spools/" ns-path "_test.clj")
                         "spool-repo/test-main.clj" params))

      "Write README"
      (write-template! root "README.md" "spool-repo/readme" params)

      "Write gitignore"
      (write-template! root ".gitignore" "spool-repo/gitignore")

      "Write Millstrand workspace"
      (doseq [[relative template-key]
              [[".millstrand/config.json" "millstrand/config.json"]
               [".millstrand/spools.edn" "millstrand/spools.edn"]
               [".millstrand/init.clj" "millstrand/init-minimal.clj"]
               [".millstrand/.gitignore" "millstrand/gitignore"]]]
        (write-template! root relative template-key))

      "Write AGENTS.md"
      (write-template! root "AGENTS.md" "spool-repo/agents.md")

      "Write quality config"
      (do
        (write-template! root ".cljfmt.edn" "spool-repo/cljfmt.edn")
        (write-template! root ".splint.edn" "spool-repo/splint.edn")
        (merge-quality-aliases! root))

      "Write Makefile fragments"
      (doseq [[relative template-key]
              [["Makefile" "spool-repo/makefile"]
               ["make/quality.mk" "spool-repo/quality.mk"]]]
        (write-template! root relative template-key))

      "Write docs pipeline"
      (doseq [[relative template-key]
              [["mkdocs.yml" "spool-repo/mkdocs.yml"]
               ["scripts/mkdocs_hooks.py" "spool-repo/mkdocs-hooks.py"]
               ["scripts/generate_api_docs.clj" "spool-repo/generate-api-docs.clj"]
               ["make/docs.mk" "spool-repo/docs.mk"]]]
        (write-template! root relative template-key (docs-params name)))

      "Link docs projection"
      (doseq [[link target] [[".mkdocs/index.md" "../README.md"]
                             [(str ".mkdocs/" name ".api.md")
                              (str "../" name ".api.md")]]]
        (symlink! root link target))

      "Write CI workflows"
      (doseq [[relative template-key]
              [[".github/workflows/quality.yml" "spool-repo/quality.yml"]
               [".github/workflows/pages.yml" "spool-repo/pages.yml"]]]
        (write-template! root relative template-key params))

      nil)))

(defn seed-spool-repo!
  "Seed a scaffold-shaped spool repo whose real registry gates can run."
  [root]
  (doseq [title ["Write deps.edn"
                 "Write source and test"
                 "Write README"
                 "Write gitignore"
                 "Write Millstrand workspace"
                 "Write AGENTS.md"
                 "Write quality config"
                 "Write Makefile fragments"
                 "Write docs pipeline"
                 "Link docs projection"
                 "Write CI workflows"]]
    (write-spool-repo-step-files! root title))
  root)

(defn write-step-files!
  "Write the millstrand-dir files owned by one setup step from canonical templates."
  [root title]
  (case title
    "Write layered workspace"
    (doseq [[relative template-key]
            [[".millstrand/config.json" "millstrand/config.json"]
             [".millstrand/spools.edn" "millstrand/spools.edn"]
             [".millstrand/init.clj" "millstrand/init-layered.clj"]
             [".millstrand/.gitignore" "millstrand/gitignore"]]]
      (write-template! root relative template-key))

    "Write quality tooling"
    (doseq [[relative template-key]
            [[".millstrand/deps.edn" "millstrand-dir/deps.edn"]
             [".millstrand/Makefile" "millstrand-dir/makefile"]]]
      (write-template! root relative template-key))

    "Write agent docs"
    (doseq [[relative template-key]
            [[".millstrand/AGENTS.md" "millstrand-dir/agents.md"]
             [".millstrand/CLAUDE.md" "millstrand-dir/claude.md"]]]
      (write-template! root relative template-key))

    nil))

(defn snapshot-outside-millstrand
  "Snapshot every host-tree path and file byte outside .millstrand/."
  [root]
  (let [root-path (.toPath (io/file (str root)))]
    (into (sorted-map)
          (for [file (file-seq (.toFile root-path))
                :let [path (.toPath file)
                      relative (str (.relativize root-path path))]
                :when (and (not (str/blank? relative))
                           (not (or (= relative ".millstrand")
                                    (str/starts-with? relative (str ".millstrand" java.io.File/separator)))))]
            [relative
             (if (.isDirectory file)
               :directory
               (vec (Files/readAllBytes path)))]))))

(defn- attention [runtime run-id]
  (shell-executor/scan!)
  (let [ready (workflow/ready run-id)
        strands (mapv #(weaver/show runtime (:id %)) ready)
        stalled (some #(when (spool/attr-get % :gate/error) %) strands)
        driver-ready (filterv #(not= "shell" (:gate %)) ready)]
    (cond
      (workflow/done? run-id) {:reason :done :ready ready}
      stalled {:reason :stalled :ready ready :gate stalled}
      (seq driver-ready) {:reason :driver :ready driver-ready}
      :else nil)))

(defn wait-for-attention!
  "Poll until a run is done, stalled, or needs its driving agent."
  [runtime run-id]
  (spool/poll-until!
   (runtime/clock runtime)
   {:timeout-ms attention-timeout-ms
    :poll-ms 25
    :check #(do
              (current/with-runtime runtime
                ((ns-resolve 'millhouse.spools.executors.shell 'scan!)))
              (attention runtime run-id))
    :pred->result identity
    :on-timeout (fn [_]
                  (throw (ex-info "Timed out waiting for dresser run"
                                  {:run-id run-id
                                   :ready (workflow/ready run-id)})))}))

(defn- require-driver-budget! [run-id driven]
  (when (>= driven max-driver-steps)
    (throw (ex-info "Dresser fixture exceeded its deterministic driver-step budget"
                    {:run-id run-id
                     :driven driven
                     :max-driver-steps max-driver-steps
                     :ready (workflow/ready run-id)}))))

(defn drive-millstrand-dir!
  "Drive all agent-owned work, letting the real shell executor own gates.

  before-advance runs after setup templates are written and before that setup
  step closes, allowing a test to introduce a deliberate gate failure."
  ([runtime root]
   (drive-millstrand-dir! runtime root {}))
  ([runtime root {:keys [before-advance]}]
   (let [run-id (target/run-id "millstrand-dir" root)]
     (loop [driven 0]
       (let [{:keys [reason ready] :as state} (wait-for-attention! runtime run-id)]
         (case reason
           :done state
           :stalled state
           :driver
           (let [_ (require-driver-budget! run-id driven)
                 step (first ready)
                 base ["advance" "millstrand-dir" (str root)]]
             (if (= "checkpoint" (:role step))
               (weaver/op! runtime 'dresser (conj base "--choice" "clean"))
               (do
                 (when-not (str/starts-with? (:title step) "Inspect ")
                   (write-step-files! root (:title step)))
                 (when before-advance (before-advance step))
                 (weaver/op! runtime 'dresser base)))
             (recur (inc driven)))))))))

(defn- checkpoint-choice
  "The choice this fixture answers a ready checkpoint with.

  A fixture repo is a throwaway temp directory with no GitHub remote, so the
  Pages checkpoint is answered honestly: the workflow files are converged and
  Pages is knowingly off. Every other checkpoint is the owned-file conflict one,
  which a freshly templated tree answers clean."
  [step]
  (if (str/starts-with? (:title step) "Enable GitHub Actions")
    "deferred"
    "clean"))

(defn drive-spool-repo!
  "Drive all spool-repo agent work, leaving gates to the real shell executor."
  [runtime root]
  (let [run-id (target/run-id "spool-repo" root)]
    (loop [driven 0]
      (let [{:keys [reason ready] :as state} (wait-for-attention! runtime run-id)]
        (case reason
          :done state
          :stalled state
          :driver
          (let [_ (require-driver-budget! run-id driven)
                step (first ready)
                base ["advance" "spool-repo" (str root)]]
            (if (= "checkpoint" (:role step))
              (weaver/op! runtime 'dresser
                          (conj base "--choice" (checkpoint-choice step)))
              (do
                (when-not (str/starts-with? (:title step) "Inspect ")
                  (write-spool-repo-step-files! root (:title step)))
                (weaver/op! runtime 'dresser base)))
            (recur (inc driven))))))))

(defn latest-molecule-strands
  "Return every strand under run-id's latest molecule."
  [runtime run-id]
  (let [root-id (or (:id (workflow/current-root run-id))
                    (get-in (peek (workflow/run-history run-id)) [:root :id]))]
    (:strands (graph/subgraph runtime [root-id]))))

(defn poured-aspects
  "Return dresser aspect keys present in run-id's latest molecule."
  [runtime run-id]
  (into #{} (keep #(spool/attr-get % :dresser/aspect))
        (latest-molecule-strands runtime run-id)))

(defn all-run-strands
  "Return strands from every retained molecule for run-id."
  [runtime run-id]
  (mapcat (fn [molecule]
            (:strands (graph/subgraph runtime [(get-in molecule [:root :id])])))
          (workflow/run-history run-id)))

(defn capture-exception
  "Return an ExceptionInfo thrown by f, or nil."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo exception
      exception)))

(defn assert-done!
  "Assert a driver state completed and include its diagnostic shape on failure."
  [state]
  (is (= :done (:reason state)) (pr-str state)))

(defn stamp-and-assert-current!
  "Stamp every aspect and assert both stamp and plan report current."
  [root aspect-keys]
  (doseq [aspect-key aspect-keys]
    (let [result (dresser/stamp! aspect-key root)]
      (is (= aspect-key (:aspect result)))
      (is (= :current (:plan result)))))
  (let [stamp (receipt/read-receipt root)
        planned (dresser/plan root)]
    (is (= (set aspect-keys) (set (keys (:aspects stamp)))))
    (is (every? #{:current} (map (:aspects planned) aspect-keys)))
    stamp))
