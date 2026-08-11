(ns dresser
  "Published Dresser workflow definitions.

  This intentionally small module owns the `dresser/*` workflow namespace.
  `defworkflow` keys entries from its qualified Var name, so defining the
  forms here preserves the public names while keeping workflow definitions in
  Millstrand's owner-complete authoring grammar."
  (:require [ct.spools.dresser.workflows :as workflows]
            [millhouse.spools.workflow :as workflow]))

(workflow/defworkflow spool-repo
  "Converge every spool-repo convention aspect on a target root."
  {:entrypoints #{:start}
   :param-spec :ct.spools.dresser.specs/flavour-workflow-input
   :defaults {:verify-only false}}
  (workflows/flavour-workflow "spool-repo"))

(workflow/defworkflow millstrand-dir
  "Converge every millstrand-dir convention aspect on a target root."
  {:entrypoints #{:start}
   :param-spec :ct.spools.dresser.specs/flavour-workflow-input
   :defaults {:verify-only false}}
  (workflows/flavour-workflow "millstrand-dir"))

(workflow/defworkflow abort
  "Record why Dresser convention convergence was aborted, and stop."
  {:entrypoints #{:continue}
   :param-spec :ct.spools.dresser.specs/abort-workflow-input}
  workflows/abort-workflow)

(workflow/defworkflow spool-repo.repo-skeleton
  "Converge the spool-repo/repo-skeleton convention aspect on a target root."
  {:entrypoints #{:call} :param-spec :ct.spools.dresser.specs/aspect-workflow-input
   :defaults {:verify-only false}}
  workflows/spool-repo-repo-skeleton-workflow)

(workflow/defworkflow spool-repo.millstrand-workspace
  "Converge the spool-repo/millstrand-workspace convention aspect on a target root."
  {:entrypoints #{:call} :param-spec :ct.spools.dresser.specs/aspect-workflow-input
   :defaults {:verify-only false}}
  workflows/spool-repo-millstrand-workspace-workflow)

(workflow/defworkflow spool-repo.agent-docs
  "Converge the spool-repo/agent-docs convention aspect on a target root."
  {:entrypoints #{:call} :param-spec :ct.spools.dresser.specs/aspect-workflow-input
   :defaults {:verify-only false}}
  workflows/spool-repo-agent-docs-workflow)

(workflow/defworkflow spool-repo.quality
  "Converge the spool-repo/quality convention aspect on a target root."
  {:entrypoints #{:call} :param-spec :ct.spools.dresser.specs/aspect-workflow-input
   :defaults {:verify-only false}}
  workflows/spool-repo-quality-workflow)

(workflow/defworkflow spool-repo.docs
  "Converge the spool-repo/docs convention aspect on a target root."
  {:entrypoints #{:call} :param-spec :ct.spools.dresser.specs/aspect-workflow-input
   :defaults {:verify-only false}}
  workflows/spool-repo-docs-workflow)

(workflow/defworkflow spool-repo.ci
  "Converge the spool-repo/ci convention aspect on a target root."
  {:entrypoints #{:call} :param-spec :ct.spools.dresser.specs/aspect-workflow-input
   :defaults {:verify-only false}}
  workflows/spool-repo-ci-workflow)

(workflow/defworkflow millstrand-dir.workspace
  "Converge the millstrand-dir/workspace convention aspect on a target root."
  {:entrypoints #{:call} :param-spec :ct.spools.dresser.specs/aspect-workflow-input
   :defaults {:verify-only false}}
  workflows/millstrand-dir-workspace-workflow)

(workflow/defworkflow millstrand-dir.quality
  "Converge the millstrand-dir/quality convention aspect on a target root."
  {:entrypoints #{:call} :param-spec :ct.spools.dresser.specs/aspect-workflow-input
   :defaults {:verify-only false}}
  workflows/millstrand-dir-quality-workflow)

(workflow/defworkflow millstrand-dir.agent-docs
  "Converge the millstrand-dir/agent-docs convention aspect on a target root."
  {:entrypoints #{:call} :param-spec :ct.spools.dresser.specs/aspect-workflow-input
   :defaults {:verify-only false}}
  workflows/millstrand-dir-agent-docs-workflow)
