package io.github.rainyinsaigon.fraudlinker.cli;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.BatchLinker;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Assignment;
import io.github.rainyinsaigon.fraudlinker.io.JsonOutput;
import io.github.rainyinsaigon.fraudlinker.io.JsonlReader;
import io.github.rainyinsaigon.fraudlinker.streaming.StreamLinker;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line entry point.
 *
 * <pre>
 * linker link   --accounts &lt;accounts.jsonl&gt; --constraints &lt;constraints.jsonl&gt; --output &lt;clusters.json&gt;
 * linker stream --accounts &lt;accounts.jsonl&gt; --constraints &lt;constraints.jsonl&gt;
 * </pre>
 *
 * Stream mode reads one account per stdin line and writes, then flushes, one
 * assignment per stdout line. Diagnostics only ever go to stderr.
 */
public final class LinkerCli {
    private LinkerCli() {
    }

    public static void main(String[] args) {
        int code = run(args, System.in, System.out, System.err);
        System.exit(code);
    }

    /** Runs the CLI and returns the process exit code (0 on success). */
    public static int run(String[] args, InputStream stdin, OutputStream stdout, PrintStream stderr) {
        try {
            dispatch(args, stdin, stdout, stderr);
            return 0;
        } catch (LinkerException | UsageException e) {
            stderr.println("linker: " + e.getMessage());
            return 1;
        }
    }

    private static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    private static void dispatch(String[] args, InputStream stdin, OutputStream stdout, PrintStream stderr) {
        if (args.length == 0) {
            printUsage(stderr);
            throw new UsageException("missing command");
        }
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "link" -> runLink(rest);
            case "stream" -> runStream(rest, stdin, stdout);
            case "help", "-h", "--help" -> printUsage(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            default -> {
                printUsage(stderr);
                throw new UsageException("unknown command \"" + args[0] + "\"");
            }
        }
    }

    private static void runLink(String[] args) {
        Map<String, String> flags = parseFlags(args, Set.of("accounts", "constraints", "output"));
        String accounts = flags.get("accounts");
        String constraints = flags.get("constraints");
        String output = flags.get("output");
        if (isBlank(accounts) || isBlank(constraints) || isBlank(output)) {
            throw new UsageException("link requires --accounts, --constraints, and --output");
        }
        LinkState state = buildState(Path.of(accounts), Path.of(constraints));
        Path outputPath = Path.of(output);
        try {
            Files.writeString(outputPath, JsonOutput.clusters(state.clusters()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LinkerException("write output \"" + outputPath + "\": " + e.getMessage(), e);
        }
    }

    private static void runStream(String[] args, InputStream stdin, OutputStream stdout) {
        Map<String, String> flags = parseFlags(args, Set.of("accounts", "constraints"));
        String accounts = flags.get("accounts");
        String constraints = flags.get("constraints");
        if (isBlank(accounts) || isBlank(constraints)) {
            throw new UsageException("stream requires --accounts and --constraints");
        }
        LinkState state = buildState(Path.of(accounts), Path.of(constraints));
        StreamLinker linker = new StreamLinker(state);
        Writer writer = new BufferedWriter(new OutputStreamWriter(stdout, StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        try {
            JsonlReader.scanAccounts(reader, "stdin", account -> {
                Assignment assignment = linker.assign(account);
                try {
                    writer.write(JsonOutput.assignment(assignment));
                    writer.write('\n');
                    // Flush every response: the caller waits for this line before sending the next event.
                    writer.flush();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw new LinkerException("write stream response: " + e.getCause().getMessage(), e);
        }
    }

    private static LinkState buildState(Path accountsPath, Path constraintsPath) {
        var accounts = JsonlReader.readAccounts(accountsPath);
        var constraints = Constraints.of(JsonlReader.readConstraints(constraintsPath));
        return BatchLinker.build(accounts, constraints, ClusteringConfig.defaults());
    }

    /** Accepts {@code --name value}, {@code -name value} and {@code --name=value}. */
    static Map<String, String> parseFlags(String[] args, Set<String> allowed) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-") || arg.equals("-") || arg.equals("--")) {
                throw new UsageException("unexpected positional arguments: " + List.of(args).subList(i, args.length));
            }
            String name = arg.startsWith("--") ? arg.substring(2) : arg.substring(1);
            String value;
            int equals = name.indexOf('=');
            if (equals >= 0) {
                value = name.substring(equals + 1);
                name = name.substring(0, equals);
            } else {
                if (i + 1 >= args.length) {
                    throw new UsageException("flag needs an argument: -" + name);
                }
                value = args[++i];
            }
            if (!allowed.contains(name)) {
                throw new UsageException("flag provided but not defined: -" + name);
            }
            values.put(name, value);
        }
        return values;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  linker link --accounts <accounts.jsonl> --constraints <constraints.jsonl> --output <clusters.json>");
        out.println("  linker stream --accounts <accounts.jsonl> --constraints <constraints.jsonl>");
    }
}
