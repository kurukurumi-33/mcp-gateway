package io.github.kurukurumi33.mcp.backends.sql;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The read-only check shared by every SQL backend.
 *
 * <p>A single allow-list is easier to trust than two copies. The check is lexical, not a parser:
 * string literals, quoted identifiers and comments are stripped first, so the words inside them are
 * never mistaken for keywords. That is enough to stop a model that writes the wrong statement, not a
 * user who is trying to attack the engine — a real SQL parser would be the next step if the gateway
 * ever accepted untrusted clients.
 */
public final class SqlGuard {

    /** Statements that cannot change anything. {@code DESC} is shorthand for {@code DESCRIBE}. */
    private static final Set<String> READ_ONLY_LEADERS = Set.of(
            "select", "with", "from", "values", "describe", "desc", "show", "explain", "summarize");

    /**
     * Anything that can write, attach another database, install an extension or move files. Checked
     * as whole words on the stripped statement, so a column called {@code updated_at} is fine.
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "insert", "update", "delete", "merge", "upsert", "drop", "alter", "create", "truncate",
            "attach", "detach", "copy", "export", "import", "install", "load", "pragma",
            "set", "reset", "call", "vacuum", "checkpoint", "begin", "commit", "rollback");

    private static final Pattern WORD = Pattern.compile("[a-z_][a-z0-9_]*");

    private SqlGuard() {
    }

    /**
     * Returns a human-readable reason when the statement may write, attach or smuggle in a second
     * statement. Empty means it passed every check.
     */
    public static Optional<String> rejectUnsafe(String sql) {
        if (sql == null || sql.isBlank()) {
            return Optional.of("sql is empty");
        }
        String stripped = stripLiteralsAndComments(sql).trim();
        if (stripped.isEmpty()) {
            return Optional.of("sql is empty");
        }

        String withoutTrailing = stripped.endsWith(";")
                ? stripped.substring(0, stripped.length() - 1).trim()
                : stripped;
        if (withoutTrailing.indexOf(';') >= 0) {
            return Optional.of("Only one statement per call is allowed");
        }

        String normalized = withoutTrailing.toLowerCase(Locale.ROOT);
        String leader = firstWord(normalized);
        if (!READ_ONLY_LEADERS.contains(leader)) {
            return Optional.of("Only read-only statements are allowed (SELECT, WITH, DESCRIBE, SHOW, EXPLAIN); got '"
                    + leader + "'");
        }

        Matcher words = WORD.matcher(normalized);
        while (words.find()) {
            if (FORBIDDEN.contains(words.group())) {
                return Optional.of("Statement contains '" + words.group()
                        + "', which this backend does not allow");
            }
        }
        return Optional.empty();
    }

    private static String firstWord(String text) {
        int end = 0;
        while (end < text.length() && (Character.isLetter(text.charAt(end)) || text.charAt(end) == '_')) {
            end++;
        }
        return text.substring(0, end);
    }

    /**
     * Replaces string literals, quoted identifiers and comments with a space.
     *
     * <p>Without this, {@code SELECT 'delete' AS word} would be rejected for a word that never
     * reaches the parser as a keyword.
     */
    public static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);

            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == quote) {
                        // Two quotes in a row are an escaped quote, not the end of the literal.
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(' ');
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                out.append(' ');
            } else if (c == '$') {
                int end = dollarQuoteEnd(sql, i);
                if (end > 0) {
                    i = end;
                    out.append(' ');
                } else {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Returns the index just past a {@code $tag$...$tag$} literal, or -1 when there is none. */
    private static int dollarQuoteEnd(String sql, int start) {
        int tagEnd = sql.indexOf('$', start + 1);
        if (tagEnd < 0) {
            return -1;
        }
        String tag = sql.substring(start, tagEnd + 1);
        int closing = sql.indexOf(tag, tagEnd + 1);
        return closing < 0 ? -1 : closing + tag.length();
    }
}