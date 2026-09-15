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
    void testSelectWithWhereOrderByAndPaging() {
        final StatementRequest request = new StandardQueryStatementRequest(
                StatementType.SELECT,
                TABLE,
                Optional.empty(),
                Optional.of("label IS NOT NULL"),
                Optional.of("id"),
                Optional.of(new StandardPageRequest(25, OptionalLong.of(100), Optional.empty()))
        );
        assertEquals("SELECT id, label FROM orders WHERE label IS NOT NULL ORDER BY id LIMIT 100 OFFSET 25", service.getStatement(request).sql());
    }

    @Test
    void testSelectWithIndexColumnPaging() {
        final StatementRequest request = new StandardQueryStatementRequest(
                StatementType.SELECT,
                TABLE,
                Optional.empty(),
                Optional.of("1 = 1"),
                Optional.empty(),
                Optional.of(new StandardPageRequest(25, OptionalLong.of(100), Optional.of("id")))
        );
        assertEquals("SELECT id, label FROM orders WHERE 1 = 1 AND id >= 25 AND id < 100", service.getStatement(request).sql());
    }

    @Test
    void testSelectDerivedTable() {
        final StatementRequest request = new StandardQueryStatementRequest(
                StatementType.SELECT,
                new TableDefinition(Optional.empty(), Optional.empty(), TABLE_NAME, List.of()),
                Optional.of("SELECT id FROM orders"),
                Optional.of("1 = 0"),
                Optional.empty(),
                Optional.empty()
        );
        assertEquals("SELECT * FROM (SELECT id FROM orders) AS orders WHERE 1 = 0", service.getStatement(request).sql());
    }
}
