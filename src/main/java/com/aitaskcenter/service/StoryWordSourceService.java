package com.aitaskcenter.service;

import com.aitaskcenter.dto.StoryRunDtos.StoryWord;
import com.aitaskcenter.dto.StoryRunDtos.WordLibraryView;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class StoryWordSourceService {
    private final ConnectionConfigService connectionService;

    public StoryWordSourceService(ConnectionConfigService connectionService) {
        this.connectionService = connectionService;
    }

    public List<StoryWord> normalizeManualWords(List<StoryWord> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("请至少输入 1 个单词");
        }
        if (requested.size() > 50) {
            throw new IllegalArgumentException("一次最多使用 50 个单词");
        }
        Map<String, StoryWord> unique = new LinkedHashMap<>();
        for (StoryWord item : requested) {
            String word = item == null ? "" : clean(item.word());
            if (!StringUtils.hasText(word)) {
                throw new IllegalArgumentException("单词不能为空");
            }
            unique.putIfAbsent(word.toLowerCase(Locale.ROOT), new StoryWord(word, clean(item.meaning())));
        }
        return List.copyOf(unique.values());
    }

    public List<WordLibraryView> listLibraries(Long connectionId) {
        requireConnectionId(connectionId);
        String sql = """
                SELECT id, library_name, COALESCE(library_meaning, '') AS library_meaning, word_count
                FROM word_library
                WHERE status = 1
                ORDER BY library_name, id
                """;
        try (Connection connection = connectionService.openConfiguredConnection(connectionId);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<WordLibraryView> result = new ArrayList<>();
            while (rows.next()) {
                result.add(new WordLibraryView(
                        rows.getLong("id"),
                        rows.getString("library_name"),
                        rows.getString("library_meaning"),
                        rows.getInt("word_count")));
            }
            return result;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("读取词库失败: " + ex.getMessage(), ex);
        }
    }

    public List<StoryWord> randomWords(Long connectionId, Long libraryId, Integer count) {
        requireConnectionId(connectionId);
        if (libraryId == null || libraryId <= 0) {
            throw new IllegalArgumentException("请选择词库");
        }
        if (count == null || count < 1 || count > 50) {
            throw new IllegalArgumentException("随机数量必须在 1 到 50 之间");
        }
        String librarySql = "SELECT 1 FROM word_library WHERE id = ? AND status = 1";
        try (Connection connection = connectionService.openConfiguredConnection(connectionId);
             PreparedStatement library = connection.prepareStatement(librarySql)) {
            library.setLong(1, libraryId);
            try (ResultSet rows = library.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("词库不存在或已停用");
                }
            }
            String wordSql = randomWordSql(databaseProduct(connection));
            try (PreparedStatement words = connection.prepareStatement(wordSql)) {
                words.setLong(1, libraryId);
                words.setInt(2, count);
                try (ResultSet rows = words.executeQuery()) {
                    List<StoryWord> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(new StoryWord(rows.getString("word"), rows.getString("meaning")));
                    }
                    if (result.isEmpty()) {
                        throw new IllegalArgumentException("所选词库没有可用单词");
                    }
                    return result;
                }
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("随机读取单词失败: " + ex.getMessage(), ex);
        }
    }

    private static String databaseProduct(Connection connection) {
        try {
            return connection.getMetaData() == null ? "" : clean(connection.getMetaData().getDatabaseProductName());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String randomWordSql(String product) {
        String normalized = product.toLowerCase(Locale.ROOT);
        if (normalized.contains("mysql") || normalized.contains("mariadb")) {
            return "SELECT word, meaning FROM word WHERE library_id = ? AND status = 1 ORDER BY RAND() LIMIT ?";
        }
        if (normalized.contains("microsoft") || normalized.contains("sql server")) {
            return "SELECT word, meaning FROM word WHERE library_id = ? AND status = 1 "
                    + "ORDER BY NEWID() OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY";
        }
        if (normalized.contains("oracle")) {
            return "SELECT word, meaning FROM word WHERE library_id = ? AND status = 1 "
                    + "ORDER BY DBMS_RANDOM.VALUE FETCH FIRST ? ROWS ONLY";
        }
        return "SELECT word, meaning FROM word WHERE library_id = ? AND status = 1 ORDER BY RANDOM() LIMIT ?";
    }

    private static void requireConnectionId(Long connectionId) {
        if (connectionId == null || connectionId <= 0) {
            throw new IllegalArgumentException("请选择数据库连接");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
