# dresser.spool

`ct.spools.dresser` converges repository conventions through versioned Millstrand workflows. An operator drives setup work against a target git root; shell gates verify the result; an explicit stamp writes the checked-in receipt beside the selected workspace marker at `.millstrand/conventions.edn` or `.ms/conventions.edn`. The authoritative selection rule is in [Receipt, plan, verify, and stamp](./dresser.md#receipt-plan-verify-and-stamp).

The spool is trusted Clojure code for a live Millstrand weaver. It has no
`spool.edn` manifest: approve source in `spools.edn` or `spools.local.edn`, then
activate its modules explicitly from `init.clj` or a trusted REPL. The full
behavior contract is in [dresser.md](./dresser.md).

## Prerequisites

- A Millstrand checkout and a live weaver configured from an operator workspace.
- Explicit approval of the Millhouse Workflow root `millhouse.spools/workflow`.
- A 40-hex git SHA pin for this repository, or a local development override.
- The shell executor namespace. It ships on the workflow spool classpath, needs
  no separate source approval, and is activation-only. Activate it after the
  workflow engine.

Dresser installs no prerequisite transitively. The target repository does not
need Millstrand or a running weaver; it only needs to be an existing git worktree
root. Gates run the target's own toolchain, so `spool-repo/docs` additionally
needs `make`, `clojure` and `uvx` on the operator's PATH. `spool-repo/ci` writes
GitHub Actions workflows, so it assumes a GitHub-hosted repository with `main`
as its default branch, and publishing its documentation site needs admin rights
on that repository.

## Dependency information

Approve every source spool explicitly. A published workspace pins Millstrand
by its immutable SHA and can use:

```clojure
{:spools
 {io.millstrand/millstrand
  {:git/url "https://github.com/codethread/millstrand.git"
   :git/sha "fb6c9057d594bfa4b5ea8531b9774b5e9a23a4b4"}

  millhouse/spools
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/sha "8f386b09fb8e8506a3c38105dce8e8552142dbf8"
   :roots {millhouse.spools/workflow "spools/workflow"}}

  codethread/dresser
  {:git/url "git@github.com:codethread/dresser.spool.git"
   :git/sha "<40-hex-sha-for-the-approved-commit>"}}}
```

For local dresser development, add a gitignored `spools.local.edn` overlay:

```clojure
{:spools
 {codethread/dresser
  {:local/root "/Users/you/dev/dresser.spool"}}}
```

## Activation

Declare the workflow engine, the classpath shell executor, Dresser's workflow
definitions, and its CLI/vocabulary module in the workspace's `init.clj`,
ordered by `:after` edges. These are form-authored modules: `defworkflow`,
`defop`, and `defseed` collect their declarations from the declared source
target. Startup config therefore declares only the source and world policy:

```clojure
(runtime/module! runtime :workflow
                 {:ns 'millhouse.spools.workflow
                  :spools ['millhouse.spools/workflow]
                  :required? true})

(runtime/module! runtime :shell-executor
                 {:ns 'millhouse.spools.executors.shell
                  :spools ['millhouse.spools.executors/shell
                           'millhouse.spools/workflow]
                  :after [:workflow]
                  :required? true})

(runtime/module! runtime :dresser-workflows
                 {:ns 'dresser
                  :spools ['codethread/dresser]
                  :after [:workflow]
                  :required? true})

(runtime/module! runtime :dresser
                 {:ns 'ct.spools.dresser
                  :spools ['codethread/dresser]
                  :after [:workflow :shell-executor :dresser-workflows]
                  :required? true})
```

Dresser's workflow module publishes its static definitions, while its CLI
module publishes the `dresser` op and seeds the `dresser/*` vocabulary. The
seed refuses activation when no `:shell` executor is registered. Refreshing a
module without its forms retracts that module's entries by omission.

The published names are `:<flavour>` for the two umbrellas (entrypoint
`:start`), `:<flavour>.<aspect>` for each registry aspect (entrypoint
`:call`, expanded inline by its umbrella), and `:abort` (entrypoint
`:continue`, routed to by the conflict checkpoint). Each is a plain definition
value a Var holds, so `strand workflow show <name>` answers what a name means
without running anything. Publication refuses a partition whose definitions
call or route to a name it does not contain.

The shell executor is present through the approved workflow root; omitting a
separate `:spools` guard on its activation is intentional.

## Quickstart

Initialize a target before starting a run. Dresser resolves the path once and
requires it to be the git worktree root:

```sh
git init /path/to/target
strand dresser aspects
strand dresser plan /path/to/target
strand dresser start spool-repo /path/to/target
```

Drive every non-gate item returned by `next`. Complete inspect and setup steps
after doing the stated work; answer every conflict checkpoint. Shell gates run
through the executor and do not need `advance`:

```sh
strand dresser next spool-repo /path/to/target
strand dresser advance spool-repo /path/to/target \
  --step <ready-strand-id>
strand dresser advance spool-repo /path/to/target \
  --step <checkpoint-strand-id> --choice clean
```

Use `--choice apply-plan --input decisions='<summary>'` when files need explicit
keep/merge/replace decisions, or `--choice abort --input reason='<reason>'` to
route to the abort stage. An aspect may declare further checkpoints after its
setup steps: `spool-repo/ci` asks `pages-source`, answered with
`--choice enabled --input site-url='<url>'` or `--choice deferred`. Repeat
`next` and `advance` until the run reports done.
Then stamp each adopted aspect from that setup run's latest green gate evidence:

```sh
strand dresser stamp spool-repo/repo-skeleton /path/to/target
strand dresser stamp spool-repo/millstrand-workspace /path/to/target
strand dresser stamp spool-repo/agent-docs /path/to/target
strand dresser stamp spool-repo/quality /path/to/target
strand dresser stamp spool-repo/docs /path/to/target
strand dresser stamp spool-repo/ci /path/to/target
strand dresser plan /path/to/target
```

The receipt records verified adoption; it does not prove the files have not
drifted. Recheck selected aspects without setup work by starting `verify`, then
inspect that run with `next --verify`:

```sh
strand dresser verify spool-repo /path/to/target \
  --aspects spool-repo/millstrand-workspace,spool-repo/agent-docs
strand dresser next spool-repo /path/to/target --verify
```

One operator world may manage a target at a time. Concurrent dresser runs
against the same target from different operator worlds are out of contract;
receipt writes are atomic per file but last-writer-wins across worlds.

## Development

Local development deliberately overlays Millstrand at `../millstrand` and the
Millhouse Workflow root at `../millhouse.spool/spools/workflow`; the
`equivalence-published` alias resolves both roots by immutable Git SHA:

```sh
clojure -M:test
PATH=/opt/homebrew/opt/openjdk/bin:$PATH make fmt-check lint test
```

The repository's `.millstrand/init.clj` remains the minimal batteries bootstrap.
Dresser is not activated in its own workspace.

Canonical template content lives under `resources/ct/spools/dresser/templates/`,
one file per key in `ct.spools.dresser.templates/templates`, at the key's own
path. A key ending in `.clj`/`.cljc` gets a `.template` extension on disk: millstrand
loads every `.clj` under a spool root's `:paths` as a namespace source, so a
template fragment named `.clj` would be evaluated as code on `reload-code!`.
The template operation accepts the authoritative `::specs/template-input` shape, `{:name <non-blank-string> :params {<keyword-or-string> <string>}}`. The parameterized `spool-repo/quality.yml` template requires both `:name` and the published immutable `:millstrand-sha`, for example:

```sh
strand dresser template spool-repo/quality.yml \
  --param name=acme --param millstrand-sha=fb6c9057d594bfa4b5ea8531b9774b5e9a23a4b4
```

Template bytes are covered by `expected-template-hashes` in the test suite, which
guards the release fingerprints derived from them.
