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
        assertEquals(Set.of(StatementType.ALTER, StatementType.CREATE, StatementType.SELECT), service.getSupportedStatementTypes());
    }

    @Test
    void testUpsertUnsupported() {
        final StatementRequest request = new StandardStatementRequest(StatementType.UPSERT, TABLE);
        assertThrows(UnsupportedOperationException.class, () -> service.getStatement(request));
    }

    @Test
    void testCreate() {
        final StatementRequest request = new StandardStatementRequest(StatementType.CREATE, TABLE);
        assertEquals("CREATE TABLE orders (id INTEGER NOT NULL PRIMARY KEY, label VARCHAR)", service.getStatement(request).sql());
    }

    @Test
    void testAlter() {
        final StatementRequest request = new StandardStatementRequest(StatementType.ALTER, TABLE);
        assertEquals("ALTER TABLE orders ADD COLUMNS (id INTEGER NOT NULL, label VARCHAR)", service.getStatement(request).sql());
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
        assertEquals("CREATE TABLE \"orders\" (\"id\" INTEGER NOT NULL PRIMARY KEY, \"label\" VARCHAR)", sql(new StandardStatementRequest(StatementType.CREATE, TABLE)));
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

    private static PageRequest page(final long offset, final Integer limit, final String indexColumn) {
        return new StandardPageRequest(offset, limit == null ? OptionalLong.empty() : OptionalLong.of(limit), Optional.ofNullable(indexColumn));
    }
}
