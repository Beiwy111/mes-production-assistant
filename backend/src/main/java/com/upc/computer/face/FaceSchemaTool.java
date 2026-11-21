package com.upc.computer.face;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Explicit Windows-friendly migration utility; never starts Spring or existing data seeders. */
public final class FaceSchemaTool {
    private static final Set<String> TABLES = Set.of("mes_face_binding", "mes_face_cleanup", "mes_face_audit");
    private static final Pattern CREATE = Pattern.compile("^CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+(mes_face_[a-z_]+)\\s*\\(");

    public static void main(String[] args) {
        if (args.length != 1 || !("--apply".equals(args[0]) || "--check".equals(args[0]))) {
            System.out.println("Additive migration: creates only mes_face_binding / mes_face_cleanup / mes_face_audit.");
            System.out.println("Set FACE_DB_URL, FACE_DB_USER, FACE_DB_PASSWORD; --check tests access, --apply creates the tables.");
            return;
        }
        String url = System.getenv("FACE_DB_URL"), user = System.getenv("FACE_DB_USER"), password = System.getenv("FACE_DB_PASSWORD");
        if (url == null || !url.startsWith("jdbc:mysql:") || user == null || password == null) {
            System.err.println("Missing MySQL settings; no database changes made."); System.exit(2);
        }
        String stage = "LOAD_SCRIPT";
        try (var stream = FaceSchemaTool.class.getResourceAsStream("/db/face-auth-v1.sql")) {
            if (stream == null) throw new IllegalStateException();
            List<String> commands = migrationStatements(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            stage = "LOAD_DRIVER";
            Class.forName("com.mysql.cj.jdbc.Driver");
            stage = "CONNECT";
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
                stage = "CHECK_DATABASE";
                if (connection.getCatalog() == null || connection.getCatalog().isBlank()) throw new IllegalStateException();
                if ("--check".equals(args[0])) {
                    statement.execute("SELECT 1");
                    System.out.println("MySQL connection OK. Selected database is reachable; no schema changes made.");
                    return;
                }
                stage = "CREATE_TABLES";
                int count = apply(connection, commands);
                System.out.println("Face schema applied: " + count + " additive CREATE TABLE statements. Existing business tables unchanged.");
            }
        } catch (Exception ex) {
            System.err.println(safeDiagnostic(stage, ex)); System.exit(1);
        }
    }

    /** Validate the entire bundled script before executing any DDL. */
    static List<String> migrationStatements(String sql) {
        List<String> commands = splitStatements(sql);
        Set<String> found = new HashSet<>();
        for (String command : commands) {
            var match = CREATE.matcher(command);
            if (!match.find() || !TABLES.contains(match.group(1)) || !found.add(match.group(1))) {
                throw new IllegalArgumentException("Unexpected migration statement");
            }
        }
        if (!found.equals(TABLES)) throw new IllegalArgumentException("Incomplete migration script");
        return commands;
    }

    static int apply(Connection connection, List<String> commands) throws SQLException {
        try (var statement = connection.createStatement()) {
            for (String command : commands) statement.execute(command);
        }
        return commands.size();
    }

    /** Handles MySQL quoted strings/identifiers and comments; semicolons inside them are not delimiters. */
    static List<String> splitStatements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i), next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (i == 0 && ch == '\uFEFF') continue;
            if (quote != 0) {
                current.append(ch);
                if (ch == '\\' && quote != '`' && next != 0) { current.append(next); i++; }
                else if (ch == quote) {
                    if (next == quote) { current.append(next); i++; }
                    else quote = 0;
                }
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch; current.append(ch);
            } else if (ch == '#' || (ch == '-' && next == '-' &&
                    (i + 2 == sql.length() || Character.isWhitespace(sql.charAt(i + 2))))) {
                while (i + 1 < sql.length() && sql.charAt(i + 1) != '\n' && sql.charAt(i + 1) != '\r') i++;
                current.append(' ');
            } else if (ch == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) throw new IllegalArgumentException("Unterminated SQL comment");
                current.append(' '); i = end + 1;
            } else if (ch == ';') {
                if (!current.toString().isBlank()) result.add(current.toString().strip());
                current.setLength(0);
            } else current.append(ch);
        }
        if (quote != 0) throw new IllegalArgumentException("Unterminated SQL quote");
        if (!current.toString().isBlank()) result.add(current.toString().strip());
        return result;
    }

    /** Never print exception messages, URLs, usernames or credentials. */
    static String safeDiagnostic(String stage, Exception ex) {
        String hint = switch (stage) {
            case "LOAD_SCRIPT" -> "Check the bundled face-auth-v1.sql migration resource.";
            case "LOAD_DRIVER" -> "Use the executable MySQL connector JAR, not a sources or javadoc JAR.";
            case "CONNECT" -> "Check MySQL service, database name and the original MES database credentials locally.";
            case "CHECK_DATABASE" -> "Select the existing MES database in FACE_DB_URL.";
            default -> "Check CREATE permissions and the face table definitions. Earlier CREATE statements may have completed; rerunning is supported.";
        };
        String details = "";
        if (ex instanceof SQLException sql) {
            String state = sql.getSQLState();
            details = ", SQLState=" + (state != null && state.matches("[A-Za-z0-9]{5}") ? state : "unknown")
                    + ", vendorCode=" + sql.getErrorCode();
        }
        return "Migration failed [stage=" + stage + details + "]. " + hint
                + " Credentials and raw SQL error text were omitted.";
    }
}
