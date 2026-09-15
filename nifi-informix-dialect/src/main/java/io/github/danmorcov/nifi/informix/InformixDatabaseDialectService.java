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

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.database.dialect.service.api.ColumnDefinition;
import org.apache.nifi.database.dialect.service.api.DatabaseDialectService;
import org.apache.nifi.database.dialect.service.api.PageRequest;
import org.apache.nifi.database.dialect.service.api.QueryStatementRequest;
import org.apache.nifi.database.dialect.service.api.StandardStatementResponse;
import org.apache.nifi.database.dialect.service.api.StatementRequest;
import org.apache.nifi.database.dialect.service.api.StatementResponse;
import org.apache.nifi.database.dialect.service.api.StatementType;
import org.apache.nifi.database.dialect.service.api.TableDefinition;

import java.sql.JDBCType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Database Dialect Service generating SQL statements for IBM Informix.
 * <p>
 * The current implementation renders ANSI SQL identical to the standard NiFi dialect;
 * Informix-specific syntax (SKIP/FIRST paging, MERGE upsert, type mapping) is added incrementally.
 */
@CapabilityDescription("""
        Database Dialect Service supporting IBM Informix.
        Supported Statement Types: ALTER, CREATE, SELECT
        """
)
@Tags({"Informix", "IBM", "Relational", "Database", "JDBC", "SQL"})
public class InformixDatabaseDialectService extends AbstractControllerService implements DatabaseDialectService {
    private static final String COLUMN_SEPARATOR = ", ";

    private static final Set<StatementType> SUPPORTED_STATEMENT_TYPES = Set.of(
            StatementType.ALTER,
            StatementType.CREATE,
            StatementType.SELECT
    );

    @Override
    public StatementResponse getStatement(final StatementRequest statementRequest) {
        Objects.requireNonNull(statementRequest, "Statement Request required");

        final StatementType statementType = statementRequest.statementType();
        final String sql = switch (statementType) {
            case ALTER -> getAlterStatement(statementRequest.tableDefinition());
            case CREATE -> getCreateStatement(statementRequest.tableDefinition());
            case SELECT -> getSelectStatement(statementRequest);
            default -> throw new UnsupportedOperationException("Statement Type [%s] not supported".formatted(statementType));
        };
        return new StandardStatementResponse(sql);
    }

    @Override
    public Set<StatementType> getSupportedStatementTypes() {
        return SUPPORTED_STATEMENT_TYPES;
    }

    private String getAlterStatement(final TableDefinition tableDefinition) {
        final StringJoiner columns = new StringJoiner(COLUMN_SEPARATOR);
        for (final ColumnDefinition column : tableDefinition.columns()) {
            columns.add(getColumnDeclaration(column, false));
        }
        return "ALTER TABLE %s ADD COLUMNS (%s)".formatted(getQualifiedTableName(tableDefinition), columns);
    }

    private String getCreateStatement(final TableDefinition tableDefinition) {
        final StringJoiner columns = new StringJoiner(COLUMN_SEPARATOR);
        for (final ColumnDefinition column : tableDefinition.columns()) {
            columns.add(getColumnDeclaration(column, true));
        }
        return "CREATE TABLE %s (%s)".formatted(getQualifiedTableName(tableDefinition), columns);
    }

    private String getColumnDeclaration(final ColumnDefinition column, final boolean includePrimaryKey) {
        final StringBuilder declaration = new StringBuilder();
        declaration.append(column.columnName());
        declaration.append(' ');
        declaration.append(JDBCType.valueOf(column.dataType()).getName());
        if (ColumnDefinition.Nullable.NO == column.nullable()) {
            declaration.append(" NOT NULL");
        }
        if (includePrimaryKey && column.primaryKey()) {
            declaration.append(" PRIMARY KEY");
        }
        return declaration.toString();
    }

    private String getSelectStatement(final StatementRequest statementRequest) {
        if (!(statementRequest instanceof final QueryStatementRequest queryStatementRequest)) {
            throw new IllegalArgumentException("Query Statement Request not found [%s]".formatted(statementRequest.getClass()));
        }

        final TableDefinition tableDefinition = queryStatementRequest.tableDefinition();
        final String qualifiedTableName = getQualifiedTableName(tableDefinition);
        final Optional<PageRequest> pageRequest = queryStatementRequest.pageRequest();

        final StringBuilder sql = new StringBuilder();
        final Optional<String> derivedTable = queryStatementRequest.derivedTable();
        if (derivedTable.isPresent()) {
            sql.append("SELECT * FROM (%s) AS %s".formatted(derivedTable.get(), qualifiedTableName));
        } else {
            sql.append("SELECT %s FROM %s".formatted(getSelectColumns(tableDefinition.columns()), qualifiedTableName));
        }

        final Optional<String> whereClause = queryStatementRequest.whereClause();
        if (whereClause.isPresent()) {
            sql.append(" WHERE ").append(whereClause.get());
            pageRequest.ifPresent(page -> appendIndexedPageRequest(page, sql));
        }

        final Optional<String> orderByClause = queryStatementRequest.orderByClause();
        if (orderByClause.isPresent()) {
            sql.append(" ORDER BY ").append(orderByClause.get());
        }

        pageRequest.ifPresent(page -> appendPageRequest(page, sql));

        return sql.toString();
    }

    private String getSelectColumns(final List<ColumnDefinition> columns) {
        if (columns.isEmpty()) {
            return "*";
        }
        final StringJoiner columnNames = new StringJoiner(COLUMN_SEPARATOR);
        for (final ColumnDefinition column : columns) {
            columnNames.add(column.columnName());
        }
        return columnNames.toString();
    }

    /**
     * Paging without an index column: LIMIT/OFFSET appended after ORDER BY
     */
    private void appendPageRequest(final PageRequest pageRequest, final StringBuilder sql) {
        if (pageRequest.indexColumnName().isPresent()) {
            return;
        }
        final OptionalLong limit = pageRequest.limit();
        if (limit.isPresent()) {
            sql.append(" LIMIT ").append(limit.getAsLong());
        }
        sql.append(" OFFSET ").append(pageRequest.offset());
    }

    /**
     * Paging with an index column: offset and limit are range bounds on the column, appended to WHERE
     */
    private void appendIndexedPageRequest(final PageRequest pageRequest, final StringBuilder sql) {
        final Optional<String> indexColumnName = pageRequest.indexColumnName();
        if (indexColumnName.isEmpty()) {
            return;
        }
        final String column = indexColumnName.get();
        sql.append(" AND ").append(column).append(" >= ").append(pageRequest.offset());
        final OptionalLong limit = pageRequest.limit();
        if (limit.isPresent()) {
            sql.append(" AND ").append(column).append(" < ").append(limit.getAsLong());
        }
    }

    private String getQualifiedTableName(final TableDefinition tableDefinition) {
        final StringJoiner qualifiedName = new StringJoiner(".");
        tableDefinition.catalog().ifPresent(qualifiedName::add);
        tableDefinition.schemaName().ifPresent(qualifiedName::add);
        qualifiedName.add(tableDefinition.tableName());
        return qualifiedName.toString();
    }
}
