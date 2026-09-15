/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.danmorcov.nifi.informix;

import org.apache.nifi.database.dialect.service.api.ColumnDefinition;
import org.apache.nifi.database.dialect.service.api.PageRequest;
import org.apache.nifi.database.dialect.service.api.StandardColumnDefinition;
import org.apache.nifi.database.dialect.service.api.StandardPageRequest;
import org.apache.nifi.database.dialect.service.api.StandardQueryStatementRequest;
import org.apache.nifi.database.dialect.service.api.StandardStatementRequest;
import org.apache.nifi.database.dialect.service.api.StatementType;
import org.apache.nifi.database.dialect.service.api.TableDefinition;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.NoOpProcessor;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Executes every statement the dialect generates against a real Informix server.
 * Run with: mvn verify -Pintegration-tests
 */
@Testcontainers
class InformixDatabaseDialectServiceIT {

    @Container
    private static final InformixContainer INFORMIX = new InformixContainer();

    private static final String DATABASE = "nifitest";

    private static final String DELIMIDENT = ";DELIMIDENT=Y";

    /** Default length of an LVARCHAR column created by the dialect; longer values are silently truncated by Informix */
    private static final int LVARCHAR_DEFAULT_LENGTH = 2048;

    private static final String LONG_LABEL = "x".repeat(LVARCHAR_DEFAULT_LENGTH);

    private static final String OVERSIZED_LABEL = "y".repeat(LVARCHAR_DEFAULT_LENGTH + 1000);

    private static final ColumnDefinition ID = new StandardColumnDefinition("id", Types.INTEGER, ColumnDefinition.Nullable.NO, true);

    private static final ColumnDefinition REGION = new StandardColumnDefinition("region", Types.VARCHAR, ColumnDefinition.Nullable.NO, true);

    private static final ColumnDefinition AMOUNT = new StandardColumnDefinition("amount", Types.DECIMAL, ColumnDefinition.Nullable.YES, false);

    /** LONGVARCHAR is what the Informix driver reports for LVARCHAR columns, and what PutDatabaseRecord binds with */
    private static final ColumnDefinition LABEL = new StandardColumnDefinition("label", Types.LONGVARCHAR, ColumnDefinition.Nullable.YES, false);

    private static final ColumnDefinition UPDATED = new StandardColumnDefinition("updated", Types.TIMESTAMP, ColumnDefinition.Nullable.YES, false);

    private static final ColumnDefinition ACTIVE = new StandardColumnDefinition("active", Types.BOOLEAN, ColumnDefinition.Nullable.YES, false);

    private TestRunner runner;

    private InformixDatabaseDialectService service;

    @BeforeAll
    static void createDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(INFORMIX.getJdbcUrl(), INFORMIX.getUsername(), INFORMIX.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE %s WITH LOG".formatted(DATABASE));
        }
    }

    @BeforeEach
    void setService() throws InitializationException {
        runner = TestRunners.newTestRunner(NoOpProcessor.class);
        service = new InformixDatabaseDialectService();
        runner.addControllerService(InformixDatabaseDialectService.class.getSimpleName(), service);
    }

    @Test
    void testCreateAndAlterTable() throws SQLException {
        runner.enableControllerService(service);
        final String tableName = "orders_ddl";
        final TableDefinition table = table(tableName, ID, REGION, AMOUNT, UPDATED, ACTIVE);

        try (Connection connection = connect("")) {
            execute(connection, sql(new StandardStatementRequest(StatementType.CREATE, table)));

            final ColumnDefinition note = new StandardColumnDefinition("note", Types.LONGVARCHAR, ColumnDefinition.Nullable.YES, false);
            final ColumnDefinition qty = new StandardColumnDefinition("qty", Types.BIGINT, ColumnDefinition.Nullable.YES, false);
            execute(connection, sql(new StandardStatementRequest(StatementType.ALTER, table(tableName, note, qty))));

            final Map<String, String> columnTypes = getColumnTypes(connection, tableName);
            assertEquals(List.of("id", "region", "amount", "updated", "active", "note", "qty"), new ArrayList<>(columnTypes.keySet()));
            assertEquals("integer", columnTypes.get("id"));
            assertEquals("varchar", columnTypes.get("region"));
            assertEquals("decimal", columnTypes.get("amount"));
            assertEquals("datetime year to fraction(5)", columnTypes.get("updated"));
            assertEquals("boolean", columnTypes.get("active"));
            assertEquals("lvarchar", columnTypes.get("note"));
            assertEquals("bigint", columnTypes.get("qty"));
            assertEquals(List.of("id", "region"), getPrimaryKeyColumns(connection, tableName));
        }
    }

    @Test
    void testSelectPaging() throws SQLException {
        runner.enableControllerService(service);
        final String tableName = "orders_select";
        final TableDefinition table = table(tableName, ID, LABEL);

        try (Connection connection = connect("")) {
            execute(connection, sql(new StandardStatementRequest(StatementType.CREATE, table)));
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO %s (id, label) VALUES (?, ?)".formatted(tableName))) {
                for (int id = 1; id <= 10; id++) {
                    insert.setInt(1, id);
                    insert.setString(2, "label-" + id);
                    insert.addBatch();
                }
                insert.executeBatch();
            }

            assertEquals(List.of(3, 4, 5), selectIds(connection, sql(select(table, "1=1", "id", page(2, 3, null)))));
            assertEquals(List.of(1, 2, 3), selectIds(connection, sql(select(table, "1=1", "id", page(0, 3, null)))));
            assertEquals(List.of(9, 10), selectIds(connection, sql(select(table, null, "id", page(8, null, null)))));
            assertEquals(List.of(4, 5, 6), selectIds(connection, sql(select(table, "label IS NOT NULL", "id", page(4, 3, "id")))));
            assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), selectIds(connection, sql(select(table, null, "id", null))));

            final String customQuery = "SELECT id FROM %s WHERE id > 5".formatted(tableName);
            final TableDefinition derived = table(tableName);
            final String schemaQuery = sql(new StandardQueryStatementRequest(
                    StatementType.SELECT, derived, Optional.of(customQuery), Optional.of("1 = 0"), Optional.empty(), Optional.empty()));
            try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(schemaQuery)) {
                assertEquals("id", resultSet.getMetaData().getColumnName(1));
                assertFalse(resultSet.next());
            }
            final String pagedDerived = sql(new StandardQueryStatementRequest(
                    StatementType.SELECT, derived, Optional.of(customQuery), Optional.empty(), Optional.of("id"), Optional.of(page(1, 2, null))));
            assertEquals(List.of(7, 8), selectIds(connection, pagedDerived));
        }
    }

    @Test
    void testUpsertAndInsertIgnore() throws SQLException {
        runner.enableControllerService(service);
        final String tableName = "orders_merge";
        final TableDefinition table = table(tableName, ID, REGION, AMOUNT, LABEL, UPDATED, ACTIVE);
        final Timestamp updated = Timestamp.valueOf("2026-09-15 13:00:00.12345");

        try (Connection connection = connect("")) {
            execute(connection, sql(new StandardStatementRequest(StatementType.CREATE, table)));

            final String upsert = sql(new StandardStatementRequest(StatementType.UPSERT, table));
            try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                bindRecord(statement, table, 1, "eu", new BigDecimal("12.5"), "first", updated, true);
                bindRecord(statement, table, 2, "us", null, null, null, null);
                bindRecord(statement, table, 1, "eu", new BigDecimal("99.25"), LONG_LABEL, updated, false);
                final int[] counts = statement.executeBatch();
                assertEquals(3, counts.length);
            }

            final List<Object[]> rows = selectRows(connection, "SELECT id, region, amount, label, updated, active FROM %s ORDER BY id".formatted(tableName));
            assertEquals(2, rows.size());
            assertRow(rows.get(0), 1, "eu", new BigDecimal("99.25"), LONG_LABEL, updated, false);
            assertRow(rows.get(1), 2, "us", null, null, null, null);

            // Informix truncates values that exceed the LVARCHAR length without raising an error
            try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                bindRecord(statement, table, 2, "us", null, OVERSIZED_LABEL, null, null);
                statement.executeBatch();
            }
            final List<Object[]> truncated = selectRows(connection, "SELECT LENGTH(label) FROM %s WHERE id = 2".formatted(tableName));
            assertEquals(LVARCHAR_DEFAULT_LENGTH, truncated.get(0)[0]);

            final String insertIgnore = sql(new StandardStatementRequest(StatementType.INSERT_IGNORE, table));
            try (PreparedStatement statement = connection.prepareStatement(insertIgnore)) {
                bindRecord(statement, table, 1, "eu", new BigDecimal("1"), "ignored", null, null);
                bindRecord(statement, table, 3, "eu", new BigDecimal("3"), "third", null, true);
                final int[] counts = statement.executeBatch();
                assertEquals(0, counts[0]);
                assertEquals(1, counts[1]);
            }

            final List<Object[]> afterIgnore = selectRows(connection, "SELECT id, region, amount, label, updated, active FROM %s ORDER BY id".formatted(tableName));
            assertEquals(3, afterIgnore.size());
            assertRow(afterIgnore.get(0), 1, "eu", new BigDecimal("99.25"), LONG_LABEL, updated, false);
            assertRow(afterIgnore.get(2), 3, "eu", new BigDecimal("3"), "third", null, true);
        }
    }

    /**
     * Informix 15 accepts an explicit LVARCHAR length in the MERGE source; 14.10 rejects it with error -499
     */
    @Test
    void testUpsertLongStringsWithConfiguredLength() throws SQLException {
        runner.setProperty(service, InformixDatabaseDialectService.UPSERT_STRING_LENGTH, String.valueOf(InformixDataTypes.MAX_STRING_LENGTH));
        runner.enableControllerService(service);
        final String tableName = "orders_long";

        try (Connection connection = connect("")) {
            assumeTrue(connection.getMetaData().getDatabaseMajorVersion() >= 15, "explicit LVARCHAR length in MERGE requires Informix 15");
            execute(connection, "CREATE TABLE %s (id INTEGER NOT NULL, label LVARCHAR(30000), PRIMARY KEY (id))".formatted(tableName));
            final TableDefinition table = table(tableName, ID, LABEL);
            try (PreparedStatement statement = connection.prepareStatement(sql(new StandardStatementRequest(StatementType.UPSERT, table)))) {
                bindRecord(statement, table, 1, "z".repeat(20000));
                statement.executeBatch();
            }
            final List<Object[]> rows = selectRows(connection, "SELECT LENGTH(label) FROM %s WHERE id = 1".formatted(tableName));
            assertEquals(20000, rows.get(0)[0]);
        }
    }

    @Test
    void testQuotedIdentifiersWithDelimident() throws SQLException {
        runner.setProperty(service, InformixDatabaseDialectService.QUOTE_IDENTIFIERS, "true");
        runner.enableControllerService(service);
        final ColumnDefinition mixedId = new StandardColumnDefinition("Id", Types.INTEGER, ColumnDefinition.Nullable.NO, true);
        final ColumnDefinition mixedLabel = new StandardColumnDefinition("Label", Types.VARCHAR, ColumnDefinition.Nullable.YES, false);
        final TableDefinition table = table("MixedCase", mixedId, mixedLabel);

        try (Connection connection = connect(DELIMIDENT)) {
            execute(connection, sql(new StandardStatementRequest(StatementType.CREATE, table)));
            try (PreparedStatement statement = connection.prepareStatement(sql(new StandardStatementRequest(StatementType.UPSERT, table)))) {
                bindRecord(statement, table, 7, "seven");
                statement.executeBatch();
            }
            final List<Object[]> rows = selectRows(connection, sql(select(table, null, "\"Id\"", page(0, 5, null))));
            assertEquals(1, rows.size());
            assertEquals(7, rows.get(0)[0]);
            assertEquals("seven", rows.get(0)[1]);
        }
    }

    /**
     * Bind one record the way PutDatabaseRecord does for UPSERT: values in column order, repeated
     * for every block of parameters the statement declares
     */
    private static void bindRecord(final PreparedStatement statement, final TableDefinition table, final Object... values) throws SQLException {
        final int columnCount = table.columns().size();
        assertEquals(columnCount, values.length);
        final int parameterCount = statement.getParameterMetaData().getParameterCount();
        final int repetitions = parameterCount / columnCount;
        for (int i = 0; i < columnCount; i++) {
            final int sqlType = table.columns().get(i).dataType();
            for (int repetition = 0; repetition < repetitions; repetition++) {
                final int index = i + columnCount * repetition + 1;
                if (values[i] == null) {
                    statement.setNull(index, sqlType);
                } else {
                    statement.setObject(index, values[i], sqlType);
                }
            }
        }
        statement.addBatch();
    }

    private static void assertRow(final Object[] row, final Object... expected) {
        assertEquals(expected.length, row.length);
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] == null) {
                assertNull(row[i]);
            } else if (expected[i] instanceof final BigDecimal expectedDecimal) {
                assertEquals(0, expectedDecimal.compareTo((BigDecimal) row[i]), "column " + i);
            } else {
                assertEquals(expected[i], row[i], "column " + i);
            }
        }
    }

    private Connection connect(final String properties) throws SQLException {
        return DriverManager.getConnection(INFORMIX.getJdbcUrl(DATABASE, properties), INFORMIX.getUsername(), INFORMIX.getPassword());
    }

    private static void execute(final Connection connection, final String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<Integer> selectIds(final Connection connection, final String sql) throws SQLException {
        final List<Integer> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                ids.add(resultSet.getInt(1));
            }
        }
        return ids;
    }

    private static List<Object[]> selectRows(final Connection connection, final String sql) throws SQLException {
        final List<Object[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            final int columnCount = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                final Object[] row = new Object[columnCount];
                for (int i = 0; i < columnCount; i++) {
                    row[i] = resultSet.getObject(i + 1);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, String> getColumnTypes(final Connection connection, final String tableName) throws SQLException {
        final Map<String, String> columnTypes = new LinkedHashMap<>();
        final DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(null, null, tableName, null)) {
            while (columns.next()) {
                columnTypes.put(columns.getString("COLUMN_NAME"), columns.getString("TYPE_NAME"));
            }
        }
        assertTrue(columnTypes.size() > 0, "Table not found: " + tableName);
        return columnTypes;
    }

    private static List<String> getPrimaryKeyColumns(final Connection connection, final String tableName) throws SQLException {
        final List<String> keyColumns = new ArrayList<>();
        try (ResultSet keys = connection.getMetaData().getPrimaryKeys(null, null, tableName)) {
            while (keys.next()) {
                keyColumns.add(keys.getString("COLUMN_NAME"));
            }
        }
        return keyColumns;
    }

    private String sql(final org.apache.nifi.database.dialect.service.api.StatementRequest request) {
        return service.getStatement(request).sql();
    }

    private static TableDefinition table(final String tableName, final ColumnDefinition... columns) {
        return new TableDefinition(Optional.empty(), Optional.empty(), tableName, List.of(columns));
    }

    private static StandardQueryStatementRequest select(final TableDefinition table, final String where, final String orderBy, final PageRequest page) {
        return new StandardQueryStatementRequest(
                StatementType.SELECT, table, Optional.empty(), Optional.ofNullable(where), Optional.ofNullable(orderBy), Optional.ofNullable(page)
        );
    }

    private static PageRequest page(final long offset, final Integer limit, final String indexColumn) {
        return new StandardPageRequest(offset, limit == null ? OptionalLong.empty() : OptionalLong.of(limit), Optional.ofNullable(indexColumn));
    }
}
