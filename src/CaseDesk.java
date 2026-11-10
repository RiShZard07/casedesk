import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.LongAdder;

/** AI-assisted local portfolio prototype. No real customer data or external AI calls. */
public final class CaseDesk implements AutoCloseable {
    public enum State { OPEN, ASSIGNED, CLOSED }
    public static final int MAX_TITLE = 160;
    /**
     * Immutable ticket snapshot. The compact constructor enforces the invariants every ticket must
     * satisfy, whether it was built from a live request or replayed from the journal, so a hand-edited
     * journal line cannot load a ticket that {@link Store#create} would have rejected.
     */
    public record Ticket(long id, String title, int priority, State state) {
        public Ticket {
            if (title == null || title.isBlank() || title.length() > MAX_TITLE)
                throw new IllegalArgumentException("title must contain 1-" + MAX_TITLE + " characters");
            if (priority < 1 || priority > 5) throw new IllegalArgumentException("priority must be 1-5");
            Objects.requireNonNull(state, "state");
        }
    }
    private record Submission(String title, int priority, long ticketId) {}
    public static final class Problem extends RuntimeException {
        final int status;
        Problem(int status, String message) { super(message); this.status = status; }
    }

    /**
     * One lock makes queue, tickets and idempotency-key updates atomic in this process.
     * With a journal attached, every mutation is written and fsync'd before it is applied, so an
     * acknowledged change survives a restart; a failed write leaves the in-memory state untouched.
     */
    public static final class Store implements AutoCloseable {
        private final Map<Long, Ticket> tickets = new LinkedHashMap<>();
        private final Map<String, Submission> keys = new HashMap<>();
        private final Map<Long, String> keyOf = new HashMap<>();
        private final PriorityQueue<Ticket> pending = new PriorityQueue<>(
            Comparator.comparingInt(Ticket::priority).reversed().thenComparingLong(Ticket::id));
        private long sequence;
        private final Journal journal;

        /** In-memory store: state is lost when the process exits. */
        public Store() { this(null); }
        /** Store that journals every change; use {@link #open(Path)} to also recover existing state. */
        public Store(Journal journal) { this.journal = journal; }

        /** Replays the journal at {@code path}, compacts it to a snapshot, and returns a store appending to it. */
        public static Store open(Path path) throws IOException {
            Store recovered = new Store();
            int line = 0;
            for (Journal.Event event : Journal.read(path)) {
                line++;
                try { recovered.apply(event); }
                catch (RuntimeException e) { throw new IOException("journal event " + line + " is inconsistent: " + e.getMessage()); }
            }
            Journal.rewrite(path, recovered.snapshotEvents());
            Store store = new Store(new Journal(path));
            for (Journal.Event event : recovered.snapshotEvents()) store.apply(event);
            return store;
        }

        private static String bounded(String value, String field, int max) {
            if (value == null || value.isBlank() || value.length() > max)
                throw new Problem(400, field + " must contain 1-" + max + " characters");
            return value.strip();
        }
        public synchronized Ticket create(String key, String title, int priority) {
            key = bounded(key, "key", 80);
            title = bounded(title, "title", MAX_TITLE);
            if (priority < 1 || priority > 5) throw new Problem(400, "priority must be 1-5");
            Submission previous = keys.get(key);
            if (previous != null) {
                if (!previous.title.equals(title) || previous.priority != priority)
                    throw new Problem(409, "key already used for different input");
                return tickets.get(previous.ticketId);
            }
            long id = sequence + 1;
            record(Journal.Event.create(id, key, title, priority));
            return apply(Journal.Event.create(id, key, title, priority));
        }
        public synchronized Ticket claim() {
            Ticket first = pending.peek();
            if (first == null) throw new Problem(404, "no open tickets");
            record(Journal.Event.claim(first.id));
            return apply(Journal.Event.claim(first.id));
        }
        public synchronized Ticket close(long id) {
            Ticket ticket = tickets.get(id);
            if (ticket == null) throw new Problem(404, "ticket not found");
            if (ticket.state != State.ASSIGNED) throw new Problem(409, "only assigned tickets can close");
            record(Journal.Event.close(id));
            return apply(Journal.Event.close(id));
        }
        public synchronized List<Ticket> snapshot() { return List.copyOf(tickets.values()); }
        public synchronized List<Ticket> snapshot(State state) {
            return tickets.values().stream().filter(t -> t.state == state).toList();
        }
        @Override public synchronized void close() throws IOException { if (journal != null) journal.close(); }

        /** Write-ahead step: durable first, or not at all. */
        private void record(Journal.Event event) {
            if (journal == null) return;
            try { journal.append(event); }
            catch (IOException e) { throw new Problem(503, "journal write failed; state unchanged"); }
        }
        /** Applies an event to memory. Used for live mutations and for replay, so both paths share one transition rule set. */
        private Ticket apply(Journal.Event event) {
            switch (event.op()) {
                case "create", "snapshot" -> {
                    if (tickets.containsKey(event.id())) throw new IllegalStateException("duplicate ticket id " + event.id());
                    if (event.key() == null || event.key().isBlank()) throw new IllegalStateException("ticket " + event.id() + " has no idempotency key");
                    if (keys.containsKey(event.key())) throw new IllegalStateException("duplicate idempotency key " + event.key());
                    State state = event.state() == null ? State.OPEN : State.valueOf(event.state());
                    Ticket ticket = new Ticket(event.id(), event.title(), event.priority(), state);
                    tickets.put(ticket.id, ticket);
                    keys.put(event.key(), new Submission(event.title(), event.priority(), ticket.id));
                    keyOf.put(ticket.id, event.key());
                    if (state == State.OPEN) pending.add(ticket);
                    sequence = Math.max(sequence, ticket.id);
                    return ticket;
                }
                case "claim" -> {
                    Ticket open = tickets.get(event.id());
                    if (open == null || open.state != State.OPEN) throw new IllegalStateException("claim of non-open ticket " + event.id());
                    pending.remove(open);
                    Ticket assigned = new Ticket(open.id, open.title, open.priority, State.ASSIGNED);
                    tickets.put(open.id, assigned);
                    return assigned;
                }
                case "close" -> {
                    Ticket assigned = tickets.get(event.id());
                    if (assigned == null || assigned.state != State.ASSIGNED) throw new IllegalStateException("close of non-assigned ticket " + event.id());
                    Ticket closed = new Ticket(assigned.id, assigned.title, assigned.priority, State.CLOSED);
                    tickets.put(assigned.id, closed);
                    return closed;
                }
                default -> throw new IllegalStateException("unknown op " + event.op());
            }
        }
        private List<Journal.Event> snapshotEvents() {
            List<Journal.Event> events = new ArrayList<>();
            for (Ticket t : tickets.values()) events.add(Journal.Event.snapshot(t.id, keyOf.get(t.id), t.title, t.priority, t.state.name()));
            return events;
        }
    }

    private final Store store;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final LongAdder requests = new LongAdder(), failures = new LongAdder(), nanos = new LongAdder();
    private final String page;
    private final String script;

    public CaseDesk(Store store, int port, Path html) throws IOException {
        this.store = store;
        page = Files.readString(html);
        script = Files.readString(html.resolveSibling("app.js"));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        long start = System.nanoTime();
        requests.increment();
        try {
            String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
            if (method.equals("GET") && path.equals("/")) {
                send(exchange, 200, page, "text/html; charset=utf-8"); return;
            }
            if (method.equals("GET") && path.equals("/app.js")) {
                send(exchange, 200, script, "text/javascript; charset=utf-8"); return;
            }
            if (method.equals("GET") && path.equals("/tickets")) {
                StringJoiner out = new StringJoiner(",", "[", "]");
                stateFilter(exchange.getRequestURI().getQuery()).map(store::snapshot).orElseGet(store::snapshot).forEach(t -> out.add(json(t)));
                send(exchange, 200, out.toString(), "application/json"); return;
            }
            if (method.equals("GET") && path.equals("/metrics")) {
                List<Ticket> tickets = store.snapshot();
                long open = tickets.stream().filter(t -> t.state == State.OPEN).count();
                long closed = tickets.stream().filter(t -> t.state == State.CLOSED).count();
                send(exchange, 200, "{\"requests\":" + requests.sum() + ",\"errors\":" + failures.sum()
                    + ",\"completed_request_nanos\":" + nanos.sum() + ",\"total_tickets\":" + tickets.size()
                    + ",\"open\":" + open + ",\"assigned\":" + (tickets.size()-open-closed)
                    + ",\"closed\":" + closed + "}", "application/json"); return;
            }
            if (!Set.of("/tickets", "/claim", "/close", "/metrics").contains(path))
                throw new Problem(404, "route not found");
            if (!method.equals("POST") || path.equals("/metrics")) throw new Problem(405, "method not allowed");
            Map<String, String> form = form(exchange);
            Ticket result;
            try {
                result = switch (path) {
                    case "/tickets" -> store.create(form.get("key"), form.get("title"), Integer.parseInt(form.get("priority")));
                    case "/claim" -> store.claim();
                    case "/close" -> store.close(Long.parseLong(form.get("id")));
                    default -> throw new Problem(404, "route not found");
                };
            } catch (NumberFormatException e) { throw new Problem(400, "missing or invalid numeric field"); }
            send(exchange, 200, json(result), "application/json");
        } catch (Problem error) {
            failures.increment();
            if (error.status == 405) exchange.getResponseHeaders().set("Allow", switch(exchange.getRequestURI().getPath()) { case "/metrics" -> "GET"; case "/tickets" -> "GET, POST"; default -> "POST"; });
            send(exchange, error.status, "{\"error\":" + quote(error.getMessage()) + "}", "application/json");
        } catch (RuntimeException error) {
            failures.increment();
            send(exchange, 500, "{\"error\":\"internal error\"}", "application/json");
        } finally { nanos.add(System.nanoTime()-start); exchange.close(); }
    }
    /** Parses an optional {@code ?state=OPEN|ASSIGNED|CLOSED} query; any other value is a 400. */
    static Optional<State> stateFilter(String query) {
        if (query == null || query.isEmpty()) return Optional.empty();
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (!kv[0].equals("state")) continue;
            try { return Optional.of(State.valueOf(kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8).toUpperCase(Locale.ROOT) : "")); }
            catch (IllegalArgumentException e) { throw new Problem(400, "state must be OPEN, ASSIGNED or CLOSED"); }
        }
        return Optional.empty();
    }
    private static Map<String,String> form(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";",2)[0].strip().equalsIgnoreCase("application/x-www-form-urlencoded"))
            throw new Problem(415, "use application/x-www-form-urlencoded");
        byte[] raw = exchange.getRequestBody().readNBytes(8193);
        if (raw.length > 8192) throw new Problem(413, "body exceeds 8192 bytes");
        Map<String,String> result = new HashMap<>();
        String input = new String(raw, StandardCharsets.UTF_8);
        if (input.isEmpty()) return result;
        try {
            for (String pair : input.split("&")) {
                String[] kv = pair.split("=",2);
                String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String value = kv.length == 1 ? "" : URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                if (result.putIfAbsent(key,value) != null) throw new Problem(400, "duplicate form field");
            }
        } catch (IllegalArgumentException e) { throw new Problem(400, "invalid form encoding"); }
        return result;
    }
    public static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    private static String json(Ticket t) {
        return "{\"id\":"+t.id+",\"title\":"+quote(t.title)+",\"priority\":"+t.priority+",\"state\":"+quote(t.state.name())+"}";
    }
    private static void send(HttpExchange exchange, int status, String content, String type) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",type);
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        exchange.sendResponseHeaders(status,bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    public void close() {
        server.stop(0); workers.shutdownNow();
        try { store.close(); } catch (IOException ignored) { /* nothing left to flush: every append was already fsync'd */ }
    }
    /** Usage: java -cp build CaseDesk [port] [journal path | :memory:]. Default journal: data/casedesk.jsonl */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String journal = args.length > 1 ? args[1] : "data/casedesk.jsonl";
        Store store = journal.equals(":memory:") ? new Store() : Store.open(Path.of(journal));
        CaseDesk app = new CaseDesk(store,port,Path.of("web/index.html"));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        System.out.println("CaseDesk local prototype: http://localhost:"+app.port()
            + (journal.equals(":memory:") ? " (in-memory, nothing persisted)" : " (journal: " + journal + ", " + store.snapshot().size() + " tickets recovered)"));
    }
}
