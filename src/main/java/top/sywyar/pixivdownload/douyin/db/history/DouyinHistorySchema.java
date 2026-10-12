package top.sywyar.pixivdownload.douyin.db.history;

import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 三张私有表及旧作品的来源补全，不依赖物理存储位置。 */
public final class DouyinHistorySchema {
    static final String WORKS = "douyin_works";
    static final String FILES = "douyin_work_files";
    static final String RELATIONS = "douyin_work_relations";

    private DouyinHistorySchema() {}

    public static List<SchemaContribution> contributions() {
        return List.of(new SchemaContribution(List.of(
                new TableSpec(WORKS, List.of(
                        new ColumnSpec("work_id", "TEXT", false, null, 1),
                        text("title", true), text("folder", true), integer("count", true),
                        text("extensions", true), integer("time", true),
                        new ColumnSpec("deleted", "INTEGER", true, "0", 0),
                        text("kind", true), text("source_url", false), text("canonical_url", false),
                        text("thumbnail_url", false), text("author_id", false), text("author_name", false),
                        text("description", false), text("item_title", false), text("caption", false),
                        integer("publish_time", false), text("collection_id", false),
                        text("collection_title", false), integer("collection_order", false)), List.of(
                        new IndexSpec(null, IndexOrigin.UNIQUE_CONSTRAINT, true, List.of("time")),
                        index("idx_douyin_works_author_time", "author_id", "time"),
                        index("idx_douyin_works_collection_order", "collection_id", "collection_order"))),
                new TableSpec(FILES, List.of(
                        new ColumnSpec("work_id", "TEXT", true, null, 1),
                        new ColumnSpec("file_index", "INTEGER", true, null, 2),
                        text("media_id", false), text("media_type", true), text("file_name", true),
                        text("extension", true), integer("bytes", false), text("content_type", false),
                        integer("created_time", true)), List.of(index("idx_douyin_work_files_work_id", "work_id"))),
                new TableSpec(RELATIONS, List.of(
                        new ColumnSpec("work_id", "TEXT", true, null, 1),
                        new ColumnSpec("source_type", "TEXT", true, null, 2),
                        new ColumnSpec("source_id", "TEXT", true, null, 3),
                        text("source_title", false), text("source_url", false), integer("source_order", false),
                        integer("discovered_time", true)), List.of(
                        index("idx_douyin_work_relations_source", "source_type", "source_id", "source_order")))),
                List.of(), List.of()));
    }

    public static List<StorageMigration> migrations() {
        return List.of(new StorageMigration("backfill_work_relations", session -> {
            backfillRelations(session);
            return null;
        }));
    }

    static int backfillRelations(DatabaseSession session) throws SQLException {
        Expression collection = nonBlank("collection_id");
        Map<String, Expression> values = Map.of(
                "work_id", column(WORKS, "work_id"),
                "source_type", new Conditional(call(IS_NULL, collection),
                        value("douyin.single"), value("douyin.collection")),
                "source_id", call(COALESCE, collection, column(WORKS, "work_id")),
                "source_title", call(COALESCE, nonBlank("collection_title"), column(WORKS, "title")),
                "source_url", call(COALESCE, nonBlank("source_url"), column(WORKS, "canonical_url")),
                "source_order", column(WORKS, "collection_order"),
                "discovered_time", column(WORKS, "time"));
        long count = (Long) session.query(Query.count(WORKS, null)).get(0).get("count");
        int inserted = 0;
        // 分批执行同一结构事务，不以单次查询上限截断历史记录。
        for (long offset = 0; offset < count; offset += 500) {
            inserted = Math.addExact(inserted, session.insertFrom(RELATIONS,
                    new Query(WORKS, values, null, List.of(),
                            List.of(new Query.Order(column(WORKS, "work_id"), false, true)), 500, offset),
                    DatabaseSession.Conflict.ignore()));
        }
        return inserted;
    }

    private static Expression nonBlank(String name) {
        return call(NULL_IF, call(TRIM, column(WORKS, name)), value(""));
    }

    private static ColumnSpec text(String name, boolean required) {
        return new ColumnSpec(name, "TEXT", required, null, 0);
    }

    private static ColumnSpec integer(String name, boolean required) {
        return new ColumnSpec(name, "INTEGER", required, null, 0);
    }

    private static IndexSpec index(String name, String... columns) {
        return new IndexSpec(name, IndexOrigin.CREATE_INDEX, false, List.of(columns));
    }
}
