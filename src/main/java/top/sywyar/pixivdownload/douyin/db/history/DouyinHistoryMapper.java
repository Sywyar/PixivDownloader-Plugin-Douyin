package top.sywyar.pixivdownload.douyin.db.history;

import top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static top.sywyar.pixivdownload.douyin.db.history.DouyinHistorySchema.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 通过 owner 私库契约访问历史；作品、文件和来源关系共享外层事务。 */
public class DouyinHistoryMapper {
    private static final List<String> WORK_COLUMNS = List.of(
            "work_id", "title", "folder", "count", "extensions", "time", "deleted", "kind",
            "source_url", "canonical_url", "thumbnail_url", "author_id", "author_name",
            "description", "item_title", "caption", "publish_time", "collection_id",
            "collection_title", "collection_order");
    private static final List<String> FILE_COLUMNS = List.of(
            "work_id", "file_index", "media_id", "media_type", "file_name", "extension",
            "bytes", "content_type", "created_time");
    private static final List<String> RELATION_COLUMNS = List.of(
            "work_id", "source_type", "source_id", "source_title", "source_url", "source_order", "discovered_time");
    private final PluginDatabase database;
    private final ThreadLocal<DatabaseSession> transactionSession = new ThreadLocal<>();

    public DouyinHistoryMapper(PluginDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public <T> T inTransaction(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (transactionSession.get() != null) return action.get();
        return execute(true, session -> {
            transactionSession.set(session);
            try { return action.get(); }
            finally { transactionSession.remove(); }
        });
    }

    public DouyinWorkRecord findActiveById(String workId) {
        return findWork(call(AND, byId(WORKS, workId), eq(WORKS, "deleted", 0)));
    }

    public DouyinWorkRecord findAnyById(String workId) { return findWork(byId(WORKS, workId)); }

    private DouyinWorkRecord findWork(Expression where) {
        var rows = query(new Query(WORKS, projection(WORKS, WORK_COLUMNS), where,
                List.of(), List.of(), 1, 0), this::readWork);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<DouyinWorkFileRecord> findFilesByWorkId(String workId) {
        return query(new Query(FILES, projection(FILES, FILE_COLUMNS), byId(FILES, workId),
                List.of(), List.of(order(FILES, "file_index", false)), Integer.MAX_VALUE, 0), this::readFile);
    }

    public List<DouyinWorkRecord> findActivePage(DouyinHistoryQuery query) {
        Expression sort = switch (query.sort()) {
            case "title" -> call(LOWER_ASCII, call(COALESCE,
                    call(NULL_IF, column(WORKS, "title"), value("")), column(WORKS, "work_id")));
            case "publishTime" -> column(WORKS, "publish_time");
            case "authorName" -> call(LOWER_ASCII, call(COALESCE,
                    call(NULL_IF, column(WORKS, "author_name"), value("")), column(WORKS, "author_id"), value("")));
            case "collectionOrder" -> column(WORKS, "collection_order");
            default -> column(WORKS, "time");
        };
        boolean descending = !"asc".equals(query.order());
        return query(new Query(WORKS, projection(WORKS, WORK_COLUMNS), filters(query), List.of(),
                List.of(new Query.Order(sort, descending, !descending),
                        order(WORKS, "time", true), order(WORKS, "work_id", false)),
                query.limit(), query.offset()), this::readWork);
    }

    public long countActive(DouyinHistoryQuery query) { return count(filters(query)); }

    public List<DouyinAuthorSummary> findAuthorFacets(DouyinHistoryQuery query) {
        var author = column(WORKS, "author_id");
        var name = call(COALESCE, call(NULL_IF, call(TRIM, column(WORKS, "author_name")), value("")), author);
        var source = new Query(WORKS, Map.of("author_id", author, "author_name", name),
                call(AND, filters(query), call(NOT, call(IS_NULL, author)),
                        call(NOT_EQUAL, call(TRIM, author), value(""))),
                List.of(), List.of(), Integer.MAX_VALUE, 0);
        var idColumn = column("authors", "author_id");
        var nameColumn = column("authors", "author_name");
        var total = call(COUNT);
        return query(new Query(new Query.Derived(source, "authors"), List.of(),
                Map.of("id", idColumn, "name", nameColumn, "count", total), null,
                List.of(idColumn, nameColumn), null, List.of(new Query.Order(total, true, false),
                        new Query.Order(call(LOWER_ASCII, nameColumn), false, true),
                        new Query.Order(idColumn, false, true)), false, query.limit(), 0),
                row -> new DouyinAuthorSummary(text(row, "id"), text(row, "name"), number(row, "count")));
    }

    public int insertWork(DouyinWorkRecord record) {
        return execute(true, s -> s.insert(WORKS, workValues(record), DatabaseSession.Conflict.ignore()));
    }

    public int updateActiveWork(DouyinWorkRecord record) {
        var changes = new LinkedHashMap<String, Expression>();
        workValues(record).forEach((key, val) -> { if (!key.equals("work_id")) changes.put(key, value(val)); });
        return execute(true, s -> s.update(WORKS, changes,
                call(AND, byId(WORKS, record.workId()), eq(WORKS, "deleted", 0))));
    }

    public int upsertFile(DouyinWorkFileRecord record) {
        var values = values(FILE_COLUMNS, record.workId(), record.fileIndex(), record.mediaId(),
                record.mediaType(), record.fileName(), record.extension(), record.bytes(),
                record.contentType(), record.createdTime());
        var changes = new LinkedHashMap<String, Expression>();
        FILE_COLUMNS.subList(2, FILE_COLUMNS.size()).forEach(key -> changes.put(key, new Incoming(key)));
        return execute(true, s -> s.insert(FILES, values, new DatabaseSession.Conflict(
                DatabaseSession.Mode.UPDATE, List.of("work_id", "file_index"), changes)));
    }

    public int upsertRelation(DouyinSourceRelation relation) {
        var values = values(RELATION_COLUMNS, relation.workId(), relation.sourceType(), relation.sourceId(),
                relation.sourceTitle(), relation.sourceUrl(), relation.sourceOrder(), relation.discoveredTime());
        return execute(true, s -> s.insert(RELATIONS, values, new DatabaseSession.Conflict(
                DatabaseSession.Mode.UPDATE, List.of("work_id", "source_type", "source_id"), Map.of(
                "source_title", call(COALESCE, new Incoming("source_title"), column(RELATIONS, "source_title")),
                "source_url", call(COALESCE, new Incoming("source_url"), column(RELATIONS, "source_url")),
                "source_order", call(COALESCE, column(RELATIONS, "source_order"), new Incoming("source_order")),
                "discovered_time", call(MINIMUM, column(RELATIONS, "discovered_time"), new Incoming("discovered_time"))))));
    }

    public List<DouyinSourceRelation> findRelationsByWorkId(String workId) {
        return query(new Query(RELATIONS, projection(RELATIONS, RELATION_COLUMNS), byId(RELATIONS, workId),
                List.of(), List.of(order(RELATIONS, "discovered_time", false),
                        order(RELATIONS, "source_type", false), order(RELATIONS, "source_id", false)),
                Integer.MAX_VALUE, 0), row -> new DouyinSourceRelation(
                text(row, "work_id"), text(row, "source_type"), text(row, "source_id"),
                text(row, "source_title"), text(row, "source_url"), integer(row, "source_order"),
                number(row, "discovered_time")));
    }

    public int backfillRelations() { return execute(true, DouyinHistorySchema::backfillRelations); }
    public int countById(String workId) { return Math.toIntExact(count(byId(WORKS, workId))); }
    public int countActiveById(String workId) {
        return Math.toIntExact(count(call(AND, byId(WORKS, workId), eq(WORKS, "deleted", 0))));
    }
    public int countDeletedById(String workId) {
        return Math.toIntExact(count(call(AND, byId(WORKS, workId), eq(WORKS, "deleted", 1))));
    }
    public int countByTime(long time) { return Math.toIntExact(count(eq(WORKS, "time", time))); }

    public Long findMaxTime() {
        return query(new Query(WORKS, Map.of("time", call(MAX, column(WORKS, "time"))),
                null, List.of(), List.of(), 1, 0), row -> number(row, "time")).get(0);
    }

    public int markDeletedById(String workId) {
        return execute(true, s -> s.update(WORKS, Map.of("deleted", value(1)), byId(WORKS, workId)));
    }

    public int deleteFilesByWorkId(String workId) {
        return execute(true, s -> s.delete(FILES, byId(FILES, workId)));
    }

    public int deleteRelationsByWorkId(String workId) {
        return execute(true, s -> s.delete(RELATIONS, byId(RELATIONS, workId)));
    }

    public int deleteFilesIfWorkMarkedDeleted(String workId) { return deleteDetailsIfDeleted(FILES, workId); }
    public int deleteRelationsIfWorkMarkedDeleted(String workId) { return deleteDetailsIfDeleted(RELATIONS, workId); }

    private int deleteDetailsIfDeleted(String table, String workId) {
        var deleted = new Exists(new Query(WORKS, Map.of("id", column(WORKS, "work_id")),
                call(AND, byId(WORKS, workId), eq(WORKS, "deleted", 1)),
                List.of(), List.of(), 1, 0));
        return execute(true, s -> s.delete(table, call(AND, byId(table, workId), deleted)));
    }

    public int deleteWorkIfMarkedDeleted(String workId) {
        return execute(true, s -> s.delete(WORKS, call(AND, byId(WORKS, workId), eq(WORKS, "deleted", 1))));
    }

    private static Expression filters(DouyinHistoryQuery query) {
        var conditions = new ArrayList<Expression>();
        conditions.add(eq(WORKS, "deleted", 0));
        if (query.search() != null) {
            var pattern = value("%" + query.search().replace("!", "!!") + "%");
            conditions.add(new Call(OR, List.of("work_id", "title", "item_title", "caption", "description", "author_name")
                    .stream().map(name -> (Expression) call(LIKE, column(WORKS, name), pattern)).toList()));
        }
        if (!query.authorIds().isEmpty()) conditions.add(in(WORKS, "author_id", query.authorIds()));
        if (!query.requiredMediaTypes().isEmpty()) {
            conditions.add(new Exists(new Query(FILES, Map.of("id", column(FILES, "work_id")),
                    call(AND, call(EQUAL, column(FILES, "work_id"), column(WORKS, "work_id")),
                            in(FILES, "media_type", query.requiredMediaTypes())),
                    List.of(), List.of(), 1, 0)));
        }
        return conditions.size() == 1 ? conditions.get(0) : new Call(AND, conditions);
    }

    private static Expression in(String table, String name, List<String> values) {
        var args = new ArrayList<Expression>();
        args.add(column(table, name));
        values.forEach(v -> args.add(value(v)));
        return new Call(IN, args);
    }

    private long count(Expression where) {
        return query(Query.count(WORKS, where), row -> number(row, "count")).get(0);
    }

    private <T> List<T> query(Query query, Function<Map<String, Object>, T> reader) {
        return execute(false, s -> s.query(query).stream().map(reader).toList());
    }

    private <T> T execute(boolean write, DatabaseSession.Work<T> action) {
        try {
            DatabaseSession active = transactionSession.get();
            if (active != null) return action.run(active);
            return write ? database.transaction(action) : database.read(action);
        } catch (SQLException failure) {
            throw new IllegalStateException("Douyin private database operation failed", failure);
        }
    }

    private DouyinWorkRecord readWork(Map<String, Object> row) {
        return new DouyinWorkRecord(
                text(row, "work_id"), text(row, "title"), text(row, "folder"), integer(row, "count"),
                text(row, "extensions"), number(row, "time"), number(row, "deleted") != 0, text(row, "kind"),
                text(row, "source_url"), text(row, "canonical_url"), text(row, "thumbnail_url"),
                text(row, "author_id"), text(row, "author_name"), text(row, "description"),
                text(row, "item_title"), text(row, "caption"), number(row, "publish_time"),
                text(row, "collection_id"), text(row, "collection_title"), integer(row, "collection_order"));
    }

    private DouyinWorkFileRecord readFile(Map<String, Object> row) {
        return new DouyinWorkFileRecord(text(row, "work_id"), integer(row, "file_index"),
                text(row, "media_id"), text(row, "media_type"), text(row, "file_name"),
                text(row, "extension"), number(row, "bytes"), text(row, "content_type"), number(row, "created_time"));
    }

    private static Map<String, Object> workValues(DouyinWorkRecord r) {
        return values(WORK_COLUMNS, r.workId(), r.title(), r.folder(), r.count(), r.extensions(), r.time(),
                r.deleted() ? 1 : 0, r.kind(), r.sourceUrl(), r.canonicalUrl(), r.thumbnailUrl(),
                r.authorId(), r.authorName(), r.description(), r.itemTitle(), r.caption(), r.publishTime(),
                r.collectionId(), r.collectionTitle(), r.collectionOrder());
    }

    private static Map<String, Object> values(List<String> columns, Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < columns.size(); i++) result.put(columns.get(i), values[i]);
        return result;
    }

    private static Map<String, Expression> projection(String table, List<String> columns) {
        var result = new LinkedHashMap<String, Expression>();
        columns.forEach(name -> result.put(name, column(table, name)));
        return result;
    }

    private static Expression byId(String table, String workId) { return eq(table, "work_id", workId); }
    private static Expression eq(String table, String name, Object val) { return call(EQUAL, column(table, name), value(val)); }
    private static Query.Order order(String table, String name, boolean descending) {
        return new Query.Order(column(table, name), descending, !descending);
    }
    private static String text(Map<String, Object> row, String key) { return (String) row.get(key); }
    private static Long number(Map<String, Object> row, String key) { return (Long) row.get(key); }
    private static Integer integer(Map<String, Object> row, String key) {
        Long value = number(row, key);
        return value == null ? null : Math.toIntExact(value);
    }
}
