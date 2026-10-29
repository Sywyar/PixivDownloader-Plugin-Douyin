package top.sywyar.pixivdownload.douyin.db.history;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.database.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;

@DisplayName("Douyin 已发布私库升级与提供者迁移")
class DouyinStorageMigrationTest {
    @TempDir Path directory;

    @Test @DisplayName("旧表原位升级完整补全多批来源，保留已知关系且只执行一次")
    void legacySchemaUpgrade() throws Exception {
        Assumptions.assumeTrue(System.getenv("DOUYIN_TEST_POSTGRES_URL") == null, "旧输入是 SQLite");
        try (var database = new TestPluginDatabase(directory.resolve("legacy.db"), true)) {
            var mapper = new DouyinHistoryMapper(database);
            mapper.inTransaction(() -> {
                for (int i = 0; i < 1001; i++) mapper.insertWork(
                        DouyinNeutralStorageTest.work(String.format(Locale.ROOT, "%04d", i), i + 1, null));
                mapper.upsertRelation(new DouyinSourceRelation("0000", "douyin.single", "0000", "retained", null, 7, 1));
                return null;
            });
            database.initialize();
            long relations = database.read(s -> (Long) s.query(Query.count("douyin_work_relations", null))
                    .get(0).get("count"));
            assertThat(relations).isEqualTo(1001L);
            assertThat(mapper.findRelationsByWorkId("0000")).singleElement().satisfies(row -> {
                assertThat(row.sourceTitle()).isEqualTo("retained");
                assertThat(row.sourceOrder()).isEqualTo(7);
            });
            mapper.insertWork(DouyinNeutralStorageTest.work("later", 2000, null));
            database.initialize();
            assertThat(mapper.findRelationsByWorkId("later")).isEmpty();
            assertThat(mapper.findAnyById("1000").folder()).isEqualTo("{douyin}/1000");
        }
    }

    @Test @DisplayName("停机转换、取消、空目标检查与迁后核验保留源数据")
    void providerMigration() throws Exception {
        String sourceJar = System.getProperty("douyin.test.sqlite.jar",
                System.getProperty("douyin.test.provider.jar"));
        Assumptions.assumeTrue(sourceJar != null, "需要当前 SQLite 提供者 JAR");
        String targetJar = System.getProperty("douyin.test.migration-target.jar", sourceJar);
        String targetClass = System.getProperty("douyin.test.migration-target.class",
                "top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin");
        String url = System.getenv("DOUYIN_TEST_POSTGRES_URL");
        String namespace = url == null ? null : "douyin_migration_" + UUID.randomUUID().toString().replace("-", "");
        Properties properties = new Properties();
        properties.setProperty("user", System.getenv().getOrDefault("DOUYIN_TEST_POSTGRES_USER", ""));
        properties.setProperty("password", System.getenv().getOrDefault("DOUYIN_TEST_POSTGRES_PASSWORD", ""));
        try (var sourceLoader = loader(sourceJar); var targetLoader = loader(targetJar)) {
            var sourceProvider = provider(sourceLoader, "top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin");
            var targetProvider = provider(targetLoader, targetClass);
            Path sourceFile = directory.resolve("source.db");
            seedLegacy(sourceFile);
            byte[] original = Files.readAllBytes(sourceFile);
            var source = sourceProvider.resolve(new DatabaseConnectionSettings("jdbc:sqlite:" + sourceFile, "", "", directory));
            String targetUrl = "jdbc:sqlite:" + directory.resolve("destination.db");
            if (namespace != null) {
                try (var admin = DriverManager.getConnection(url, properties); var statement = admin.createStatement()) {
                    statement.execute("CREATE SCHEMA " + namespace);
                }
                targetUrl = url + "?currentSchema=" + namespace;
            }
            var target = targetProvider.resolve(new DatabaseConnectionSettings(targetUrl,
                    properties.getProperty("user"), properties.getProperty("password"), directory));
            var migrations = DouyinHistorySchema.migrations();
            var schema = PrivateStorageSchema.merge(DouyinHistorySchema.contributions(), migrations);
            Path backup = directory.resolve("backup.db");
            try {
                var failed = new ArrayList<>(migrations);
                failed.add(new StorageMigration("failure", s -> { throw new SQLException("fixture"); }));
                assertThatThrownBy(() -> sourceProvider.exportDatabase(source,
                        new MigrationRequest(schema, directory.resolve("failed-backup.db"), Set.of(), failed), (t, n) -> {}))
                        .isInstanceOf(Exception.class);
                assertThat(Files.readAllBytes(sourceFile)).isEqualTo(original);
                assertBackupMatches(source, sourceFile, directory.resolve("failed-backup.db"));

                try (var exported = sourceProvider.exportDatabase(source,
                        new MigrationRequest(schema, backup, Set.of(), migrations), (t, n) -> {})) {
                    // 中止的目标可以重新迁入，未提交数据不算成功。
                    try (var cancelled = targetProvider.importDatabase(target, schema, (t, n) -> {})) {
                        try (var rows = exported.rows("douyin_works")) { cancelled.write("douyin_works", rows); }
                    }
                    try (var imported = targetProvider.importDatabase(target, schema, (t, n) -> {})) {
                        for (var table : schema.tables()) {
                            try (var rows = exported.rows(table.name())) { imported.write(table.name(), rows); }
                        }
                        assertThat(imported.verify(exported.snapshot())).isEqualTo(exported.snapshot().verification());
                        imported.commit();
                    }
                    sourceProvider.verifySource(source, exported.snapshot().sourceRevision());
                    assertThatThrownBy(() -> targetProvider.importDatabase(target, schema, (t, n) -> {}))
                            .isInstanceOf(Exception.class);
                }
                assertThat(Files.readAllBytes(sourceFile)).isEqualTo(original);
                assertBackupMatches(source, sourceFile, backup);
                try (var connection = target.openConnection(DatabaseConnectionTarget.OpenMode.EXISTING);
                     var session = target.storageSession(connection, schema)) {
                    var relations = session.query(new Query("douyin_work_relations",
                            Map.of("title", column("douyin_work_relations", "source_title"),
                                    "id", column("douyin_work_relations", "source_id")),
                            null, List.of(), List.of(), 10, 0));
                    assertThat(relations).containsExactlyInAnyOrder(
                            Map.of("title", "合集", "id", "mix"),
                            Map.of("title", "single", "id", "single"));
                    assertThat(session.query(Query.count("douyin_work_files", null)).get(0).get("count")).isEqualTo(1L);
                    StorageMigration.requireApplied(session, migrations);
                }
            } finally {
                if (namespace != null) try (var admin = DriverManager.getConnection(url, properties);
                                           var statement = admin.createStatement()) {
                    statement.execute("DROP SCHEMA " + namespace + " CASCADE");
                }
            }
        }
    }

    private static URLClassLoader loader(String jar) throws Exception {
        return new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()},
                DouyinStorageMigrationTest.class.getClassLoader());
    }

    private static void assertBackupMatches(DatabaseConnectionTarget target, Path source, Path backup) throws Exception {
        var originalSchema = PrivateStorageSchema.merge(DouyinHistorySchema.contributions());
        try (var original = DriverManager.getConnection("jdbc:sqlite:" + source);
             var copy = DriverManager.getConnection("jdbc:sqlite:" + backup)) {
            assertThat(target.readSchema(copy)).isEqualTo(target.readSchema(original));
            assertThat(digests(originalSchema, (t, n) -> {},
                    table -> rows(copy, table, column -> quote(column.name())))).isEqualTo(
                    digests(originalSchema, (t, n) -> {},
                            table -> rows(original, table, column -> quote(column.name()))));
        }
    }

    private static DatabaseProvider provider(ClassLoader loader, String name) throws Exception {
        return (DatabaseProvider) Class.forName(name, true, loader).getConstructor().newInstance();
    }

    private static void seedLegacy(Path file) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            TestPluginDatabase.legacySchema(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO douyin_works (work_id,title,folder,count,extensions,time,kind,collection_id,collection_title) "
                        + "VALUES ('mixed','mixed','{douyin}/mixed',1,'mp4',9007199254740993,'VIDEO',' mix ',' 合集 '),"
                        + "('single','single','{douyin}/single',1,'mp4',9007199254740994,'VIDEO',NULL,NULL)");
                statement.execute("INSERT INTO douyin_work_files (work_id,file_index,media_type,file_name,extension,bytes,created_time) "
                        + "VALUES ('mixed',0,'VIDEO','mixed.mp4','mp4',9007199254740993,9007199254740993)");
            }
        }
    }
}
