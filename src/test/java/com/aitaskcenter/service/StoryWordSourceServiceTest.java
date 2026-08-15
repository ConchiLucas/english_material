package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.StoryRunDtos.StoryWord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.DatabaseMetaData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StoryWordSourceServiceTest {
    private ConnectionConfigService connectionService;
    private Connection connection;
    private StoryWordSourceService service;

    @BeforeEach
    void setUp() throws Exception {
        connectionService = mock(ConnectionConfigService.class);
        connection = mock(Connection.class);
        when(connectionService.openConfiguredConnection(9L)).thenReturn(connection);
        service = new StoryWordSourceService(connectionService);
    }

    @Test
    void normalizesManualWordsAndDeduplicatesCaseInsensitively() {
        List<StoryWord> result = service.normalizeManualWords(List.of(
                new StoryWord(" Book ", " 书 "),
                new StoryWord("book", "书本"),
                new StoryWord("GREEN", "绿色")));

        assertEquals(List.of("Book", "GREEN"), result.stream().map(StoryWord::word).toList());
        assertEquals(List.of("书", "绿色"), result.stream().map(StoryWord::meaning).toList());
    }

    @Test
    void rejectsManualWordCountsOutsideOneToFifty() {
        assertEquals("请至少输入 1 个单词", assertThrows(
                IllegalArgumentException.class,
                () -> service.normalizeManualWords(List.of())).getMessage());
        List<StoryWord> tooMany = java.util.stream.IntStream.rangeClosed(1, 51)
                .mapToObj(index -> new StoryWord("word" + index, ""))
                .toList();
        assertEquals("一次最多使用 50 个单词", assertThrows(
                IllegalArgumentException.class,
                () -> service.normalizeManualWords(tooMany)).getMessage());
    }

    @Test
    void rejectsOversizedManualFieldsAfterTrimmingAndAcceptsExactLimits() {
        assertEquals("单词长度不能超过 120 个字符", assertThrows(
                IllegalArgumentException.class,
                () -> service.normalizeManualWords(List.of(new StoryWord(" " + "w".repeat(121) + " ", "meaning"))))
                .getMessage());
        assertEquals("单词释义长度不能超过 500 个字符", assertThrows(
                IllegalArgumentException.class,
                () -> service.normalizeManualWords(List.of(new StoryWord("word", " " + "m".repeat(501) + " "))))
                .getMessage());

        List<StoryWord> boundary = service.normalizeManualWords(List.of(
                new StoryWord(" " + "w".repeat(120) + " ", " " + "m".repeat(500) + " ")));

        assertEquals(120, boundary.get(0).word().length());
        assertEquals(500, boundary.get(0).meaning().length());
    }

    @Test
    void selectsRandomWordsWithParameterizedLibraryAndLimit() throws Exception {
        PreparedStatement libraryStatement = mock(PreparedStatement.class);
        PreparedStatement wordStatement = mock(PreparedStatement.class);
        ResultSet libraryRows = mock(ResultSet.class);
        ResultSet wordRows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(libraryStatement, wordStatement);
        when(libraryStatement.executeQuery()).thenReturn(libraryRows);
        when(libraryRows.next()).thenReturn(true);
        when(wordStatement.executeQuery()).thenReturn(wordRows);
        when(wordRows.next()).thenReturn(true, true, false);
        when(wordRows.getString("word")).thenReturn("book", "green");
        when(wordRows.getString("meaning")).thenReturn("书", "绿色");

        List<StoryWord> result = service.randomWords(9L, 21L, 2);

        assertEquals(List.of("book", "green"), result.stream().map(StoryWord::word).toList());
        verify(libraryStatement).setLong(1, 21L);
        verify(wordStatement).setLong(1, 21L);
        verify(wordStatement).setInt(2, 2);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, org.mockito.Mockito.times(2)).prepareStatement(sql.capture());
        assertTrue(sql.getAllValues().get(1).contains("ORDER BY RANDOM()"));
        assertTrue(sql.getAllValues().get(1).contains("LIMIT ?"));
    }

    @Test
    void appliesTheSameFieldLimitsToRandomDatabaseWords() throws Exception {
        PreparedStatement libraryStatement = mock(PreparedStatement.class);
        PreparedStatement wordStatement = mock(PreparedStatement.class);
        ResultSet libraryRows = mock(ResultSet.class);
        ResultSet wordRows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(libraryStatement, wordStatement);
        when(libraryStatement.executeQuery()).thenReturn(libraryRows);
        when(libraryRows.next()).thenReturn(true);
        when(wordStatement.executeQuery()).thenReturn(wordRows);
        when(wordRows.next()).thenReturn(true, false);
        when(wordRows.getString("word")).thenReturn("w".repeat(121));
        when(wordRows.getString("meaning")).thenReturn("meaning");

        assertEquals("单词长度不能超过 120 个字符", assertThrows(
                IllegalArgumentException.class,
                () -> service.randomWords(9L, 21L, 1)).getMessage());
    }

    @Test
    void rejectsUnknownLibraryAndInvalidRandomCount() throws Exception {
        assertEquals("随机数量必须在 1 到 50 之间", assertThrows(
                IllegalArgumentException.class,
                () -> service.randomWords(9L, 21L, 0)).getMessage());

        PreparedStatement libraryStatement = mock(PreparedStatement.class);
        ResultSet libraryRows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(libraryStatement);
        when(libraryStatement.executeQuery()).thenReturn(libraryRows);
        when(libraryRows.next()).thenReturn(false);

        assertEquals("词库不存在或已停用", assertThrows(
                IllegalArgumentException.class,
                () -> service.randomWords(9L, 404L, 20)).getMessage());
    }

    @Test
    void usesMysqlRandomFunctionForMysqlConnections() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        PreparedStatement libraryStatement = mock(PreparedStatement.class);
        PreparedStatement wordStatement = mock(PreparedStatement.class);
        ResultSet libraryRows = mock(ResultSet.class);
        ResultSet wordRows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(libraryStatement, wordStatement);
        when(libraryStatement.executeQuery()).thenReturn(libraryRows);
        when(libraryRows.next()).thenReturn(true);
        when(wordStatement.executeQuery()).thenReturn(wordRows);
        when(wordRows.next()).thenReturn(true, false);
        when(wordRows.getString("word")).thenReturn("book");
        when(wordRows.getString("meaning")).thenReturn("书");

        service.randomWords(9L, 21L, 1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, org.mockito.Mockito.times(2)).prepareStatement(sql.capture());
        assertTrue(sql.getAllValues().get(1).contains("ORDER BY RAND()"));
    }
}
