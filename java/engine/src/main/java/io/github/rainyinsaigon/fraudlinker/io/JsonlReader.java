package io.github.rainyinsaigon.fraudlinker.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import io.github.rainyinsaigon.fraudlinker.normalize.Text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads accounts and constraints from JSONL (one JSON object per line).
 *
 * <p>Blank lines are skipped. Unknown fields are ignored and field names match
 * case-insensitively. A record with a missing {@code account_id} or an invalid
 * {@code created_at} is an error that names the source and line number.
 */
public final class JsonlReader {
    /** A single record larger than this is rejected instead of exhausting memory. */
    static final int MAX_RECORD_CHARS = 4 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** RFC 3339: seconds required, optional fraction, 'Z' or a numeric offset. */
    private static final DateTimeFormatter RFC_3339 = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .appendOffset("+HH:MM", "Z")
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private JsonlReader() {
    }

    public static List<Account> readAccounts(Path path) {
        List<Account> accounts = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            scanAccounts(reader, path.toString(), accounts::add);
        } catch (IOException e) {
            throw new LinkerException("open accounts \"" + path + "\": " + e.getMessage(), e);
        }
        return accounts;
    }

    public static List<Constraint> readConstraints(Path path) {
        List<Constraint> constraints = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            scan(reader, path.toString(), node -> constraints.add(decodeConstraint(node)));
        } catch (IOException e) {
            throw new LinkerException("open constraints \"" + path + "\": " + e.getMessage(), e);
        }
        return constraints;
    }

    /** Streams accounts one at a time; {@code handler} runs before the next line is read. */
    public static void scanAccounts(Reader reader, String source, Consumer<Account> handler) {
        scan(reader, source, node -> handler.accept(decodeAccount(node)));
    }

    public static List<Account> decodeAccounts(Reader reader, String source) {
        List<Account> accounts = new ArrayList<>();
        scanAccounts(reader, source, accounts::add);
        return accounts;
    }

    public static List<Constraint> decodeConstraints(Reader reader, String source) {
        List<Constraint> constraints = new ArrayList<>();
        scan(reader, source, node -> constraints.add(decodeConstraint(node)));
        return constraints;
    }

    private static void scan(Reader reader, String source, Consumer<JsonNode> handler) {
        BufferedReader lines = reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader);
        int lineNumber = 0;
        while (true) {
            String line;
            try {
                line = lines.readLine();
            } catch (IOException e) {
                throw new LinkerException("read " + source + " near line " + (lineNumber + 1) + ": " + e.getMessage(), e);
            } catch (UncheckedIOException e) {
                throw new LinkerException("read " + source + " near line " + (lineNumber + 1) + ": " + e.getMessage(), e);
            }
            if (line == null) {
                return;
            }
            lineNumber++;
            if (Text.trimSpace(line).isEmpty()) {
                continue;
            }
            try {
                if (line.length() > MAX_RECORD_CHARS) {
                    throw new LinkerException("record exceeds " + MAX_RECORD_CHARS + " characters");
                }
                JsonNode node;
                try {
                    node = MAPPER.readTree(line);
                } catch (JsonProcessingException e) {
                    throw new LinkerException("invalid JSON: " + e.getOriginalMessage());
                }
                if (node == null || !node.isObject()) {
                    throw new LinkerException("record must be a JSON object");
                }
                handler.accept(node);
            } catch (LinkerException e) {
                throw new LinkerException(source + ":" + lineNumber + ": " + e.getMessage(), e);
            }
        }
    }

    private static Account decodeAccount(JsonNode node) {
        String accountId = Text.trimSpace(string(node, "account_id"));
        if (accountId.isEmpty()) {
            throw new LinkerException("missing account_id");
        }
        Instant createdAt = timestamp(node, "created_at");
        if (createdAt == null) {
            throw new LinkerException("missing or invalid created_at");
        }
        return new Account(
                accountId,
                string(node, "email"),
                nullableString(node, "device_id"),
                string(node, "ip"),
                nullableString(node, "payment_fingerprint"),
                createdAt);
    }

    private static Constraint decodeConstraint(JsonNode node) {
        String accountA = Text.trimSpace(string(node, "account_a"));
        String accountB = Text.trimSpace(string(node, "account_b"));
        String relation = Text.trimSpace(string(node, "relation"));
        if (accountA.isEmpty() || accountB.isEmpty()) {
            throw new LinkerException("constraint endpoint is empty");
        }
        return new Constraint(accountA, accountB, relation);
    }

    /** Finds a field by exact name, falling back to a case-insensitive match. */
    private static JsonNode field(JsonNode node, String name) {
        JsonNode exact = node.get(name);
        if (exact != null) {
            return exact;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** A string field; missing or null reads as "". */
    private static String string(JsonNode node, String name) {
        String value = nullableString(node, name);
        return value == null ? "" : value;
    }

    /** A string field; missing or null reads as null. Any other JSON type is an error. */
    private static String nullableString(JsonNode node, String name) {
        JsonNode value = field(node, name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new LinkerException("field " + name + " must be a string, got " + value.getNodeType()
                    .toString().toLowerCase(Locale.ROOT));
        }
        return value.textValue();
    }

    private static Instant timestamp(JsonNode node, String name) {
        String value = nullableString(node, name);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, RFC_3339).toInstant();
        } catch (DateTimeParseException e) {
            throw new LinkerException("decode account: invalid " + name + " \"" + value + "\"");
        }
    }
}
