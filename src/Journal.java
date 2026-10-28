import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only JSON-lines write-ahead journal for the ticket store.
 *
 * Every state change is written and fsync'd here BEFORE it is applied in memory, so a ticket
 * that was acknowledged to a client survives a process restart. On startup the journal is
 * replayed in order and then rewritten as a compact snapshot, so its size is bounded by the
 * number of live tickets rather than by the number of operations ever performed.
 *
 * Only the final line may be incomplete (a crash in the middle of an append). It is ignored and
 * removed by the next compaction. A malformed line anywhere else means the file was edited or
 * corrupted, and startup fails fast rather than loading a partial history.
 */
public class Journal implements Closeable {
    /** One journal line. key/title are null for claim and close events; state is set for snapshot lines only. */
    public record Event(String op, long id, String key, String title, int priority, String state) {
        public static Event create(long id, String key, String title, int priority) { return new Event("create", id, key, title, priority, null); }
        public static Event claim(long id) { return new Event("claim", id, null, null, 0, null); }
        public static Event close(long id) { return new Event("close", id, null, null, 0, null); }
        public static Event snapshot(long id, String key, String title, int priority, String state) { return new Event("snapshot", id, key, title, priority, state); }
    }

    private final FileOutputStream out;

    /** Opens the journal for appending, creating the file and parent directories if needed. */
    public Journal(Path path) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        out = new FileOutputStream(path.toFile(), true);
    }

    /** Appends one event and forces it to disk. Throws if the write cannot be made durable. */
    public void append(Event event) throws IOException {
        out.write((encode(event) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.getFD().sync();
    }

    @Override public void close() throws IOException { out.close(); }

    /** Reads every complete event in order. A torn final line is skipped; any other bad line is an error. */
    public static List<Event> read(Path path) throws IOException {
        List<Event> events = new ArrayList<>();
        if (!Files.exists(path)) return events;
        String text = Files.readString(path, StandardCharsets.UTF_8);
        int lineNumber = 0, start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) break; // unterminated tail: the append was interrupted, so the event never took effect
            lineNumber++;
            String line = text.substring(start, end);
            start = end + 1;
            if (line.isBlank()) continue;
            try { events.add(decode(line)); }
            catch (RuntimeException e) { throw new IOException("journal line " + lineNumber + " is malformed: " + e.getMessage()); }
        }
        return events;
    }

    /** Atomically replaces the journal with the given events: write a temp file, fsync it, then rename over the original. */
    public static void rewrite(Path path, List<Event> events) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (FileOutputStream tempOut = new FileOutputStream(temp.toFile(), false)) {
            StringBuilder content = new StringBuilder();
            for (Event event : events) content.append(encode(event)).append('\n');
            tempOut.write(content.toString().getBytes(StandardCharsets.UTF_8));
            tempOut.flush();
            tempOut.getFD().sync();
        }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String encode(Event e) {
        StringBuilder json = new StringBuilder("{\"op\":").append(CaseDesk.quote(e.op())).append(",\"id\":").append(e.id());
        if (e.key() != null) json.append(",\"key\":").append(CaseDesk.quote(e.key()));
        if (e.title() != null) json.append(",\"title\":").append(CaseDesk.quote(e.title()));
        if (e.priority() != 0) json.append(",\"priority\":").append(e.priority());
        if (e.state() != null) json.append(",\"state\":").append(CaseDesk.quote(e.state()));
        return json.append('}').toString();
    }

    /** Parses one flat JSON object with string and integer values, which is all the journal ever writes. */
    static Event decode(String line) {
        Parser p = new Parser(line);
        String op = null, key = null, title = null, state = null;
        long id = -1; int priority = 0;
        p.expect('{');
        while (true) {
            String name = p.string();
            p.expect(':');
            switch (name) {
                case "op" -> op = p.string();
                case "key" -> key = p.string();
                case "title" -> title = p.string();
                case "state" -> state = p.string();
                case "id" -> id = p.number();
                case "priority" -> priority = (int) p.number();
                default -> throw new IllegalArgumentException("unknown field " + name);
            }
            if (p.peek() == ',') { p.expect(','); continue; }
            p.expect('}');
            break;
        }
        if (op == null || id < 0) throw new IllegalArgumentException("missing op or id");
        return new Event(op, id, key, title, priority, state);
    }

    private static final class Parser {
        private final String s; private int i;
        Parser(String s) { this.s = s; }
        char peek() { skip(); if (i >= s.length()) throw new IllegalArgumentException("unexpected end"); return s.charAt(i); }
        void expect(char c) { if (peek() != c) throw new IllegalArgumentException("expected '" + c + "' at " + i); i++; }
        private void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        long number() {
            skip(); int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (start == i) throw new IllegalArgumentException("expected number at " + start);
            return Long.parseLong(s.substring(start, i));
        }
        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                if (i >= s.length()) throw new IllegalArgumentException("unterminated escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw new IllegalArgumentException("bad unicode escape");
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape \\" + e);
                }
            }
        }
    }
}
