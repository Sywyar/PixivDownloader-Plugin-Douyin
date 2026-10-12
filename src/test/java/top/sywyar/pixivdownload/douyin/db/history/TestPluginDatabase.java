package top.sywyar.pixivdownload.douyin.db.history;

import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.plugin.api.storage.relational.jdbc.JdbcDatabaseSession;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.quote;

/** 默认使用独立 SQLite 夹具；显式传入提供者 JAR 时运行同一套真实提供者回归。 */
final class TestPluginDatabase implements PluginDatabase, AutoCloseable {
    final MigrationSchema schema = PrivateStorageSchema.merge(
            DouyinHistorySchema.contributions(), DouyinHistorySchema.migrations());
    private final Connection connection;
    private final DatabaseConnectionTarget target;
    private final URLClassLoader loader;
    private final String namespace;
    private boolean closed;

    TestPluginDatabase(Path file) {
        this(file, false);
    }

    TestPluginDatabase(Path file, boolean legacy) {
        try {
            String providerJar = System.getProperty("douyin.test.provider.jar");
            String providerClass = System.getProperty("douyin.test.provider.class");
            String url = System.getenv("DOUYIN_TEST_POSTGRES_URL");
            namespace = url == null ? null : "douyin_test_" + UUID.randomUUID().toString().replace("-", "");
            Properties properties = new Properties();
            properties.setProperty("user", System.getenv().getOrDefault("DOUYIN_TEST_POSTGRES_USER", ""));
            properties.setProperty("password", System.getenv().getOrDefault("DOUYIN_TEST_POSTGRES_PASSWORD", ""));
            if (providerJar == null) {
                if (url != null || providerClass != null) throw new IllegalArgumentException("Test provider JAR required");
                loader = null;
                target = null;
                connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                legacySchema(connection);
            } else {
                loader = new URLClassLoader(new java.net.URL[]{Path.of(providerJar).toUri().toURL()},
                        TestPluginDatabase.class.getClassLoader());
                var provider = (DatabaseProvider) Class.forName(Objects.requireNonNull(providerClass), true, loader)
                        .getConstructor().newInstance();
                String targetUrl = "jdbc:sqlite:" + file;
                if (namespace != null) {
                    if (url.contains("?")) throw new IllegalArgumentException("Use an isolated PostgreSQL base URL");
                    try (var admin = DriverManager.getConnection(url, properties); var statement = admin.createStatement()) {
                        statement.execute("CREATE SCHEMA " + namespace);
                    }
                    targetUrl = url + "?currentSchema=" + namespace;
                }
                target = provider.resolve(new DatabaseConnectionSettings(targetUrl,
                        properties.getProperty("user"), properties.getProperty("password"), file.getParent()));
                connection = target.openConnection(DatabaseConnectionTarget.OpenMode.READ_WRITE);
                if (legacy) {
                    if (namespace != null) throw new IllegalArgumentException("Legacy input is SQLite only");
                    legacySchema(connection);
                }
            }
            if (!legacy) initialize();
        } catch (Exception failure) {
            throw new IllegalStateException("Test storage initialization failed", failure);
        }
    }

    static void legacySchema(Connection connection) throws Exception {
        try (var stream = Objects.requireNonNull(TestPluginDatabase.class.getResourceAsStream("/douyin-history-legacy.sql"));
             var statement = connection.createStatement()) {
            for (String sql : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
    }

    void initialize() throws SQLException {
        transaction(session -> {
            if (target != null) target.initializeStorage(connection, schema);
            else try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS _storage_migrations (id TEXT PRIMARY KEY NOT NULL, position INTEGER NOT NULL)");
            }
            StorageMigration.apply(session, DouyinHistorySchema.migrations());
            return null;
        });
    }

    @Override public String databaseId() { return namespace == null ? "sqlite-fixture" : "postgresql-fixture"; }
    @Override public boolean available() { return !closed; }
    @Override public <T> T read(DatabaseSession.Work<T> work) throws SQLException { return transaction(work); }
    @Override public <T> T transaction(DatabaseSession.Work<T> work) throws SQLException {
        if (closed) throw new SQLException("STORAGE_UNAVAILABLE", "08003");
        connection.setAutoCommit(false);
        try (var session = target == null ? new SqliteSession(connection, schema) : target.storageSession(connection, schema)) {
            T result = work.run(session);
            if (session.rollbackOnly()) throw new SQLException("STORAGE_TRANSACTION_FAILED", "25000");
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException | Error failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally { connection.setAutoCommit(true); }
    }

    @Override public void close() throws Exception {
        closed = true;
        try {
            if (namespace != null) try (var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + namespace + " CASCADE");
            }
        } finally {
            try { connection.close(); }
            finally { if (loader != null) loader.close(); }
        }
    }

    private static final class SqliteSession extends JdbcDatabaseSession {
        SqliteSession(Connection connection, MigrationSchema schema) { super(connection, schema); }
        @Override protected String lowerAscii(String sql) { return "lower(" + sql + ")"; }
        @Override protected String minimum(String left, String right) { return "min(" + left + "," + right + ")"; }
        @Override protected String substring(String text, String start, String length) { throw new UnsupportedOperationException(); }
        @Override protected String arithmetic(Expression.Operator op, String left, String right, boolean integral) {
            throw new UnsupportedOperationException();
        }
        @Override protected String epochMonth(String epoch, String zone, boolean fixed) { throw new UnsupportedOperationException(); }
        @Override protected void beforeInsert(String table, Set<String> columns) {}
        @Override protected void afterInsert(String table, Set<String> columns) {}
        @Override protected String generatedInsertStatement(String sql, String key) { throw new UnsupportedOperationException(); }
        @Override protected String updateReturningStatement(String sql, List<String> columns) { throw new UnsupportedOperationException(); }
        @Override protected String conflictClause(String table, Conflict conflict, String updates) {
            if (conflict.mode() == Mode.ERROR) return "";
            String keys = conflict.keys().isEmpty() ? "" : " (" + String.join(",",
                    conflict.keys().stream().map(k -> quote(k)).toList()) + ")";
            return " ON CONFLICT" + keys + (conflict.mode() == Mode.IGNORE ? " DO NOTHING" : " DO UPDATE SET " + updates);
        }
        @Override protected String insertFromStatement(String table, List<String> columns, String select, Conflict conflict, String updates) {
            return "INSERT INTO " + quote(table) + " (" + String.join(",", columns)
                    + ") SELECT " + String.join(",", columns) + " FROM (" + select + ") AS incoming WHERE 1=1"
                    + conflictClause(table, conflict, updates);
        }
    }
}
