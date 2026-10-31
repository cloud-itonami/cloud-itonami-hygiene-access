# AGENTS.md

## CI/CD: no JVM / nbb (JUN-290, removed in JUN-717)

Workspace rule (owner, 2026-10-22, JUN-290): JVM and nbb are not used, so CI/CD
must not start setup-java, setup-clojure, clojure, nbb, babashka or GraalVM.
Such workflows are deleted, not disabled or kept as a fallback. The canonical
rule is the "CI/CD（CDCI）に JVM と nbb を置かない" section of the workspace
`AGENTS.md` (canonical source: `com-junkawasaki/root/AGENTS.md`, "CDCI 停止中").

- **Removed:** `.github/workflows/ci.yml` (job `test`: setup-java 21 +
  setup-clojure, sibling checkouts of kotoba-lang/langchain, langgraph, dcs,
  crm and cloud-itonami/cloud-itonami-regulatory-tracker, then `kbb -M:lint`
  and `kbb -M:test`). It was the only workflow in this repository.
- **Native gate (Amu selfhost):** none. This repository has no Amu selfhost
  native gate with a negative control; that is recorded as blocked.
- **No longer covered in CI:** clj-kondo lint of `src` and `test`
  (`:lint`, errors fail), and the `test/` suite via cognitect test-runner
  (`:test`), including the cross-repo classpath against the sibling checkouts
  listed above.
