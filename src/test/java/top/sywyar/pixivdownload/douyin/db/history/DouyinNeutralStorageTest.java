package top.sywyar.pixivdownload.douyin.db.history;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("Douyin 中性私库的持久化语义")
class DouyinNeutralStorageTest {
    @TempDir Path directory;

    @Test @DisplayName("64 位数值、空值和冲突合并在同一契约下保留")
    void conflictsAndLosslessValues() throws Exception {
        try (var database = new TestPluginDatabase(directory.resolve("history.db"))) {
            var mapper = new DouyinHistoryMapper(database);
            long large = 9_007_199_254_740_993L;
            assertThat(mapper.findMaxTime()).isNull();
            assertThat(mapper.insertWork(work("first", large, null))).isEqualTo(1);
            assertThat(mapper.insertWork(work("first", large + 1, 4L))).isZero();
            assertThat(mapper.insertWork(work("same-time", large, 4L))).isZero();
            assertThat(mapper.findAnyById("first").time()).isEqualTo(large);
            assertThat(mapper.findMaxTime()).isEqualTo(large);
            mapper.upsertFile(new DouyinWorkFileRecord("first", 0, "old", "VIDEO", "old.mp4", "mp4", large, "video/mp4", large));
            mapper.upsertFile(new DouyinWorkFileRecord("first", 0, null, "VIDEO", "new.mp4", "mp4", null, null, large + 2));
            assertThat(mapper.findFilesByWorkId("first")).singleElement().satisfies(row -> {
                assertThat(row.fileName()).isEqualTo("new.mp4");
                assertThat(row.mediaId()).isNull();
                assertThat(row.bytes()).isNull();
                assertThat(row.createdTime()).isEqualTo(large + 2);
            });
            mapper.upsertRelation(new DouyinSourceRelation("first", "douyin.user", "author", "old", "url", null, large + 3));
            mapper.upsertRelation(new DouyinSourceRelation("first", "douyin.user", "author", null, "new", 2, large));
            mapper.upsertRelation(new DouyinSourceRelation("first", "douyin.user", "author", "title", null, 9, large + 1));
            assertThat(mapper.findRelationsByWorkId("first")).singleElement().satisfies(row -> {
                assertThat(row.sourceTitle()).isEqualTo("title");
                assertThat(row.sourceUrl()).isEqualTo("new");
                assertThat(row.sourceOrder()).isEqualTo(2);
                assertThat(row.discoveredTime()).isEqualTo(large);
            });
        }
    }

    @Test @DisplayName("嵌套业务操作加入外层事务，异常撤回三张表且后续事务可用")
    void wholeWorkRollsBack() throws Exception {
        try (var database = new TestPluginDatabase(directory.resolve("history.db"))) {
            var mapper = new DouyinHistoryMapper(database);
            assertThatThrownBy(() -> mapper.inTransaction(() -> {
                mapper.insertWork(work("rollback", 1, null));
                mapper.inTransaction(() -> mapper.upsertFile(new DouyinWorkFileRecord(
                        "rollback", 0, null, "VIDEO", "file.mp4", "mp4", null, null, 1)));
                mapper.upsertRelation(new DouyinSourceRelation("rollback", "douyin.single", "rollback", null, null, null, 1));
                throw new IllegalArgumentException("fixture");
            })).isInstanceOf(IllegalArgumentException.class);
            assertThat(mapper.findAnyById("rollback")).isNull();
            assertThat(mapper.findFilesByWorkId("rollback")).isEmpty();
            assertThat(mapper.findRelationsByWorkId("rollback")).isEmpty();
            assertThat(mapper.insertWork(work("next", 2, null))).isEqualTo(1);
        }
    }

    @Test @DisplayName("捕获约束错误仍不可提交外层事务，IGNORE 不吞掉必填字段错误")
    void caughtFailureCannotCommit() throws Exception {
        try (var database = new TestPluginDatabase(directory.resolve("history.db"))) {
            var mapper = new DouyinHistoryMapper(database);
            assertThatThrownBy(() -> mapper.inTransaction(() -> {
                mapper.insertWork(work("rollback", 1, null));
                try {
                    mapper.upsertFile(new DouyinWorkFileRecord("rollback", 0, null, null, "a.mp4", "mp4", null, null, 1));
                } catch (IllegalArgumentException expected) { /* 业务捕获不能恢复事务。 */ }
                return null;
            })).isInstanceOf(IllegalStateException.class);
            assertThat(mapper.findAnyById("rollback")).isNull();
            var invalid = work("invalid", 3, null).withFolder(null);
            assertThatThrownBy(() -> mapper.insertWork(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThat(mapper.findAnyById("invalid")).isNull();
        }
    }

    @Test @DisplayName("NULL 排序与 ASCII 搜索保留既有语义和稳定分页")
    void orderingAndPatternSearch() throws Exception {
        try (var database = new TestPluginDatabase(directory.resolve("history.db"))) {
            var mapper = new DouyinHistoryMapper(database);
            mapper.insertWork(work("a!_中文", 1, null));
            mapper.insertWork(work("BETA", 2, 10L));
            mapper.insertWork(work("gamma", 3, 10L));
            var ascending = new DouyinHistoryQuery(0, 10, "publishTime", "asc", null, List.of());
            assertThat(mapper.findActivePage(ascending)).extracting(DouyinWorkRecord::workId)
                    .containsExactly("a!_中文", "gamma", "BETA");
            assertThat(mapper.findActivePage(new DouyinHistoryQuery(1, 1, "publishTime", "desc", null, List.of())))
                    .extracting(DouyinWorkRecord::workId).containsExactly("BETA");
            for (String term : List.of("beta", "BET_", "be%")) {
                assertThat(mapper.countActive(new DouyinHistoryQuery(0, 10, null, null, term, List.of()))).isEqualTo(1);
            }
            assertThat(mapper.countActive(new DouyinHistoryQuery(0, 10, null, null, "!_", List.of()))).isEqualTo(1);
            assertThat(mapper.countActive(new DouyinHistoryQuery(0, 10, null, null, "中文", List.of()))).isEqualTo(1);
        }
    }

    @Test @DisplayName("已完成的转换不在后续启动重复执行")
    void conversionRunsOnlyOnce() throws Exception {
        try (var database = new TestPluginDatabase(directory.resolve("history.db"))) {
            var mapper = new DouyinHistoryMapper(database);
            mapper.insertWork(work("new", 1, null));
            database.initialize();
            assertThat(mapper.findRelationsByWorkId("new")).isEmpty();
        }
    }

    @Test @DisplayName("不可用存储不阻止 Bean 构造，读写明确失败")
    void unavailableStorage() throws Exception {
        var database = new TestPluginDatabase(directory.resolve("history.db"));
        database.close();
        var mapper = new DouyinHistoryMapper(database);
        assertThatCode(() -> new DouyinHistoryService(new DouyinHistoryRepository(mapper, null)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> mapper.insertWork(work("unavailable", 1, null)))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> mapper.findAnyById("unavailable")).isInstanceOf(IllegalStateException.class);
    }

    static DouyinWorkRecord work(String id, long time, Long publishTime) {
        return new DouyinWorkRecord(id, id, "{douyin}/" + id, 1, "mp4", time, false, "VIDEO",
                " ", "canonical", null, "author", " Author ", null, null, null, publishTime, null, null, null);
    }
}
