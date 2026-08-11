(ns ct.spools.dresser.aspects
  "Versioned convention aspects and their material lineage data."
  (:require [clojure.string :as str]
            [millstrand.api.format.alpha :as fmt]
            [millstrand.api.spool.alpha :as spool]
            [ct.spools.dresser.specs :as specs]
            [ct.spools.dresser.templates :as templates])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def release-version
  "Monotonic release for the complete aspect registry."
  10)

(def releases
  "Published release fingerprints. Historical entries are immutable."
  {1 "03cc25f420a0ef5b961d909205af6c0f2990819f0d858c6797a58ce1390ae498"
   2 "13bc922fb126113db697af8bf825c83fe0908a92536e7e7d9c983f6d39d282b3"
   3 "3241b836a15e41a30428db2d09df9b24a4570abb1f273c50f898dc2b19ca1f89"
   4 "8a0623a366b78d71a5dce9304a82904b38ad80f9454a54550d4c385bfb39f036"
   5 "c65d4c1710f6ec952da6afb57bda81e0b8ae0ee663c33a33ef7954963f800516"
   6 "30964887cba66477f3e268116694853f154f71bc4af9397cdd99165806e54daf"
   7 "04cd916e114ac33ad7447f9151cfb046afd7cbf3061755f655642bbfc4037b98"
   8 "b6eeec076fbe2f24140db091442fbb861b5a406e2a174b5c0616674c71bafaad"
   9 "a3de8dbab7efd2acfda17aadea0e7c7e40bb1a94f13e59b3e3b89cdb9cc61065"
   10 "3a47ea94ad759a414d57c49be651e940169127051f86afb8776c0a28a2f97c63"})

(def ^:private conflict-discipline
  "Honor the recorded conflict decisions for every owned file: keep preserves the customization, merge reconciles it with the canonical template, and replace uses the canonical template.")

(defn- setup [id title instruction template-keys]
  {:id id
   :title title
   :instruction (str instruction " " conflict-discipline)
   :templates template-keys})

(def registry
  "The nine versioned dresser aspects, keyed by <flavour>/<aspect>."
  {"spool-repo/repo-skeleton"
   {:version 8
    :deps []
    :owned ["deps.edn"
            "src/ct/spools/<name>.clj"
            "test/ct/spools/<name>_test.clj"
            "README.md"
            ".gitignore"]
    :inspect "Compare every owned file with the canonical templates, record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-deps "Write deps.edn"
                   "Converge deps.edn using template spool-repo/deps.edn."
                   ["spool-repo/deps.edn"])
            (setup :write-src-test "Write source and test"
                   "Converge the source and fail-loud test namespaces using templates spool-repo/src-ns.clj and spool-repo/test-main.clj."
                   ["spool-repo/src-ns.clj" "spool-repo/test-main.clj"])
            (setup :write-readme "Write README"
                   "Converge README.md using template spool-repo/readme."
                   ["spool-repo/readme"])
            (setup :write-gitignore "Write gitignore"
                   "Converge .gitignore using template spool-repo/gitignore."
                   ["spool-repo/gitignore"])]
    :gates [{:id :test-suite
             :title "Run test suite"
             :argv ["clojure" "-M:test"]
             :timeout-secs 600}
            {:id :readme-sections
             :title "Check README sections"
             :argv ["sh" "-c" "grep -q '## Prerequisites' README.md && grep -q '## Dependency information' README.md && grep -q '## Activation' README.md"]
             :timeout-secs 30}]}

   "spool-repo/millstrand-workspace"
   {:version 3
    :deps []
    :owned [".millstrand/config.json" ".millstrand/spools.edn" ".millstrand/init.clj" ".millstrand/.gitignore"]
    :inspect (fmt/reflow
              "|Compare the .millstrand bootstrap quartet with the canonical templates,
               |record findings, and record a keep/merge/replace decision for each conflict.")
    :setup [(setup :write-workspace "Write Millstrand workspace"
                   (fmt/reflow
                    "|Converge the bootstrap quartet using templates
                     |millstrand/config.json, millstrand/spools.edn, millstrand/init-minimal.clj,
                     |and millstrand/gitignore.")
                   ["millstrand/config.json" "millstrand/spools.edn" "millstrand/init-minimal.clj" "millstrand/gitignore"])]
    :gates [{:id :workspace-files
             :title "Check workspace files"
             :argv ["sh" "-c" "test -f .millstrand/init.clj && test -f .millstrand/spools.edn && test -f .millstrand/.gitignore && grep -q configFormat .millstrand/config.json"]
             :timeout-secs 30}]}

   "spool-repo/agent-docs"
   {:version 1
    :deps []
    :owned ["AGENTS.md"]
    :inspect "Compare AGENTS.md with the canonical agent guidance, record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-agents-md "Write AGENTS.md"
                   "Converge AGENTS.md using template spool-repo/agents.md."
                   ["spool-repo/agents.md"])]
    :gates [{:id :agents-md
             :title "Check AGENTS.md"
             :argv ["sh" "-c" "grep -q 'mill:millstrand-prime' AGENTS.md && test $(wc -l < AGENTS.md) -le 70"]
             :timeout-secs 30}]}

   "spool-repo/quality"
   {:version 4
    :deps ["spool-repo/repo-skeleton"]
    :owned [".cljfmt.edn" ".splint.edn" "deps.edn" "Makefile" "make/quality.mk"]
    :inspect "Compare the quality configuration, deps.edn aliases, Makefile include line, and make/quality.mk with the canonical templates, record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-quality-config "Write quality config"
                   "Converge .cljfmt.edn, .splint.edn, and deps.edn quality aliases using templates spool-repo/cljfmt.edn, spool-repo/splint.edn, and spool-repo/quality-aliases.edn."
                   ["spool-repo/cljfmt.edn" "spool-repo/splint.edn" "spool-repo/quality-aliases.edn"])
            (setup :write-makefiles "Write Makefile fragments"
                   "Converge the Makefile fragment include stub and make/quality.mk using templates spool-repo/makefile and spool-repo/quality.mk."
                   ["spool-repo/makefile" "spool-repo/quality.mk"])]
    :gates [{:id :fmt-check
             :title "Check formatting"
             :argv ["make" "fmt-check"]
             :timeout-secs 300}
            {:id :lint
             :title "Run linters"
             :argv ["make" "lint"]
             :timeout-secs 600}]}

   "spool-repo/docs"
   {:version 1
    :deps ["spool-repo/quality"]
    :owned ["mkdocs.yml"
            "scripts/mkdocs_hooks.py"
            "scripts/generate_api_docs.clj"
            "make/docs.mk"]
    :inspect "Compare the mkdocs config, its link-rewriting hook, the quickdoc generator, and make/docs.mk with the canonical templates; confirm .mkdocs/ projects every page the nav names, that .gitignore ignores site/ and that Makefile keeps its make/*.mk include; record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-docs-pipeline "Write docs pipeline"
                   "Converge mkdocs.yml, scripts/mkdocs_hooks.py, scripts/generate_api_docs.clj, and make/docs.mk using templates spool-repo/mkdocs.yml, spool-repo/mkdocs-hooks.py, spool-repo/generate-api-docs.clj, and spool-repo/docs.mk. Render the two parameterized templates with name, repo-name as <github-owner>/<repo>, git-branch as the branch source links pin, site-name as the published site title, and site-description as one sentence naming what the repo documents. The baseline publishes one API page for ct.spools.<name>; extend the generator's api-docs vector and the mkdocs nav with one entry per further namespace that earns a published page, and add Contract or Cookbook nav rows for hand-written pages this repo already has. Only the generator reads git-branch: mkdocs_hooks.py hardcodes /blob/main/ for the links it rewrites, so a default branch other than main links correctly from API pages and wrongly from every other page."
                   ["spool-repo/mkdocs.yml" "spool-repo/mkdocs-hooks.py"
                    "spool-repo/generate-api-docs.clj" "spool-repo/docs.mk"])
            (setup :link-docs-projection "Link docs projection"
                   "Build the .mkdocs/ docs collection as symlinks to the repository files it publishes, so every page has exactly one source: .mkdocs/index.md -> ../README.md plus one symlink per remaining nav row, each named for the repo path it points at. Symlinks are not file content, so dresser cannot write them from a template; run make api-docs first when a generated API page does not exist yet. mkdocs builds --strict, so a nav row without its symlink fails the docs gate."
                   ["spool-repo/mkdocs.yml"])]
    :gates [{:id :docs-files
             :title "Check docs projection files"
             :argv ["sh" "-c" "test -f mkdocs.yml && test -f scripts/mkdocs_hooks.py && test -f scripts/generate_api_docs.clj && test -f make/docs.mk && test -L .mkdocs/index.md && grep -qF 'include make/*.mk' Makefile && grep -q '^site/$' .gitignore"]
             :timeout-secs 30}
            {:id :docs-targets
             :title "Check docs targets resolve"
             :argv ["sh" "-c" "make -n api-docs docs-site docs-check >/dev/null"]
             :timeout-secs 60}
            {:id :docs-check
             :title "Regenerate API docs and build site"
             ;; Cold uvx and gitlibs caches dominate this gate: a first run
             ;; resolves the whole mkdocs toolchain and the quickdoc git dep
             ;; before any work starts, so it needs far more headroom than the
             ;; other gates, all of which run against warm local tooling.
             :argv ["make" "docs-check"]
             :timeout-secs 900}]}

   "spool-repo/ci"
   {:version 1
    :deps ["spool-repo/docs"]
    :owned [".github/workflows/quality.yml" ".github/workflows/pages.yml"]
    :inspect "Compare both GitHub Actions workflows with the canonical templates, confirm the repository has a GitHub remote whose default branch is main, record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-workflows "Write CI workflows"
                   (fmt/reflow
                    "|Converge .github/workflows/quality.yml and .github/workflows/pages.yml using
                     |templates spool-repo/quality.yml and spool-repo/pages.yml.
                     |Render quality.yml with name and the required published Millstrand SHA as millstrand-sha;
                     |the workflow checks out that immutable core beside the generated repository for operator tooling,
                     |while the generated test alias resolves Millstrand and Millhouse Workflow by Git coordinates.
                     |The quality gate consolidates clj-kondo and splint behind one make lint job,
                     |so a red build names lint rather than the offending linter; that is deliberate
                     |for a four-job repo, not an oversight.
                     |Quality gate is the job name branch protection binds to as a required check, so renaming it silently unbinds the protection.")
                   ["spool-repo/quality.yml" "spool-repo/pages.yml"])]
    :checkpoints [{:id :pages-source
                   :title "Enable GitHub Actions as the Pages build source"
                   :instruction "pages.yml deploys through the native GitHub Actions Pages flow, which needs the repository's Pages build source set to GitHub Actions; it defaults to \"Deploy from a branch\", and until it changes the deploy job fails on every push to main even though the workflow runs. One authenticated command sets it: gh api -X POST repos/<owner>/<repo>/pages -f build_type=workflow. Without gh, a repo admin sets Settings -> Pages -> \"Build and deployment\" to GitHub Actions. The workflow cannot do this for itself: actions/configure-pages with enablement: true fails with \"Resource not accessible by integration\" when the repository has no Pages site yet, so do not reach for it. Dresser has no credential model and no gate can read or write this setting, which is why the answer is recorded here instead."
                   :choices [{:key :enabled
                              :label "Enabled"
                              :description "The Pages build source is set to GitHub Actions."
                              :input {:spec ::specs/pages-enabled-input
                                      :doc "The published site URL, e.g. https://codethread.github.io/kanban.spool/."}}
                             {:key :deferred
                              :label "Deferred"
                              :description "Workflow files are in place and Pages stays off. A repo can adopt the CI gates without publishing a site; the deploy job is knowingly red on push to main until an admin enables it."}
                             {:key :abort
                              :label "Abort"
                              :description "Stop convention convergence for this target."
                              :next :abort
                              :input {:spec ::specs/abort-workflow-input
                                      :doc "Why convention convergence was aborted."}}]}]
    :gates [{:id :workflow-files
             :title "Check workflow files"
             :argv ["sh" "-c" "test -f .github/workflows/quality.yml && test -f .github/workflows/pages.yml && grep -q 'name: Quality gate' .github/workflows/quality.yml && grep -q 'actions/deploy-pages@v4' .github/workflows/pages.yml"]
             :timeout-secs 30}
            {:id :workflow-targets
             :title "Check workflow targets resolve"
             ;; Workflow syntax is linted by quality.yml's own actionlint job
             ;; rather than here: the files are hand-edited long after
             ;; convergence, and a local gate would put a Go toolchain on every
             ;; machine that ever converges this aspect.
             :argv ["sh" "-c" "make -n fmt-check lint test docs-check >/dev/null"]
             :timeout-secs 60}]}

   "millstrand-dir/workspace"
   {:version 3
    :deps []
    :owned [".millstrand/config.json" ".millstrand/spools.edn" ".millstrand/init.clj" ".millstrand/.gitignore"]
    :inspect (fmt/reflow
              "|Compare the self-contained .millstrand workspace with the layered canonical
               |templates, record richer existing files as conflicts, and record a
               |keep/merge/replace decision for each conflict.")
    :setup [(setup :write-workspace "Write layered workspace"
                   (fmt/reflow
                    "|Converge the workspace using templates millstrand/config.json, millstrand/spools.edn,
                     |millstrand/init-layered.clj, and millstrand/gitignore.")
                   ["millstrand/config.json" "millstrand/spools.edn" "millstrand/init-layered.clj" "millstrand/gitignore"])]
    :gates [{:id :workspace-files
             :title "Check workspace files"
             :argv ["sh" "-c" "test -f .millstrand/init.clj && test -f .millstrand/spools.edn && test -f .millstrand/.gitignore && grep -q configFormat .millstrand/config.json"]
             :timeout-secs 30}
            {:id :init-header
             :title "Check init header"
             :argv ["sh" "-c" "head -20 .millstrand/init.clj | grep -qi 'startup entrypoint'"]
             :timeout-secs 30}]}

   "millstrand-dir/quality"
   {:version 2
    :deps ["millstrand-dir/workspace"]
    :owned [".millstrand/deps.edn" ".millstrand/Makefile"]
    :inspect "Compare workspace-local quality tooling with the canonical templates, record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-quality-tooling "Write quality tooling"
                   "Converge .millstrand/deps.edn and .millstrand/Makefile using templates millstrand-dir/deps.edn and millstrand-dir/makefile."
                   ["millstrand-dir/deps.edn" "millstrand-dir/makefile"])]
    :gates [{:id :fmt-check
             :title "Check formatting"
             :argv ["make" "-C" ".millstrand" "fmt-check"]
             :timeout-secs 300}
            {:id :lint
             :title "Run linter"
             :argv ["make" "-C" ".millstrand" "lint"]
             :timeout-secs 600}]}

   "millstrand-dir/agent-docs"
   {:version 1
    :deps ["millstrand-dir/workspace"]
    :owned [".millstrand/AGENTS.md" ".millstrand/CLAUDE.md"]
    :inspect "Compare workspace-local agent guidance with the canonical templates, record findings, and record a keep/merge/replace decision for each conflict."
    :setup [(setup :write-agent-docs "Write agent docs"
                   "Converge .millstrand/AGENTS.md and .millstrand/CLAUDE.md using templates millstrand-dir/agents.md and millstrand-dir/claude.md."
                   ["millstrand-dir/agents.md" "millstrand-dir/claude.md"])]
    :gates [{:id :agent-docs-files
             :title "Check agent docs"
             :argv ["sh" "-c" "test -f .millstrand/AGENTS.md && test -f .millstrand/CLAUDE.md && grep -q 'AGENTS.md' .millstrand/CLAUDE.md"]
             :timeout-secs 30}]}})

(defn- validated-registry []
  (spool/require-valid! ::specs/registry registry
                        "Dresser aspect registry has an invalid shape"))

(defn aspect
  "Return an aspect definition, failing loudly when key is unknown."
  [key]
  (or (get (validated-registry) key)
      (spool/fail! "Unknown dresser aspect"
                   {:aspect key :known (set (keys registry))})))

(defn- key-flavour [key]
  (first (str/split key #"/" 2)))

(defn- ordered-selection [flavour selected]
  (let [available (set (for [key (keys registry) :when (= flavour (key-flavour key))] key))]
    (when (empty? available)
      (spool/fail! "Unknown dresser flavour"
                   {:flavour flavour :known #{"spool-repo" "millstrand-dir"}}))
    (doseq [key selected]
      (when-not (contains? available key)
        (spool/fail! "Unknown dresser aspect for flavour"
                     {:flavour flavour :aspect key :known available})))
    (let [state (atom {})
          result (transient [])]
      (letfn [(visit [key trail]
                (case (get @state key)
                  :done nil
                  :visiting (spool/fail! "Cycle in dresser aspect dependencies"
                                         {:flavour flavour :cycle (conj trail key)})
                  (do
                    (swap! state assoc key :visiting)
                    (doseq [dep (:deps (aspect key))]
                      (when-not (contains? available dep)
                        (spool/fail! "Unknown or cross-flavour aspect dependency"
                                     {:flavour flavour :aspect key :dependency dep}))
                      (visit dep (conj trail key)))
                    (swap! state assoc key :done)
                    (conj! result key))))]
        (doseq [key (sort selected)] (visit key []))
        (persistent! result)))))

(defn flavour-aspects
  "Return all aspects for flavour in deterministic dependency order."
  [flavour]
  (validated-registry)
  (ordered-selection flavour
                     (for [key (keys registry) :when (= flavour (key-flavour key))] key)))

(defn close-under-deps
  "Return selected full aspect keys, closed under dependencies and ordered."
  [flavour keys]
  (validated-registry)
  (ordered-selection flavour keys))

(defn- canonical [value]
  (cond
    (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                       (map (fn [[key item]] [key (canonical item)]))
                       value)
    (set? value) (mapv canonical (sort-by pr-str value))
    (sequential? value) (mapv canonical value)
    :else value))

(defn- template-contents []
  (into (sorted-map)
        (map (fn [[key entry]]
               [key (if (fn? entry) (entry templates/fingerprint-params) entry)]))
        templates/templates))

(defn material-data
  "Return canonical material inputs for the current release plus workflow topology."
  [topology]
  (validated-registry)
  (canonical
   {:aspects
    (into (sorted-map)
          (map (fn [[key {:keys [version deps owned inspect setup checkpoints gates]}]]
                 [key {:version version
                       :deps deps
                       :owned owned
                       :inspect inspect
                       :setup (mapv #(select-keys % [:instruction :templates]) setup)
                       ;; A checkpoint's text and choice set are the whole
                       ;; contract of a decision no gate can make, so they are
                       ;; material to the release the same way an instruction is.
                       :checkpoints (mapv #(select-keys % [:instruction :choices])
                                          checkpoints)
                       :gates (mapv #(select-keys % [:argv :timeout-secs]) gates)}]))
          registry)
    :templates (template-contents)
    :topology topology}))

(defn fingerprint
  "Return the lowercase hexadecimal SHA-256 of canonical material data."
  [topology]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str (material-data topology)) StandardCharsets/UTF_8))]
    (str/join (map #(format "%02x" (bit-and % 0xff)) digest))))
