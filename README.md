# CaseDesk: Java + React support-ticket service

An **AI-assisted educational prototype**, built with ChatGPT/Codex and extended with Claude Code, to practice Java backend design, data structures, HTTP contracts, concurrency testing, durable storage and operational metrics. This is not a Salesforce integration, a production deployment, or evidence of real customer adoption. It makes no LLM API calls.

## Run

JDK 17+ and Node.js 22+. The Java backend has no third-party dependencies. The React frontend and build/test tools are pinned in `package-lock.json`. From the repository root:

```sh
npm ci --ignore-scripts
npm run build
mkdir -p build
javac -d build src/*.java tests/CaseDeskTest.java
java -cp build CaseDeskTest
java -cp build CaseDesk
```

Open `http://localhost:8080`. Tickets are journaled to `data/casedesk.jsonl` and come back after a restart. Optional arguments: `java -cp build CaseDesk 8081 path/to/journal.jsonl`, or `java -cp build CaseDesk 8081 :memory:` to keep nothing.

For the six browser integration scenarios, run `npx playwright install chromium --only-shell` and `npm run test:ui` after building and compiling. The test script starts and stops its own Java server on an ephemeral port. Generated bundles and screenshots are not committed.

## Implemented

- Java records and an encapsulated store model immutable ticket snapshots and the `OPEN -> ASSIGNED -> CLOSED` lifecycle.
- A priority queue claims priority 5 before 1, breaking equal-priority ties by arrival sequence. Enqueue/dequeue are O(log n); keyed ticket/idempotency lookups are expected O(1). Listing and snapshot metrics are O(n).
- Synchronized store methods update tickets, keys, and queue together. One hundred concurrent retries produce one ticket; one hundred concurrent claims produce distinct assignments in regression tests. These are correctness checks, **not throughput benchmarks**.
- An idempotency key retries identical normalized submissions without duplicate tickets; reuse with changed title/priority returns 409. Retry returns the ticket's current state, not the original response bytes.
- A loopback HTTP server accepts URL-encoded forms and returns JSON; guards validation, body size, numeric parsing, duplicate fields, missing records and illegal state transitions.
- A React frontend uses components, state/effect/ref hooks, controlled forms, and asynchronous HTTP calls to create, claim, list and close fictional tickets. React escapes ticket titles as text. Buttons disable during requests; a ref guards rapid repeated events, and failed submissions retain their idempotency key for retries of unchanged inputs.
- `/metrics` reports request/error counts, cumulative completed-request duration in nanoseconds, and a consistent ticket-state snapshot. Timing/count reads are approximate under concurrency; the currently executing metrics request is counted but not yet timed.
- `Journal` is an append-only JSON-lines write-ahead log. Each create, claim and close is written and fsync'd *before* it is applied in memory, so an acknowledged change survives a process restart; if the write fails the request gets a 503 and memory is untouched. On startup the journal is replayed through the same `apply` transition rules as live requests, then rewritten atomically (temp file, fsync, rename) as a snapshot, so the file is bounded by live tickets rather than total operations. A torn final line from a crash mid-append is dropped; a malformed line anywhere else fails startup instead of loading partial history. The `Ticket` record's compact constructor enforces the title and priority limits, so a well-formed but invalid journal line (priority 7, blank title, missing idempotency key) also fails startup rather than loading a ticket that `POST /tickets` would have rejected.
- `GET /tickets?state=OPEN|ASSIGNED|CLOSED` filters the listing; any other value is a 400.
- Twenty-five Java unit/concurrency/persistence/HTTP test groups and six Playwright browser scenarios cover lifecycle actions, priority assignment, restart recovery, idempotency keys across restarts, torn, corrupt and invalid journals, failed writes, compaction, metrics refresh, escaped titles, retry after a lost response, and visible backend errors. See `LEARNING_GUIDE.md` before representing personal proficiency on a resume.

## HTTP contract

| Method | Route | Form fields | Success |
|---|---|---|---|
| GET | `/` | none | local UI |
| GET | `/app.js` | none | built React bundle |
| GET | `/tickets` | optional query `state=OPEN\|ASSIGNED\|CLOSED` | JSON array |
| POST | `/tickets` | key, title, priority (1-5) | JSON ticket |
| POST | `/claim` | none; send form Content-Type | highest-priority open ticket |
| POST | `/close` | id | closed ticket |
| GET | `/metrics` | none | JSON process metrics |

Successful writes return 200 including idempotent retries. Errors return JSON and 400, 404, 405, 409, 413, 415 or 503 (journal write failed) as appropriate. The body limit is 8 KiB. Strings are validated by Java character count, not bytes. JSON strings escape quotes, backslashes and ASCII control characters.

## Deliberate limitations

Persistence is a single-process, single-writer journal: no concurrent processes, no ad hoc queries, no indexes, no encryption at rest, and compaction only runs at startup, so a long-running server's journal grows with every operation until the next restart. Replaying a `claim` removes the ticket from the heap with an O(n) scan, which is fine for a replay but would not be acceptable on the live path. There is no authentication, authorization, CSRF defense, TLS, distributed coordination, pagination, queue aging/fairness, rate limiting, network timeout at the server, or production observability backend. The fixed worker pool has no custom bounded queue/backpressure policy. Low-priority tickets may starve. The UI's retry key is not durable across a page reload. This is a loopback-only learning demo: use fictional data, stop it after use, and do not expose it to a network or deploy it as a real service.

Next steps (not implemented): a SQLite/JDBC repository behind the same `apply` rules with unique keys and transactions, authenticated agents and tenant isolation, aging priorities, bounded request queues, structured telemetry, and periodic online compaction.

## Design tradeoffs

A single lock is simple to reason about but limits concurrent writes; replacing it requires preserving atomicity across all three structures. A write-ahead journal was chosen over a database to keep the backend dependency-free and to make the durability rule explicit (fsync before apply, replay through the same transition code); the cost is no query language and no second writer, which is exactly what the SQLite next step would buy. A heap supports efficient prioritization without sorting all tickets on every claim, but dynamic reprioritization would require a stale-entry strategy or an indexed queue. Form input avoids a third-party JSON parser; responses remain JSON for the UI. AI assistance accelerated the draft, while executable tests check specific behavior rather than proving the system is production-ready.
