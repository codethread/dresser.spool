(ns ct.spools.dresser.workflows
  "Static workflow definitions generated from the versioned dresser aspect registry.

  A registered workflow is a plain value, so `aspect-workflow` and
  `flavour-workflow` return `workflow/static-definition` maps rather than
  param-taking constructors, and each `def` below binds one such value. The
  generator stays a function because the registry — not this file — decides how
  many definitions exist; what it returns is the value the Var holds, which is
  what lets the form-authored `dresser` module publish resolvable symbols under
  the engine's definition kind and lets a worker read what a registered name
  means without executing anything.

  Each definition carries its own contract: `:entrypoints` says how the name may
  be reached, and `:param-spec` names the spec that judges the whole resolved
  params map, replacing the per-key argument list a constructor used to own."
  (:require [clojure.string :as str]
            [millstrand.api.spool.alpha :as spool]
            [ct.spools.dresser.aspects :as aspects]
            [ct.spools.dresser.specs :as specs]
            [millhouse.spools.workflow :as workflow]))

(defn- aspect-parts [aspect-key]
  (let [[flavour aspect-name :as parts] (str/split aspect-key #"/" 2)]
    (when-not (= 2 (count parts))
      (throw (ex-info "Dresser aspect key must be <flavour>/<aspect>"
                      {:aspect aspect-key})))
    [flavour aspect-name]))

(defn- param-value [key]
  (fn [params]
    (get params key)))

(defn- aspect-attributes [flavour aspect-key version]
  {"dresser/flavour" flavour
   "dresser/aspect" aspect-key
   "dresser/version" version
   "dresser/root" (param-value :root)})

(defn- inspect-instruction [entry]
  (fn [{:keys [root]}]
    (str (:inspect entry)
         " Owned files: " (str/join ", " (:owned entry)) "."
         " Target root: " root ".")))

(defn- setup-instruction [setup]
  (fn [{:keys [root]}]
    (str (:instruction setup)
         " Template keys: " (str/join ", " (:templates setup)) "."
         " Target root: " root ".")))

(defn- checkpoint-instruction [checkpoint]
  (fn [{:keys [root]}]
    (str (:instruction checkpoint)
         " Target root: " root ".")))

(def ^:private decisions-input
  "Declared choice input for `apply-plan`: the whole-map contract `choose!`
  judges before the recorded decisions become the run's evidence."
  {:spec ::specs/conflict-decisions-input
   :doc "Summary of per-file keep/merge/replace decisions."})

(def ^:private abort-reason-input
  "Declared choice input for `abort`, which is the abort workflow's own param
  contract: the choice and the continuation it routes to are judged by one spec
  rather than by two descriptions that can drift apart."
  {:spec ::specs/abort-workflow-input
   :doc "Why convention convergence was aborted."})

(defn- conflict-checkpoint [attributes]
  (workflow/checkpoint
   :conflict
   "Resolve owned-file conflicts"
   :kind :human
   :condition [:!= :verify-only true]
   :depends-on [:inspect]
   :choices [{:key :clean
              :label "Clean"
              :description "Inspection found no owned-file conflicts."}
             {:key :apply-plan
              :label "Apply plan"
              :description "Apply the recorded keep/merge/replace decisions."
              :input decisions-input}
             {:key :abort
              :label "Abort"
              :description "Stop convention convergence for this target."
              :next :abort
              :input abort-reason-input}]
   :attributes (assoc attributes
                      "workflow/decision-point" "conflict-policy")))

(defn- setup-steps [entry attributes]
  (second
   (reduce (fn [[dependency steps] setup]
             [(:id setup)
              (conj steps
                    (workflow/step
                     (:id setup)
                     (:title setup)
                     :self
                     :condition [:!= :verify-only true]
                     :depends-on [dependency]
                     :attributes (assoc attributes
                                        "workflow/instruction"
                                        (setup-instruction setup))))])
           [:conflict []]
           (:setup entry))))

(defn- aspect-checkpoints
  "Return an aspect's registry-declared checkpoints, chained after `dependency`.

  These stand where a gate would if the fact were checkable: they record an
  operator's answer about state outside the target tree, so like every setup
  step they are excluded from a verify-only run, which carries gates alone."
  [entry dependency attributes]
  (second
   (reduce (fn [[checkpoint-dependency steps] {:keys [id title choices] :as checkpoint}]
             [id
              (conj steps
                    (workflow/checkpoint
                     id
                     title
                     :kind :human
                     :condition [:!= :verify-only true]
                     :depends-on [checkpoint-dependency]
                     :choices choices
                     :attributes (assoc attributes
                                        "workflow/decision-point" (name id)
                                        "workflow/instruction"
                                        (checkpoint-instruction checkpoint))))])
           [dependency []]
           (:checkpoints entry))))

(defn- gate-steps [entry dependency attributes]
  (second
   (reduce (fn [[gate-dependency steps] {:keys [id title argv timeout-secs]}]
             [id
              (conj steps
                    (workflow/gate
                     id
                     title
                     :shell
                     :depends-on [gate-dependency]
                     :attributes (assoc attributes
                                        "dresser/gate-id" (name id)
                                        "shell/argv" argv
                                        "shell/cwd" (param-value :root)
                                        "shell/timeout-secs" timeout-secs)))])
           [dependency []]
           (:gates entry))))

(defn registered-name
  "Return the stable registry name an aspect's definition is published under."
  [aspect-key]
  (keyword (str/replace aspect-key "/" ".")))

(defn aspect-workflow
  "Return the static workflow definition for `aspect-key`.

  Reached only as a `call` target from its flavour umbrella, so `:call` is the
  one entrypoint it declares: nothing starts an aspect on its own, and nothing
  routes to one."
  [aspect-key]
  (spool/require-valid! ::specs/aspect-key aspect-key
                        "Dresser aspect workflow key has an invalid shape")
  (let [entry (aspects/aspect aspect-key)
        [flavour aspect-name] (aspect-parts aspect-key)
        attributes (aspect-attributes flavour aspect-key (:version entry))
        setup-dependency (or (:id (peek (:setup entry))) :conflict)
        gate-dependency (or (:id (peek (:checkpoints entry))) setup-dependency)]
    (workflow/static-definition
     (str "Converge the " aspect-key " convention aspect on a target root.")
     {:entrypoints #{:call}
      :param-spec ::specs/aspect-workflow-input
      :defaults {:verify-only false}}
     (apply workflow/workflow
            (str "Dresser " aspect-key)
            (concat
             [(workflow/step
               :inspect
               (str "Inspect " aspect-name)
               :self
               :condition [:!= :verify-only true]
               :attributes (assoc attributes
                                  "workflow/instruction"
                                  (inspect-instruction entry)))
              (conflict-checkpoint attributes)]
             (setup-steps entry attributes)
             (aspect-checkpoints entry setup-dependency attributes)
             (gate-steps entry gate-dependency attributes))))))

(def spool-repo-repo-skeleton-workflow
  (aspect-workflow "spool-repo/repo-skeleton"))

(def spool-repo-millstrand-workspace-workflow
  (aspect-workflow "spool-repo/millstrand-workspace"))

(def spool-repo-agent-docs-workflow
  (aspect-workflow "spool-repo/agent-docs"))

(def spool-repo-quality-workflow
  (aspect-workflow "spool-repo/quality"))

(def spool-repo-docs-workflow
  (aspect-workflow "spool-repo/docs"))

(def spool-repo-ci-workflow
  (aspect-workflow "spool-repo/ci"))

(def millstrand-dir-workspace-workflow
  (aspect-workflow "millstrand-dir/workspace"))

(def millstrand-dir-quality-workflow
  (aspect-workflow "millstrand-dir/quality"))

(def millstrand-dir-agent-docs-workflow
  (aspect-workflow "millstrand-dir/agent-docs"))

(defn- call-id [aspect-key]
  (keyword (second (aspect-parts aspect-key))))

(defn- aspect-call [aspect-key dependency]
  (cond-> (workflow/call
           (call-id aspect-key)
           (registered-name aspect-key)
           {}
           :title (str "Complete " aspect-key))
    dependency (assoc :depends-on [dependency])))

(defn flavour-workflow
  "Return the static umbrella definition covering `selected` aspects of `flavour`.

  The one-argument arity covers the flavour's complete aspect set and is what
  the registry publishes under `:dresser/<flavour>`; `:start` is its only
  entrypoint, because an umbrella begins a run and is never called or routed to.

  A `call` takes no `:condition`, so which aspects a definition covers is fixed
  where it is authored and no param can narrow it. The two-argument arity is
  therefore how an operator's `--aspects` selection is expressed: trusted
  Clojure builds the narrower definition and pours the value it holds, past the
  registry boundary rather than through it (TEN-002)."
  ([flavour]
   (flavour-workflow flavour (aspects/flavour-aspects flavour)))
  ([flavour selected]
   (spool/require-valid! ::specs/flavour flavour
                         "Dresser workflow flavour has an invalid shape")
   (spool/require-valid! ::specs/aspects (vec selected)
                         "Dresser workflow aspect selection has an invalid shape")
   (let [calls (second
                (reduce (fn [[dependency result] aspect-key]
                          [(call-id aspect-key)
                           (conj result (aspect-call aspect-key dependency))])
                        [nil []]
                        selected))]
     (workflow/static-definition
      (str "Converge the " flavour " convention aspects on a target root.")
      {:entrypoints #{:start}
       :param-spec ::specs/flavour-workflow-input
       :defaults {:verify-only false}}
      (apply workflow/workflow
             (str "Dresser " flavour)
             {:attributes {"dresser/flavour" flavour
                           "dresser/root" (param-value :root)}}
             calls)))))

(def spool-repo-workflow
  (flavour-workflow "spool-repo"))

(def millstrand-dir-workflow
  (flavour-workflow "millstrand-dir"))

(def abort-workflow
  (workflow/static-definition
   "Record why dresser convention convergence was aborted, and stop."
   {:entrypoints #{:continue}
    :param-spec ::specs/abort-workflow-input}
   (workflow/workflow
    "Dresser abort"
    (workflow/step
     :abort
     "Record dresser abort"
     :self
     :attributes {"workflow/instruction"
                  (fn [params]
                    (str "Record why dresser convergence was aborted: "
                         (:reason params)))}))))

(def workflow-definitions
  "Stable workflow names and their form-authored Vars, retained as a pure
  discovery/test catalog. The `dresser` module publishes these names through
  `workflow/defworkflow`; this map is never a contribution payload."
  {:spool-repo 'dresser/spool-repo
   :millstrand-dir 'dresser/millstrand-dir
   :abort 'dresser/abort
   :spool-repo.repo-skeleton
   'dresser/spool-repo.repo-skeleton
   :spool-repo.millstrand-workspace
   'dresser/spool-repo.millstrand-workspace
   :spool-repo.agent-docs
   'dresser/spool-repo.agent-docs
   :spool-repo.quality
   'dresser/spool-repo.quality
   :spool-repo.docs
   'dresser/spool-repo.docs
   :spool-repo.ci
   'dresser/spool-repo.ci
   :millstrand-dir.workspace
   'dresser/millstrand-dir.workspace
   :millstrand-dir.quality
   'dresser/millstrand-dir.quality
   :millstrand-dir.agent-docs
   'dresser/millstrand-dir.agent-docs})

(defn- topology-mode [aspect-key verify-only]
  (let [params {:root "<root>" :verify-only verify-only}
        description (workflow/describe (aspect-workflow aspect-key) params)]
    {:steps (mapv #(select-keys % [:id :role :depends-on])
                  (:steps description))
     :gates (into [] (keep #(when (:gate %) (:id %))) (:steps description))}))

(defn describe-topology
  "Return deterministic setup and verify-only topology for every aspect."
  []
  (let [topology (into (sorted-map)
                       (map (fn [aspect-key]
                              [aspect-key
                               {:setup (topology-mode aspect-key false)
                                :verify-only (topology-mode aspect-key true)}]))
                       (sort (keys aspects/registry)))]
    (spool/require-valid! ::specs/topology topology
                          "Dresser topology has an invalid shape")
    topology))
