package com.aitaskcenter.service;

import com.aitaskcenter.dto.PageResult;
import com.aitaskcenter.dto.WordCleanFacetItem;
import com.aitaskcenter.dto.WordCleanFacets;
import com.aitaskcenter.dto.WordCleanItem;
import com.aitaskcenter.dto.WordCleanSentenceItem;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WordCleanService {
    private static final List<DifficultyRange> DIFFICULTY_RANGES = List.of(
            new DifficultyRange("0-99", 0, 99),
            new DifficultyRange("100-199", 100, 199),
            new DifficultyRange("200-249", 200, 249),
            new DifficultyRange("250-299", 250, 299),
            new DifficultyRange("300-399", 300, 399),
            new DifficultyRange("400-449", 400, 449),
            new DifficultyRange("450-499", 450, 499),
            new DifficultyRange("500-549", 500, 549),
            new DifficultyRange("550-599", 550, 599),
            new DifficultyRange("600-649", 600, 649),
            new DifficultyRange("650-699", 650, 699),
            new DifficultyRange("700-749", 700, 749),
            new DifficultyRange("750-799", 750, 799),
            new DifficultyRange("800-899", 800, 899),
            new DifficultyRange("900-999", 900, 999));

    private final ConnectionConfigService connectionConfigService;

    public WordCleanService(ConnectionConfigService connectionConfigService) {
        this.connectionConfigService = connectionConfigService;
    }

    public PageResult<WordCleanItem> list(
            Long connectionId,
            String keyword,
            Integer pepDifficulty,
            Integer sourceDifficulty,
            Integer difficultyMin,
            Integer difficultyMax,
            String sortBy,
            String sortOrder,
            int page,
            int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 500);
        try (Connection connection = connectionConfigService.openConfiguredConnection(connectionId)) {
            requireTable(connection, "word_clean");
            boolean hasBestSentence = tableExists(connection, "word_clean_best_sentence");
            boolean hasWordTts = tableExists(connection, "word_clean_tts");
            QueryFilter filter = buildFilter(keyword, pepDifficulty, sourceDifficulty, difficultyMin, difficultyMax);

            long total = queryCount(connection, filter);
            String sql = buildListSql(hasBestSentence, hasWordTts, filter.sql(), sortBy, sortOrder);
            List<Object> args = new ArrayList<>(filter.args());
            args.add(safePageSize);
            args.add((safePage - 1) * safePageSize);
            List<WordCleanItem> items = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        items.add(mapWordCleanItem(rs));
                    }
                }
            }
            return new PageResult<>(items, total, safePage, safePageSize);
        } catch (SQLException ex) {
            throw readFailure(ex);
        }
    }

    public List<WordCleanSentenceItem> sentences(Long connectionId, long wordCleanId) {
        if (wordCleanId <= 0) {
            throw new IllegalArgumentException("去重单词 ID 不正确");
        }
        try (Connection connection = connectionConfigService.openConfiguredConnection(connectionId)) {
            requireTable(connection, "word_clean_sentence");
            String sql = """
                    SELECT id,
                           word_clean_id,
                           COALESCE(word, '') AS word,
                           COALESCE(model_name, '') AS model_name,
                           COALESCE(sentence, '') AS sentence,
                           COALESCE(sentence_translation, '') AS sentence_translation,
                           score,
                           COALESCE(score_reason, '') AS score_reason,
                           COALESCE(score_model_name, '') AS score_model_name,
                           scored_at
                    FROM word_clean_sentence
                    WHERE word_clean_id = ?
                    ORDER BY id DESC
                    """;
            List<WordCleanSentenceItem> items = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, wordCleanId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        items.add(new WordCleanSentenceItem(
                                rs.getLong("id"),
                                rs.getLong("word_clean_id"),
                                text(rs, "word"),
                                text(rs, "model_name"),
                                text(rs, "sentence"),
                                text(rs, "sentence_translation"),
                                nullableInt(rs, "score"),
                                text(rs, "score_reason"),
                                text(rs, "score_model_name"),
                                timestamp(rs, "scored_at")));
                    }
                }
            }
            return items;
        } catch (SQLException ex) {
            throw readFailure(ex);
        }
    }

    public WordCleanFacets facets(Long connectionId) {
        try (Connection connection = connectionConfigService.openConfiguredConnection(connectionId)) {
            requireTable(connection, "word_clean");
            List<WordCleanFacetItem> pep = queryFacets(
                    connection,
                    "pep_difficulty",
                    "pep_difficulty_label");
            List<WordCleanFacetItem> source = queryFacets(
                    connection,
                    "source_difficulty",
                    "source_difficulty_label");
            List<WordCleanFacetItem> ranges = new ArrayList<>();
            String sql = "SELECT COUNT(*) FROM word_clean WHERE difficulty BETWEEN ? AND ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (DifficultyRange range : DIFFICULTY_RANGES) {
                    statement.setInt(1, range.min());
                    statement.setInt(2, range.max());
                    try (ResultSet rs = statement.executeQuery()) {
                        rs.next();
                        ranges.add(new WordCleanFacetItem(range.value(), range.value(), rs.getLong(1)));
                    }
                }
            }
            return new WordCleanFacets(pep, source, ranges);
        } catch (SQLException ex) {
            throw readFailure(ex);
        }
    }

    static QueryFilter buildFilter(
            String keyword,
            Integer pepDifficulty,
            Integer sourceDifficulty,
            Integer difficultyMin,
            Integer difficultyMax) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            clauses.add("(LOWER(COALESCE(wc.word, '')) LIKE ? OR LOWER(COALESCE(wc.meaning, '')) LIKE ? "
                    + "OR LOWER(COALESCE(wc.sentence, '')) LIKE ? OR LOWER(COALESCE(wc.pep_difficulty_label, '')) LIKE ? "
                    + "OR LOWER(COALESCE(wc.source_difficulty_label, '')) LIKE ?)");
            for (int i = 0; i < 5; i++) {
                args.add(like);
            }
        }
        if (pepDifficulty != null && pepDifficulty > 0) {
            clauses.add("wc.pep_difficulty = ?");
            args.add(pepDifficulty);
        }
        if (sourceDifficulty != null && sourceDifficulty > 0) {
            clauses.add("wc.source_difficulty = ?");
            args.add(sourceDifficulty);
        }
        if (difficultyMin != null && difficultyMin >= 0) {
            clauses.add("wc.difficulty >= ?");
            args.add(difficultyMin);
        }
        if (difficultyMax != null && difficultyMax >= 0) {
            clauses.add("wc.difficulty <= ?");
            args.add(difficultyMax);
        }
        return new QueryFilter(clauses.isEmpty() ? "" : " AND " + String.join(" AND ", clauses), args);
    }

    static String orderBy(String sortBy, String sortOrder) {
        String column = switch (sortBy == null ? "" : sortBy.trim()) {
            case "difficulty" -> "wc.difficulty";
            case "frequency" -> "wc.frequency";
            case "pepDifficulty" -> "wc.pep_difficulty";
            case "sourceDifficulty" -> "wc.source_difficulty";
            default -> null;
        };
        if (column == null) {
            return "wc.id ASC";
        }
        String direction = "desc".equalsIgnoreCase(sortOrder) ? "DESC" : "ASC";
        return column + " " + direction + " NULLS LAST, wc.id ASC";
    }

    private static long queryCount(Connection connection, QueryFilter filter) throws SQLException {
        String sql = "SELECT COUNT(*) FROM word_clean wc WHERE 1=1" + filter.sql();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, filter.args());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String buildListSql(
            boolean hasBestSentence,
            boolean hasWordTts,
            String filterSql,
            String sortBy,
            String sortOrder) {
        String bestFields = hasBestSentence
                ? "wcbs.id AS best_sentence_id, wcbs.source_sentence_id AS best_source_sentence_id, "
                        + "COALESCE(wcbs.source_model_name, '') AS best_source_model_name, "
                        + "COALESCE(wcbs.sentence, '') AS best_sentence, "
                        + "COALESCE(wcbs.sentence_translation, '') AS best_sentence_translation, "
                        + "wcbs.score AS best_sentence_score, COALESCE(wcbs.score_reason, '') AS best_sentence_score_reason, "
                        + "COALESCE(wcbs.score_model_name, '') AS best_sentence_score_model_name, wcbs.scored_at AS best_sentence_scored_at, "
                        + "COALESCE(wcbs.tts_status, '') AS best_sentence_tts_status, "
                        + "COALESCE(wcbs.tts_object_url, '') AS best_sentence_tts_object_url"
                : "NULL AS best_sentence_id, NULL AS best_source_sentence_id, '' AS best_source_model_name, "
                        + "'' AS best_sentence, '' AS best_sentence_translation, NULL AS best_sentence_score, "
                        + "'' AS best_sentence_score_reason, '' AS best_sentence_score_model_name, NULL AS best_sentence_scored_at, "
                        + "'' AS best_sentence_tts_status, '' AS best_sentence_tts_object_url";
        String wordTtsFields = hasWordTts
                ? "COALESCE(wct.status, '') AS word_tts_status, COALESCE(wct.tts_object_url, '') AS word_tts_object_url"
                : "'' AS word_tts_status, '' AS word_tts_object_url";
        String bestJoin = hasBestSentence
                ? " LEFT JOIN word_clean_best_sentence wcbs ON wcbs.word_clean_id = wc.id"
                : "";
        String wordTtsJoin = hasWordTts
                ? " LEFT JOIN word_clean_tts wct ON wct.word_clean_id = wc.id"
                : "";
        return "SELECT wc.id, wc.word, wc.meaning, wc.difficulty, wc.frequency, "
                + "COALESCE(wc.sentence, '') AS sentence, wc.pep_difficulty, "
                + "COALESCE(wc.pep_difficulty_label, '') AS pep_difficulty_label, wc.source_difficulty, "
                + "COALESCE(wc.source_difficulty_label, '') AS source_label, "
                + bestFields + ", " + wordTtsFields
                + " FROM word_clean wc" + bestJoin + wordTtsJoin
                + " WHERE 1=1" + filterSql
                + " ORDER BY " + orderBy(sortBy, sortOrder) + " LIMIT ? OFFSET ?";
    }

    private static WordCleanItem mapWordCleanItem(ResultSet rs) throws SQLException {
        return new WordCleanItem(
                rs.getLong("id"),
                text(rs, "word"),
                text(rs, "meaning"),
                rs.getInt("difficulty"),
                rs.getInt("frequency"),
                text(rs, "sentence"),
                nullableInt(rs, "pep_difficulty"),
                text(rs, "pep_difficulty_label"),
                nullableInt(rs, "source_difficulty"),
                text(rs, "source_label"),
                nullableLong(rs, "best_sentence_id"),
                nullableLong(rs, "best_source_sentence_id"),
                text(rs, "best_source_model_name"),
                text(rs, "best_sentence"),
                text(rs, "best_sentence_translation"),
                nullableInt(rs, "best_sentence_score"),
                text(rs, "best_sentence_score_reason"),
                text(rs, "best_sentence_score_model_name"),
                timestamp(rs, "best_sentence_scored_at"),
                text(rs, "best_sentence_tts_status"),
                text(rs, "best_sentence_tts_object_url"),
                text(rs, "word_tts_status"),
                text(rs, "word_tts_object_url"));
    }

    private static List<WordCleanFacetItem> queryFacets(Connection connection, String valueColumn, String labelColumn)
            throws SQLException {
        String sql = "SELECT " + valueColumn + ", COALESCE(MAX(" + labelColumn + "), ''), COUNT(*) "
                + "FROM word_clean WHERE " + valueColumn + " IS NOT NULL GROUP BY " + valueColumn
                + " ORDER BY " + valueColumn;
        List<WordCleanFacetItem> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String value = String.valueOf(rs.getInt(1));
                String label = rs.getString(2);
                items.add(new WordCleanFacetItem(value, StringUtils.hasText(label) ? label : value, rs.getLong(3)));
            }
        }
        return items;
    }

    private static void requireTable(Connection connection, String tableName) throws SQLException {
        if (!tableExists(connection, tableName)) {
            throw new IllegalArgumentException("所选数据源中不存在表 " + tableName);
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getTables(connection.getCatalog(), null, tableName, new String[] {"TABLE"})) {
            if (rs.next()) {
                return true;
            }
        }
        try (ResultSet rs = metaData.getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                if (tableName.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void bind(PreparedStatement statement, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            statement.setObject(i + 1, args.get(i));
        }
    }

    private static String text(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? "" : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? "" : value.toInstant().toString();
    }

    private static IllegalArgumentException readFailure(SQLException ex) {
        return new IllegalArgumentException("读取去重单词表失败: " + ex.getMessage());
    }

    record QueryFilter(String sql, List<Object> args) {
    }

    private record DifficultyRange(String value, int min, int max) {
    }
}
