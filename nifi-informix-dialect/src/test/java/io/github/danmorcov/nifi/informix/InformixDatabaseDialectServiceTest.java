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
import org.apache.nifi.database.dialect.service.api.StatementRequest;
import org.apache.nifi.database.dialect.service.api.StatementType;
import org.apache.nifi.database.dialect.service.api.TableDefinition;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.NoOpProcessor;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InformixDatabaseDialectServiceTest {

    private static final String SERVICE_ID = InformixDatabaseDialectService.class.getSimpleName();

    private static final String TABLE_NAME = "orders";

    private static final ColumnDefinition ID = new StandardColumnDefinition("id", Types.INTEGER, ColumnDefinition.Nullable.NO, true);

    private static final ColumnDefinition LABEL = new StandardColumnDefinition("label", Types.VARCHAR, ColumnDefinition.Nullable.YES, false);

    private static final TableDefinition TABLE = new TableDefinition(Optional.empty(), Optional.empty(), TABLE_NAME, List.of(ID, LABEL));

    private TestRunner runner;

    private InformixDatabaseDialectService service;

    @BeforeEach
    void setRunner() throws InitializationException {
        runner = TestRunners.newTestRunner(NoOpProcessor.class);
        service = new InformixDatabaseDialectService();
        runner.addControllerService(SERVICE_ID, service);
    }

    @Test
    void testEnableDisable() {
        runner.assertValid(service);
        runner.enableControllerService(service);
        runner.disableControllerService(service);
    }

    @Test
    void testGetSupportedStatementTypes() {
        assertEquals(Set.of(StatementType.values()), service.getSupportedStatementTypes());
    }

    @Test
    void testUpsert() {
        final StatementRequest request = new StandardStatementRequest(StatementType.UPSERT, TABLE);
        assertEquals("MERGE INTO orders t USING (SELECT CAST(? AS INTEGER) AS id, CAST(? AS LVARCHAR(32739)) AS label FROM sysmaster:sysdual) n"
                + " ON (t.id = n.id)"
                + " WHEN MATCHED THEN UPDATE SET label = n.label"
                + " WHEN NOT MATCHED THEN INSERT (id, label) VALUES (n.id, n.label)", sql(request));
    }

    @Test
    void testUpsertCompositeKeyAndTypes() {
        final ColumnDefinition region = new StandardColumnDefinition("region", Types.VARCHAR, ColumnDefinition.Nullable.NO, true);
        final ColumnDefinition amount = new StandardColumnDefinition("amount", Types.DECIMAL, ColumnDefinition.Nullable.YES, false);
        final ColumnDefinition updated = new StandardColumnDefinition("updated", Types.TIMESTAMP, ColumnDefinition.Nullable.YES, false);
        final TableDefinition table = new TableDefinition(Optional.empty(), Optional.empty(), TABLE_NAME, List.of(ID, region, amount, updated));
        assertEquals("MERGE INTO orders t USING (SELECT CAST(? AS INTEGER) AS id, CAST(? AS LVARCHAR(32739)) AS region,"
                + " CAST(? AS DECIMAL(32,10)) AS amount, CAST(? AS DATETIME YEAR TO FRACTION(5)) AS updated FROM sysmaster:sysdual) n"
                + " ON (t.id = n.id AND t.region = n.region)"
                + " WHEN MATCHED THEN UPDATE SET amount = n.amount, updated = n.updated"
                + " WHEN NOT MATCHED THEN INSERT (id, region, amount, updated) VALUES (n.id, n.region, n.amount, n.updated)",
                sql(new StandardStatementRequest(StatementType.UPSERT, table)));
    }

    @Test
    void testUpsertAllColumnsAreKeysOmitsUpdate() {
        final TableDefinition table = new TableDefinition(Optional.empty(), Optional.empty(), TABLE_NAME, List.of(ID));
        assertEquals("MERGE INTO orders t USING (SELECT CAST(? AS INTEGER) AS id FROM sysmaster:sysdual) n ON (t.id = n.id)"
                + " WHEN NOT MATCHED THEN INSERT (id) VALUES (n.id)", sql(new StandardStatementRequest(StatementType.UPSERT, table)));
    }

    @Test
    void testUpsertParameterCountMatchesColumns() {
        final String sql = sql(new StandardStatementRequest(StatementType.UPSERT, TABLE));
        assertEquals(TABLE.columns().size(), sql.chars().filter(c -> c == '?').count());
    }

    @Test
    void testUpsertRequiresKeyColumns() {
        final TableDefinition table = new TableDefinition(Optional.empty(), Optional.empty(), TABLE_NAME, List.of(LABEL));
        final StatementRequest request = new StandardStatementRequest(StatementType.UPSERT, table);
        assertThrows(IllegalArgumentException.class, () -> service.getStatement(request));
    }

    @Test
    void testUpsertRequiresColumns() {
        final TableDefinition table = new TableDefinition(Optional.empty(), Optional.empty(), TABLE_NAME, List.of());
        final StatementRequest request = new StandardStatementRequest(StatementType.UPSERT, table);
        assertThrows(IllegalArgumentException.class, () -> service.getStatement(request));
    }

    @Test
    void testInsertIgnore() {
        final StatementRequest request = new StandardStatementRequest(StatementType.INSERT_IGNORE, TABLE);
        assertEquals("MERGE INTO orders t USING (SELECT CAST(? AS INTEGER) AS id, CAST(? AS LVARCHAR(32739)) AS label FROM sysmaster:sysdual) n"
                + " ON (t.id = n.id)"
                + " WHEN NOT MATCHED THEN INSERT (id, label) VALUES (n.id, n.label)", sql(request));
    }

    @Test
    void testUpsertQuotedIdentifiersFromProcessor() {
        enableQuoting();
        final ColumnDefinition quotedId = new StandardColumnDefinition("\"Id\"", Types.INTEGER, ColumnDefinition.Nullable.NO, true);
        final ColumnDefinition quotedLabel = new StandardColumnDefinition("\"Label\"", Types.VARCHAR, ColumnDefinition.Nullable.YES, false);
        final TableDefinition table = new TableDefinition(Optional.empty(), Optional.empty(), "\"Orders\"", List.of(quotedId, quotedLabel));
        assertEquals("MERGE INTO \"Orders\" t USING (SELECT CAST(? AS INTEGER) AS \"Id\", CAST(? AS LVARCHAR(32739)) AS \"Label\" FROM sysmaster:sysdual) n"
                + " ON (t.\"Id\" = n.\"Id\")"
                + " WHEN MATCHED THEN UPDATE SET \"Label\" = n.\"Label\""
                + " WHEN NOT MATCHED THEN INSERT (\"Id\", \"Label\") VALUES (n.\"Id\", n.\"Label\")", sql(new StandardStatementRequest(StatementType.UPSERT, table)));
    }

    @Test
    void testCreate() {
        final StatementRequest request = new StandardStatementRequest(StatementType.CREATE, TABLE);
        assertEquals("CREATE TABLE orders (id INTEGER NOT NULL, label LVARCHAR, PRIMARY KEY (id))", sql(request));
    }

    @Test
    void testCreateCompositePrimaryKey() {
        final ColumnDefinition region = new StandardColumnDefinition("region", Types.VARCHAR, ColumnDefinition.Nullable.UNKNOWN, true);
        final ColumnDefinition amount = new StandardColumnDefinition("amount", Types.DECIMAL, ColumnDefinition.Nullable.NO, false);
        final TableDefinition table = new TableDefinition(Optional.empty(), Optional.empty(), TABLE_NAME, List.of(ID, region, amount));
        final StatementRequest request = new StandardStatementRequest(StatementType.CREATE, table);
        assertEquals("CREATE TABLE orders (id INTEGER NOT NULL, region VARCHAR(255) NOT NULL, amount DECIMAL(32,10) NOT NULL, PRIMARY KEY (id, region))", sql(request));
    }

    @Test
    void testCreateWithoutPrimaryKey() {
        final TableDefinition table = new TableDefinition(Optional.empty(), Optional.empty(), TABLE_NAME, List.of(LABEL));
        final StatementRequest request = new StandardStatementRequest(StatementType.CREATE, table);
        assertEquals("CREATE TABLE orders (label LVARCHAR)", sql(request));
    }

    @Test
    void testCreateTypeMapping() {
        final List<ColumnDefinition> columns = List.of(
                column("c_bool", Types.BOOLEAN), column("c_bit", Types.BIT), column("c_tiny", Types.TINYINT), column("c_small", Types.SMALLINT),
                column("c_int", Types.INTEGER), column("c_big", Types.BIGINT), column("c_real", Types.REAL), column("c_float", Types.FLOAT),
                column("c_double", Types.DOUBLE), column("c_dec", Types.DECIMAL), column("c_num", Types.NUMERIC), column("c_char", Types.CHAR),
                column("c_nvarchar", Types.NVARCHAR), column("c_long", Types.LONGVARCHAR), column("c_other", Types.OTHER), column("c_clob", Types.CLOB),
                column("c_bin", Types.BINARY), column("c_varbin", Types.VARBINARY), column("c_blob", Types.BLOB), column("c_date", Types.DATE),
                column("c_time", Types.TIME), column("c_ts", Types.TIMESTAMP), column("c_tstz", Types.TIMESTAMP_WITH_TIMEZONE), column("c_array", Types.ARRAY)
        );
        final TableDefinition table = new TableDefinition(Optional.empty(), Optional.empty(), "typetest", columns);
        assertEquals("CREATE TABLE typetest ("
                + "c_bool BOOLEAN, c_bit BOOLEAN, c_tiny SMALLINT, c_small SMALLINT, "
                + "c_int INTEGER, c_big BIGINT, c_real SMALLFLOAT, c_float FLOAT, "
                + "c_double FLOAT, c_dec DECIMAL(32,10), c_num DECIMAL(32,10), c_char LVARCHAR, "
                + "c_nvarchar LVARCHAR, c_long LVARCHAR, c_other LVARCHAR, c_clob CLOB, "
                + "c_bin BYTE, c_varbin BYTE, c_blob BLOB, c_date DATE, "
                + "c_time DATETIME HOUR TO SECOND, c_ts DATETIME YEAR TO FRACTION(5), c_tstz DATETIME YEAR TO FRACTION(5), c_array ARRAY)",
                sql(new StandardStatementRequest(StatementType.CREATE, table)));
    }

    @Test
    void testAlter() {
        final StatementRequest request = new StandardStatementRequest(StatementType.ALTER, TABLE);
        assertEquals("ALTER TABLE orders ADD (id INTEGER, label LVARCHAR)", sql(request));
    }

    @Test
    void testSelectAllColumns() {
        final StatementRequest request = select(new TableDefinition(Optional.empty(), Optional.empty(), TABLE_NAME, List.of()), null, null, null);
        assertEquals("SELECT * FROM orders", sql(request));
    }

    @Test
    void testSelectQualifiedTableName() {
        final TableDefinition table = new TableDefinition(Optional.of("stores"), Optional.of("informix"), TABLE_NAME, List.of(ID));
        assertEquals("SELECT id FROM stores.informix.orders", sql(select(table, null, null, null)));
    }

    @Test
    void testSelectWithWhereAndOrderBy() {
        final StatementRequest request = select(TABLE, "label IS NOT NULL", "id", null);
        assertEquals("SELECT id, label FROM orders WHERE label IS NOT NULL ORDER BY id", sql(request));
    }

    @Test
    void testSelectSkipFirst() {
        final StatementRequest request = select(TABLE, "1=1", "id", page(25, 100, null));
        assertEquals("SELECT SKIP 25 FIRST 100 id, label FROM orders WHERE 1=1 ORDER BY id", sql(request));
    }

    @Test
    void testSelectFirstPageOmitsSkip() {
        final StatementRequest request = select(TABLE, "1=1", "id", page(0, 100, null));
        assertEquals("SELECT FIRST 100 id, label FROM orders WHERE 1=1 ORDER BY id", sql(request));
    }

    @Test
    void testSelectSkipWithoutLimit() {
        final StatementRequest request = select(TABLE, null, null, page(25, null, null));
        assertEquals("SELECT SKIP 25 id, label FROM orders", sql(request));
    }

    @Test
    void testSelectIndexColumnRange() {
        final StatementRequest request = select(TABLE, "1=1", "id", page(25, 100, "id"));
        assertEquals("SELECT id, label FROM orders WHERE 1=1 AND id >= 25 AND id < 125 ORDER BY id", sql(request));
    }

    @Test
    void testSelectIndexColumnRangeWithoutWhere() {
        final StatementRequest request = select(TABLE, null, null, page(25, 100, "id"));
        assertEquals("SELECT id, label FROM orders WHERE id >= 25 AND id < 125", sql(request));
    }

    @Test
    void testSelectIndexColumnLowerBoundOnly() {
        final StatementRequest request = select(TABLE, "label IS NOT NULL", null, page(25, null, "id"));
        assertEquals("SELECT id, label FROM orders WHERE label IS NOT NULL AND id >= 25", sql(request));
    }

    @Test
    void testSelectDerivedTable() {
        final TableDefinition table = new TableDefinition(Optional.empty(), Optional.empty(), TABLE_NAME, List.of());
        final StatementRequest request = new StandardQueryStatementRequest(
                StatementType.SELECT, table, Optional.of("SELECT id FROM orders"), Optional.of("1 = 0"), Optional.empty(), Optional.empty()
        );
        assertEquals("SELECT * FROM (SELECT id FROM orders) AS orders WHERE 1 = 0", sql(request));
    }

    @Test
    void testSelectDerivedTableWithPaging() {
        final TableDefinition table = new TableDefinition(Optional.empty(), Optional.empty(), TABLE_NAME, List.of());
        final StatementRequest request = new StandardQueryStatementRequest(
                StatementType.SELECT, table, Optional.of("SELECT id FROM orders"), Optional.empty(), Optional.of("id"), Optional.of(page(10, 5, null))
        );
        assertEquals("SELECT SKIP 10 FIRST 5 * FROM (SELECT id FROM orders) AS orders ORDER BY id", sql(request));
    }

    @Test
    void testSelectRequiresQueryStatementRequest() {
        final StatementRequest request = new StandardStatementRequest(StatementType.SELECT, TABLE);
        assertThrows(IllegalArgumentException.class, () -> service.getStatement(request));
    }

    @Test
    void testQuoteIdentifiersDefaultOff() {
        runner.enableControllerService(service);
        assertEquals("SELECT id, label FROM orders", sql(select(TABLE, null, null, null)));
    }

    @Test
    void testQuoteIdentifiersSelect() {
        enableQuoting();
        final StatementRequest request = select(TABLE, "label IS NOT NULL", "id", page(25, 100, "id"));
        assertEquals("SELECT \"id\", \"label\" FROM \"orders\" WHERE label IS NOT NULL AND \"id\" >= 25 AND \"id\" < 125 ORDER BY id", sql(request));
    }

    @Test
    void testQuoteIdentifiersQualifiedTable() {
        enableQuoting();
        final TableDefinition table = new TableDefinition(Optional.of("stores"), Optional.of("informix"), TABLE_NAME, List.of(ID));
        assertEquals("SELECT \"id\" FROM \"stores\".\"informix\".\"orders\"", sql(select(table, null, null, null)));
    }

    @Test
    void testQuoteIdentifiersDottedTableName() {
        enableQuoting();
        final TableDefinition table = new TableDefinition(Optional.empty(), Optional.empty(), "informix.orders", List.of(ID));
        assertEquals("SELECT \"id\" FROM \"informix\".\"orders\"", sql(select(table, null, null, null)));
    }

    @Test
    void testQuoteIdentifiersAlreadyQuoted() {
        enableQuoting();
        final ColumnDefinition quotedId = new StandardColumnDefinition("\"Id\"", Types.INTEGER, ColumnDefinition.Nullable.NO, true);
        final TableDefinition table = new TableDefinition(Optional.empty(), Optional.empty(), "\"Orders\"", List.of(quotedId));
        assertEquals("SELECT \"Id\" FROM \"Orders\"", sql(select(table, null, null, null)));
    }

    @Test
    void testQuoteIdentifiersCreate() {
        enableQuoting();
        assertEquals("CREATE TABLE \"orders\" (\"id\" INTEGER NOT NULL, \"label\" LVARCHAR, PRIMARY KEY (\"id\"))", sql(new StandardStatementRequest(StatementType.CREATE, TABLE)));
    }

    @Test
    void testQuoteIdentifiersAlter() {
        enableQuoting();
        assertEquals("ALTER TABLE \"orders\" ADD (\"id\" INTEGER, \"label\" LVARCHAR)", sql(new StandardStatementRequest(StatementType.ALTER, TABLE)));
    }

    private void enableQuoting() {
        runner.setProperty(service, InformixDatabaseDialectService.QUOTE_IDENTIFIERS, "true");
        runner.enableControllerService(service);
    }

    private String sql(final StatementRequest request) {
        return service.getStatement(request).sql();
    }

    private static StatementRequest select(final TableDefinition table, final String where, final String orderBy, final PageRequest page) {
        return new StandardQueryStatementRequest(
                StatementType.SELECT, table, Optional.empty(), Optional.ofNullable(where), Optional.ofNullable(orderBy), Optional.ofNullable(page)
        );
    }

    private static ColumnDefinition column(final String name, final int jdbcType) {
        return new StandardColumnDefinition(name, jdbcType, ColumnDefinition.Nullable.YES, false);
    }

    private static PageRequest page(final long offset, final Integer limit, final String indexColumn) {
        return new StandardPageRequest(offset, limit == null ? OptionalLong.empty() : OptionalLong.of(limit), Optional.ofNullable(indexColumn));
    }
}
