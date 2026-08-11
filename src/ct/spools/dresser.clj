(ns ct.spools.dresser
  "Convention-convergence spool: versioned per-aspect setup/verify workflows
  driven from an operator weaver world against a target repo path."
  (:require [clojure.string :as str]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as fmt]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spool.alpha :as spool]
            [millstrand.api.vocab.alpha :as vocab]
            [ct.spools.dresser.aspects :as aspects]
            [ct.spools.dresser.receipt :as receipt]
            [ct.spools.dresser.specs :as specs]
            [ct.spools.dresser.target :as target]
            [ct.spools.dresser.templates :as templates]
            [ct.spools.dresser.workflows :as dresser-workflows]
            [millhouse.spools.workflow :as workflow])
  (:import (java.time LocalDate)))

(def ^:dynamic *current-date*
  "Injectable date used by receipt stamping; nil selects the system date."
  nil)

(defn- current-fingerprint []
  (get aspects/releases aspects/release-version))

(defn- aspect-projection []
  (into (sorted-map)
        (map (fn [[aspect-key {:keys [version deps gates]}]]
               (spool/require-valid! ::specs/registry-entry
                                     (aspects/aspect aspect-key)
                                     "Dresser registry entry has an invalid shape")
               [aspect-key
                {:version version
                 :deps (vec deps)
                 :gates (mapv (comp name :id) gates)}]))
        aspects/registry))

(defn about
  "Return dresser's authored semantic discovery document."
  []
  {:purpose
   (fmt/reflow
    "|Converge repositories on versioned formatting, linting, test, agent-guidance,
     |and Millstrand-workspace conventions through inspectable, verifiable workflows.")
   :flavours
   (into (sorted-map)
         (for [flavour ["spool-repo" "millstrand-dir"]]
           [flavour {:aspects (aspects/flavour-aspects flavour)}]))
   :semantics
   {:receipt
    (fmt/reflow
     "|The checked-in .millstrand/conventions.edn receipt records green verification,
      |aspect versions, release lineage, and a release fingerprint; it does not prove
      |that files have not drifted since stamping.")
    :plan
    (fmt/reflow
     "|Plan compares the receipt with the live pinned registry and reports every
      |aspect as new, pending, current, divergent, ahead, or removed.")
    :verify
    (fmt/reflow
     "|Verify runs the registry's mechanical gates against the target without
      |running inspect, conflict, or setup steps.")
    :stamp
    (fmt/reflow
     "|Stamp advances one receipt aspect only after the latest setup molecule's
      |complete expected gate set has shell-recorded success evidence.")}
   :quickstart
   (fmt/fill
    "|Run `git init` first when scaffolding a new target; every target must already
     |be a git worktree root.
     |
     |Inspect `strand dresser aspects`, then use `strand dresser plan <root>` to
     |compare a target receipt with this release.
     |
     |Use `strand help dresser` for the installed command and argument surface.")})

(defn aspects-view
  "Return the versioned registry projection exposed by `dresser aspects`."
  []
  (let [view {:release aspects/release-version
              :fingerprint (current-fingerprint)
              :releases (into (sorted-map) aspects/releases)
              :aspects (aspect-projection)}]
    (spool/require-valid! ::specs/registry-view view
                          "Dresser registry view has an invalid shape")
    view))

(defn template-view
  "Return one canonical template, rendering any supplied params."
  [name params]
  (let [params (or params {})]
    (spool/require-valid! ::specs/template-input
                          {:name name :params params}
                          "Dresser template input has an invalid shape")
    (let [params (into {}
                       (map (fn [[key value]] [(keyword (clojure.core/name key)) value]))
                       params)]
      {:template name
       :params params
       :content (templates/template name params)})))

(defn- provenance-view [stamp]
  (let [receipt-release (:dresser/release stamp)
        receipt-fingerprint (:dresser/fingerprint stamp)
        lineage-fingerprint (get aspects/releases receipt-release)
        verdict (cond
                  (nil? stamp) :unstamped
                  (and (integer? receipt-release)
                       (> receipt-release aspects/release-version)) :ahead
                  (= receipt-fingerprint lineage-fingerprint) :known
                  :else :divergent)]
    {:verdict verdict
     :receipt-release receipt-release
     :receipt-fingerprint receipt-fingerprint
     :lineage-fingerprint lineage-fingerprint}))

(defn plan
  "Resolve root and compare its receipt with the live versioned registry."
  [root]
  (spool/require-valid! ::specs/root root "Dresser plan root has an invalid shape")
  (let [root (target/resolve-root root)
        stamp (receipt/read-receipt root)
        registry-view {:release aspects/release-version
                       :fingerprint (current-fingerprint)
                       :releases aspects/releases
                       :aspects (into (sorted-map)
                                      (map (fn [[key entry]] [key (:version entry)]))
                                      aspects/registry)}]
    {:root root
     :release aspects/release-version
     :fingerprint (current-fingerprint)
     :aspects (receipt/plan-classification stamp registry-view)
     :provenance (provenance-view stamp)}))

(defn- selected-aspects [flavour selection]
  (if (some? selection)
    (let [keys (mapv str/trim (str/split selection #"," -1))]
      (when (some str/blank? keys)
        (spool/fail! "Dresser --aspects must be a comma-separated list of aspect keys"
                     {:flavour flavour :aspects selection}))
      (aspects/close-under-deps flavour keys))
    (aspects/flavour-aspects flavour)))

(defn- start-target
  "Return what a start pours for `selected`: the registered flavour name, or the
  narrower umbrella definition an aspect selection needs.

  A static definition's `call` set is fixed where it is authored, so a subset of
  a flavour is a definition the registry does not hold. Building one here and
  pouring the value is trusted Clojure past the registry boundary (TEN-002); a
  full-flavour run — every run without `--aspects` — stays a registered-name
  start and records the definition name it resolved."
  [flavour selected]
  (if (= selected (aspects/flavour-aspects flavour))
    (keyword flavour)
    (dresser-workflows/flavour-workflow flavour selected)))

(defn- start-run! [flavour root verify-only selection]
  (spool/require-valid! ::specs/start-input
                        {:flavour flavour
                         :root root
                         :verify-only verify-only
                         :selection selection}
                        "Dresser lifecycle start input has an invalid shape")
  (let [root (target/resolve-root root)
        selected (selected-aspects flavour selection)
        run-id ((if verify-only target/verify-run-id target/run-id) flavour root)
        params {:root root :verify-only verify-only}]
    (workflow/start! run-id
                     (start-target flavour selected)
                     params
                     {:family "dresser"})))

(defn start
  "Start a setup run for flavour and root."
  [flavour root selection]
  (start-run! flavour root false selection))

(defn verify
  "Start a verify-only run for flavour and root."
  [flavour root selection]
  (start-run! flavour root true selection))

(defn- addressed-run-id [flavour root verify?]
  (let [root (target/resolve-root root)]
    ((if verify? target/verify-run-id target/run-id) flavour root)))

(defn ready
  "Return `millhouse.spools.workflow/ready`'s engine-owned frontier unchanged for a
  setup run, or verify run when verify? is true."
  [flavour root verify?]
  (spool/require-valid! ::specs/ready-input
                        {:flavour flavour :root root :verify verify?}
                        "Dresser lifecycle ready input has an invalid shape")
  (workflow/ready (addressed-run-id flavour root verify?)))

(defn advance!
  "Advance a setup run, or verify run when verify? is true."
  [flavour root verify? opts]
  (spool/require-valid! ::specs/advance-input
                        {:flavour flavour :root root :verify verify? :opts opts}
                        "Dresser lifecycle advance input has an invalid shape")
  (spool/reject-unknown-keys! "dresser advance"
                              #{:choice :input :step}
                              opts)
  ;; The engine publishes a caller-supplied :by, but dresser deliberately pins it
  ;; and exposes no --by. `advance!` acts on any ready step, gates included, so a
  ;; caller-chosen :by would let `advance --step <gate-id> --by shell` forge the
  ;; workflow/outcome-by = "shell" evidence stamp! trusts.
  (workflow/advance! (addressed-run-id flavour root verify?)
                     (assoc opts :by "dresser")))

(defn- keywordize-input [input]
  (let [input (or input {})
        invalid-keys (vec (remove #(or (keyword? %)
                                       (string? %)
                                       (symbol? %))
                                  (keys input)))]
    (when (seq invalid-keys)
      (spool/fail! "Dresser advance input keys must be keywords, strings, or symbols"
                   {:invalid-keys invalid-keys
                    :allowed-key-types #{:keyword :string :symbol}}))
    (into {}
          (map (fn [[key value]] [(keyword (name key)) value]))
          input)))

(defn- attr [strand key]
  (spool/attr-get strand key))

(defn- expected-gates [aspect-key]
  (into (sorted-map)
        (map (fn [{:keys [id title]}] [(name id) title]))
        (:gates (aspects/aspect aspect-key))))

(defn- latest-molecule-gates [run-id aspect-key]
  (let [active-root (workflow/current-root run-id)
        history (when-not active-root
                  (try
                    (workflow/run-history run-id)
                    (catch clojure.lang.ExceptionInfo exception
                      (if (and (= "Unknown workflow run" (ex-message exception))
                               (= run-id (:run-id (ex-data exception))))
                        []
                        (throw exception)))))
        root-id (or (:id active-root) (get-in (peek history) [:root :id]))]
    (if-not root-id
      {:root-id nil :gates []}
      (let [strands (:strands (graph/subgraph (current/runtime) [root-id]))]
        {:root-id root-id
         :gates (filterv #(and (= aspect-key (attr % :dresser/aspect))
                               (= "shell" (attr % :workflow/gate)))
                         strands)}))))

(defn- evidence-violations [expected gates]
  (let [identified (group-by #(attr % :dresser/gate-id) gates)
        missing (remove #(contains? identified %) (keys expected))
        unexpected (remove #(contains? expected (attr % :dresser/gate-id)) gates)]
    (vec
     (concat
      (map (fn [gate-id] {:violation :missing-gate :gate gate-id}) missing)
      (map (fn [gate]
             {:violation :unexpected-gate
              :gate (attr gate :dresser/gate-id)
              :strand-id (:id gate)
              :title (:title gate)})
           unexpected)
      (mapcat
       (fn [[gate-id expected-title]]
         (let [matches (get identified gate-id)]
           (concat
            (when (> (count matches) 1)
              [{:violation :duplicate-gate
                :gate gate-id
                :strand-ids (mapv :id matches)}])
            (mapcat
             (fn [gate]
               (cond-> []
                 (not= expected-title (:title gate))
                 (conj {:violation :gate-title
                        :gate gate-id
                        :actual (:title gate)
                        :expected expected-title})

                 (not= "closed" (:state gate))
                 (conj {:violation :gate-not-closed :gate gate-id :state (:state gate)})

                 (not= "shell" (attr gate :workflow/outcome-by))
                 (conj {:violation :outcome-by
                        :gate gate-id
                        :actual (attr gate :workflow/outcome-by)
                        :expected "shell"})

                 (not= 0 (attr gate :shell/exit-code))
                 (conj {:violation :exit-code
                        :gate gate-id
                        :actual (attr gate :shell/exit-code)
                        :expected 0})

                 ;; Executors clear by removing the key; a legacy blank left by
                 ;; a hand-clear still reads as cleared, not as a failure.
                 (specs/non-blank-string? (attr gate :gate/error))
                 (conj {:violation :gate-error
                        :gate gate-id
                        :actual (attr gate :gate/error)})))
             matches))))
       expected)))))

(defn stamp!
  "Stamp one aspect from the latest setup molecule's durable gate evidence."
  [aspect-key root]
  (spool/require-valid! ::specs/stamp-input
                        {:aspect aspect-key :root root}
                        "Dresser stamp input has an invalid shape")
  (let [root (target/resolve-root root)
        [flavour aspect-name :as parts] (str/split aspect-key #"/" 2)]
    (when-not (= 2 (count parts))
      (spool/fail! "Dresser stamp aspect must be <flavour>/<aspect>"
                   {:aspect aspect-key}))
    (let [entry (aspects/aspect aspect-key)
          run-id (target/run-id flavour root)
          expected (expected-gates aspect-key)
          {:keys [root-id gates]} (latest-molecule-gates run-id aspect-key)
          violations (if root-id
                       (evidence-violations expected gates)
                       [{:violation :missing-molecule
                         :run-id run-id}])]
      (when (seq violations)
        (spool/fail! (str "Dresser stamp evidence failed for " flavour "/" aspect-name
                          ": "
                          (str/join ", "
                                    (map #(str (name (:violation %))
                                               (when-let [gate (:gate %)]
                                                 (str "[" gate "]")))
                                         violations)))
                     {:aspect aspect-key
                      :run-id run-id
                      :molecule root-id
                      :violations violations}))
      (let [updated (receipt/merge-aspect (receipt/read-receipt root)
                                          aspect-key
                                          entry
                                          aspects/release-version
                                          (current-fingerprint)
                                          (str (or *current-date* (LocalDate/now))))
            written (receipt/write-receipt! root updated)]
        {:aspect aspect-key
         :entry (get-in written [:aspects aspect-key])
         :plan (get-in (plan root) [:aspects aspect-key])}))))

(def ^:private dresser-arg-spec
  {:op "dresser"
   :doc "Inspect and converge repository conventions."
   :subcommands
   {"about" {:doc "Explain dresser's conventions and receipt semantics."
             :hook-class :read :deadline-class :standard}
    "aspects" {:doc "List the installed aspect registry and release lineage."
               :hook-class :read :deadline-class :standard}
    "template" {:doc "Render one canonical template."
                :flags {:param {:type :map
                                :doc "Template parameter as name=value; repeatable keys accumulate into a map."}}
                :positionals [{:name :name
                               :required? true
                               :doc "Template key, such as spool-repo/deps.edn."}]
                :hook-class :read :deadline-class :standard}
    "plan" {:doc "Compare a target receipt with this registry release."
            :positionals [{:name :root
                           :required? true
                           :doc "Existing git worktree root."}]
            :hook-class :read :deadline-class :standard}
    "start" {:doc "Start a setup convergence run."
             :flags {:aspects {:type :string
                               :doc "Comma-separated full aspect keys."}}
             :positionals [{:name :flavour :required? true}
                           {:name :root :required? true}]
             :hook-class :mutating :deadline-class :standard}
    "verify" {:doc "Start a verify-only run."
              :flags {:aspects {:type :string
                                :doc "Comma-separated full aspect keys."}}
              :positionals [{:name :flavour :required? true}
                            {:name :root :required? true}]
              :hook-class :mutating :deadline-class :standard}
    "next" {:doc "Return the run's ready frontier."
            :flags {:verify {:type :boolean
                             :doc "Address the verify-only run."}}
            :positionals [{:name :flavour :required? true}
                          {:name :root :required? true}]
            :hook-class :read :deadline-class :standard}
    "advance" {:doc "Advance one ready run step or checkpoint."
               :flags {:verify {:type :boolean
                                :doc "Address the verify-only run."}
                       :choice {:type :string}
                       :input {:type :map}
                       :step {:type :string}}
               :positionals [{:name :flavour :required? true}
                             {:name :root :required? true}]
               :hook-class :mutating :deadline-class :standard}
    "stamp" {:doc "Stamp one aspect after latest-molecule gates pass."
             :positionals [{:name :aspect :required? true}
                           {:name :root :required? true}]
             :hook-class :mutating :deadline-class :standard}}})

(def dresser-op-options
  "Registration metadata for the `dresser` op's authoring form."
  {:arg-spec dresser-arg-spec})

(millstrand/defop dresser
  "Inspect and converge repository conventions."
  dresser-op-options
  [request]
  (spool/require-valid! ::specs/op-input request
                        "Dresser operation input has an invalid shape")
  (let [args (:op/args request)
        subcommand (:subcommand args)
        allowed (set (keys specs/op-args-specs))]
    (if-let [arg-spec (get specs/op-args-specs subcommand)]
      (do
        (spool/require-valid! arg-spec args
                              "Dresser subcommand input has an invalid shape")
        (case subcommand
          ["about"] (about)
          ["aspects"] (aspects-view)
          ["template"] (template-view (:name args) (:param args))
          ["plan"] (plan (:root args))
          ["start"] (start (:flavour args) (:root args) (:aspects args))
          ["verify"] (verify (:flavour args) (:root args) (:aspects args))
          ["next"] (ready (:flavour args) (:root args) (:verify args))
          ["advance"] (advance! (:flavour args)
                                (:root args)
                                (:verify args)
                                (cond-> {}
                                  (contains? args :choice) (assoc :choice (:choice args))
                                  (contains? args :input) (assoc :input (keywordize-input (:input args)))
                                  (contains? args :step) (assoc :step (:step args))))
          ["stamp"] (stamp! (:aspect args) (:root args))))
      (spool/fail! "Unsupported dresser subcommand"
                   {:subcommand subcommand
                    :allowed (vec (sort allowed))}))))

(def ^:private dresser-vocab
  "The `dresser/*` attribute-namespace declaration reconcile seeds."
  {:kind :attr-namespace
   :name "dresser"
   :owner :millstrand/spools-dresser
   :keys ["dresser/flavour" "dresser/aspect" "dresser/version" "dresser/root"
          "dresser/gate-id"]
   :doc "Dresser target and aspect identity attributes on workflow roots and steps."})

(defn- require-shell-executor!
  "Fail loudly unless a `:shell` workflow executor is registered.

  Module dependencies cannot express this prerequisite: the executor is
  contributed by a separate module whose consumer-chosen key this spool
  cannot name in an `:after` edge, and the kernel's undeclared-kind refusal
  never fires for an entry nobody contributes. Without it every dresser gate
  would sit unhandled, so activation refuses instead."
  []
  (let [executors (workflow/executors)]
    (when-not (contains? executors :shell)
      (spool/fail! "Dresser requires the shell workflow executor"
                   {:prerequisite :shell
                    :executors (set (keys executors))}))))

(defn seed-dresser-vocabulary!
  "Assert Dresser's executor prerequisite and seed its process-lifetime vocab."
  [{:keys [runtime]}]
  (require-shell-executor!)
  (vocab/declare! runtime dresser-vocab)
  {:seeded :dresser})

(lifecycle/defseed dresser-vocabulary
  "Seed Dresser's process-lifetime vocabulary after its forms publish."
  {:apply 'ct.spools.dresser/seed-dresser-vocabulary!})
