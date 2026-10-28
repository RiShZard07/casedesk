# Verification

Compiled with JDK 17 and executed on October 26, 2026 (persistence layer added the same day with Claude Code).

```sh
npm ci --ignore-scripts && npm run build
mkdir -p build
javac -d build src/*.java tests/CaseDeskTest.java
java -cp build CaseDeskTest
npm run test:ui
```

- All 24 Java test groups passed locally: 11 store/concurrency groups, 7 journal groups (restart recovery, idempotency keys across restart, priority order across restart, torn tail dropped and repaired, corrupt middle line fails fast, failed write leaves state unchanged, startup compaction bounds the file), 6 HTTP groups.
- All 6 Playwright browser scenarios passed locally against a `:memory:` server (Chromium headless).
- Manual restart check: created two tickets and claimed one over HTTP, killed the process, restarted it; the server reported `2 tickets recovered`, `GET /tickets?state=ASSIGNED` returned the claimed ticket, and the journal had been compacted from three operation lines to two snapshot lines.

Concurrency fixtures submit 100 identical creations through eight workers (one ticket) and 100 claims through eight workers (100 distinct IDs). They are correctness checks, not throughput or latency benchmarks. Hosted GitHub Actions results must be checked separately. No users, deployments, business impact, external AI models, or Salesforce integrations are claimed.
