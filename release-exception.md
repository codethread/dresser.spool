# MSR-09 Dresser release exception

This record prepares Dresser's next marker, `v4`. It is not a tag or a
publication instruction.

- Previous marker: annotated `v3` at `d296a9f`.
- Proposed marker: annotated `v4` after the candidate is landed.
- Affected root: the card target `codethread/dresser.spool` and its generated
  `spool-repo` targets. This checkout's current `origin` is
  `codethread/dresser.loom`; the distinction is recorded in
  `release/msr09-release.json`; the coordinator renames it to the canonical
  `codethread/dresser.spool` repository before publication, and published
  verification requires that post-rename URL explicitly.
- Domain identity retained: `ct.spools.dresser`, `dresser/*`, and the Dresser
  workflow names remain Dresser-owned. The product-owned core identity moves
  to `millstrand.*`, `io.millstrand/millstrand`, and `.millstrand`/`.ms`.
- Core input: `release/msr04-release.json`, an immutable SHA record for
  `codethread/millstrand` at
  `fb6c9057d594bfa4b5ea8531b9774b5e9a23a4b4`. No core tag, `:git/tag`, or
  `v1` core marker is used.
- Generated repositories consume that SHA in `deps.edn` and in the CI
  workflow's checkout `ref`; `:local/root` is rejected from generated release
  metadata. Local sibling checkouts are development-only test paths.
- `.millstrand` and `.ms` are equivalent workspace spellings. The pre-tag
  verifier proves that both select the same Millstrand database after a real
  stop and reopen.
- Change-review dispositions: accepted fail-closed `MILL_BIN`/`STRAND_BIN` overrides, ambiguous dual workspace-marker rejection, `|`-margin reflow for migrated operator-facing aspect prose, `.ms` receipt-path documentation, generated README documentation of the immutable `../millstrand` test sibling and CI layout, removal of orphaned board/guild/kanban/peering comments, and the authoritative `::specs/template-input` plus `:millstrand-sha` template docs. Rejected broad spec validation additions, adding a core-repo CLI-style guide here, removing generated AGENTS workflow discipline, or replacing the pre-existing wait-for-attention timeout/polling and public workflow composition.
- `bin/verify-generated-repo` drives `strand dresser plan/start`, answers every
  fresh-target checkpoint deterministically, stamps every shipped aspect, reruns
  `plan` and `verify`, runs the generated quality commands, then regenerates
  against a committed baseline and requires zero diff.
- The Dresser release record keeps the card target and current origin separate;
  published verification requires the canonical post-rename repository URL;
  no repository rename is inferred by the verifier.
- `bin/identity-check` is a fail-closed audit over active source, tests,
  templates, workspace config, build files, and release docs. Its empty
  allowlist records that no active legacy identity exception remains.
- Release proof: run `make all`, then
  `MILL_BIN=/path/to/mill bin/verify-generated-repo --mode pre-tag
  --source-root "$PWD" --core-release release/msr04-release.json`.
  Post-tag proof uses `--mode published --repository
  https://github.com/codethread/dresser.spool.git --tag v4 --sha <sha>`.

Rollback is a consumer action: retain or restore the previous Dresser SHA pin.
No tag or publication is performed by this branch.
