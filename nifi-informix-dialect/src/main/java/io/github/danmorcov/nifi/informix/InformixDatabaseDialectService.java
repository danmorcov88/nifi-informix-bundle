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
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.database.dialect.service.api.ColumnDefinition;
import org.apache.nifi.database.dialect.service.api.DatabaseDialectService;
import org.apache.nifi.database.dialect.service.api.PageRequest;
import org.apache.nifi.database.dialect.service.api.QueryStatementRequest;
import org.apache.nifi.database.dialect.service.api.StandardStatementResponse;
import org.apache.nifi.database.dialect.service.api.StatementRequest;
import org.apache.nifi.database.dialect.service.api.StatementResponse;
import org.apache.nifi.database.dialect.service.api.StatementType;
import org.apache.nifi.database.dialect.service.api.TableDefinition;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Database Dialect Service generating SQL statements for IBM Informix.
 * <p>
 * SELECT statements use Informix SKIP/FIRST paging, CREATE and ALTER use Informix column types,
 * UPSERT and INSERT_IGNORE are rendered as MERGE statements.
 */
@CapabilityDescription("""
        Database Dialect Service supporting IBM Informix.
        Supported Statement Types: ALTER, CREATE, SELECT, UPSERT, INSERT_IGNORE.
        UPSERT and INSERT_IGNORE are rendered as MERGE statements (Informix 11.70 or later).
        Identifiers are not quoted by default because Informix requires DELIMIDENT for delimited identifiers.
        """
)
@Tags({"Informix", "IBM", "Relational", "Database", "JDBC", "SQL"})
public class InformixDatabaseDialectService extends AbstractControllerService implements DatabaseDialectService {
    static final PropertyDescriptor QUOTE_IDENTIFIERS = new PropertyDescriptor.Builder()
            .name("Quote Identifiers")
            .description("""
                    Wrap table and column names in double quotes so that mixed-case or reserved names can be used.
                    Requires DELIMIDENT=Y on the Informix connection (JDBC URL property or environment variable):
                    without it Informix treats double-quoted text as a string literal, not as an identifier.
                    Names that are already quoted are left untouched. Names inside WHERE and ORDER BY clauses
                    are passed through as written.
                    """)
            .required(true)
            .allowableValues("true", "false")
            .defaultValue("false")
            .build();

    static final PropertyDescriptor UPSERT_STRING_LENGTH = new PropertyDescriptor.Builder()
            .name("Upsert String Length")
            .description("""
                    Length of the LVARCHAR cast applied to string values in UPSERT and INSERT_IGNORE (MERGE) statements.
                    Informix silently truncates values longer than this. The default of 2048 works on every supported
                    Informix version; Informix 14.10 rejects any other length inside a MERGE statement (error -499),
                    while Informix 15 accepts values up to 32739.
                    """)
            .required(true)
            .defaultValue(String.valueOf(InformixDataTypes.DEFAULT_STRING_LENGTH))
            .addValidator(InformixDatabaseDialectService::validateStringLength)
            .build();

    private static final List<PropertyDescriptor> PROPERTY_DESCRIPTORS = List.of(QUOTE_IDENTIFIERS, UPSERT_STRING_LENGTH);

    private static final String COLUMN_SEPARATOR = ", ";

    private static final char QUOTE = '"';

    private static final String QUALIFIER_SEPARATOR = ".";

    private static final String TARGET_ALIAS = "t";

    private static final String SOURCE_ALIAS = "n";

    /** Single-row system table available since Informix 11.70, used as the MERGE source */
    private static final String DUAL_TABLE = "sysmaster:sysdual";

    private static final Set<StatementType> SUPPORTED_STATEMENT_TYPES = Set.of(
            StatementType.ALTER,
            StatementType.CREATE,
            StatementType.SELECT,
            StatementType.UPSERT,
            StatementType.INSERT_IGNORE
    );

    private volatile boolean quoteIdentifiers;

    private volatile int upsertStringLength = InformixDataTypes.DEFAULT_STRING_LENGTH;

    private static ValidationResult validateStringLength(final String subject, final String input, final ValidationContext context) {
        boolean valid;
        try {
            final int length = Integer.parseInt(input);
            valid = length >= 1 && length <= InformixDataTypes.MAX_STRING_LENGTH;
        } catch (final NumberFormatException e) {
            valid = false;
        }
        return new ValidationResult.Builder()
                .subject(subject)
                .input(input)
                .valid(valid)
                .explanation("must be an integer between 1 and %d".formatted(InformixDataTypes.MAX_STRING_LENGTH))
                .build();
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTY_DESCRIPTORS;
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context) {
        quoteIdentifiers = context.getProperty(QUOTE_IDENTIFIERS).asBoolean();
        upsertStringLength = context.getProperty(UPSERT_STRING_LENGTH).asInteger();
    }

    @Override
    public StatementResponse getStatement(final StatementRequest statementRequest) {
        Objects.requireNonNull(statementRequest, "Statement Request required");

        final StatementType statementType = statementRequest.statementType();
        final String sql = switch (statementType) {
            case ALTER -> getAlterStatement(statementRequest.tableDefinition());
            case CREATE -> getCreateStatement(statementRequest.tableDefinition());
            case SELECT -> getSelectStatement(statementRequest);
            case UPSERT -> getMergeStatement(statementRequest.tableDefinition(), true);
            case INSERT_IGNORE -> getMergeStatement(statementRequest.tableDefinition(), false);
        };
        return new StandardStatementResponse(sql);
    }

    @Override
    public Set<StatementType> getSupportedStatementTypes() {
        return SUPPORTED_STATEMENT_TYPES;
    }

    /**
     * Informix syntax is ALTER TABLE ... ADD (column type, ...). NOT NULL is deliberately omitted:
     * Informix rejects adding a NOT NULL column without a default to a table that already has rows.
     */
    private String getAlterStatement(final TableDefinition tableDefinition) {
        final StringJoiner columns = new StringJoiner(COLUMN_SEPARATOR);
        for (final ColumnDefinition column : tableDefinition.columns()) {
            columns.add(quote(column.columnName()) + ' ' + InformixDataTypes.getTypeName(column.dataType()));
        }
        return "ALTER TABLE %s ADD (%s)".formatted(getQualifiedTableName(tableDefinition), columns);
    }

    /**
     * Primary key columns are declared NOT NULL and collected into a table-level constraint,
     * which is the only form that works for composite keys. String key columns get a bounded type
     * because Informix limits the total key size of an index.
     */
    private String getCreateStatement(final TableDefinition tableDefinition) {
        final StringJoiner columns = new StringJoiner(COLUMN_SEPARATOR);
        final StringJoiner primaryKeyColumns = new StringJoiner(COLUMN_SEPARATOR);
        for (final ColumnDefinition column : tableDefinition.columns()) {
            final String columnName = quote(column.columnName());
            final StringBuilder declaration = new StringBuilder(columnName);
            declaration.append(' ');
            declaration.append(column.primaryKey() ? InformixDataTypes.getKeyTypeName(column.dataType()) : InformixDataTypes.getTypeName(column.dataType()));
            if (column.primaryKey() || ColumnDefinition.Nullable.NO == column.nullable()) {
                declaration.append(" NOT NULL");
            }
            columns.add(declaration.toString());
            if (column.primaryKey()) {
                primaryKeyColumns.add(columnName);
            }
        }
        if (primaryKeyColumns.length() > 0) {
            columns.add("PRIMARY KEY (%s)".formatted(primaryKeyColumns));
        }
        return "CREATE TABLE %s (%s)".formatted(getQualifiedTableName(tableDefinition), columns);
    }

    /**
     * Upsert as MERGE with a single-row source built from statement parameters:
     * <pre>
     * MERGE INTO orders t USING (SELECT CAST(? AS INTEGER) AS id, CAST(? AS LVARCHAR) AS label FROM sysmaster:sysdual) n
     * ON (t.id = n.id) WHEN MATCHED THEN UPDATE SET label = n.label WHEN NOT MATCHED THEN INSERT (id, label) VALUES (n.id, n.label)
     * </pre>
     * Every column appears exactly once as a parameter, in table definition order, which is what
     * PutDatabaseRecord expects when binding record values. Parameters are cast so that Informix can
     * resolve their types inside the source query. Without updateOnMatch the statement only inserts,
     * which ignores records whose key already exists.
     */
    private String getMergeStatement(final TableDefinition tableDefinition, final boolean updateOnMatch) {
        final List<ColumnDefinition> columns = tableDefinition.columns();
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Column names required for MERGE statement");
        }
        final List<ColumnDefinition> keyColumns = columns.stream().filter(ColumnDefinition::primaryKey).toList();
        if (keyColumns.isEmpty()) {
            throw new IllegalArgumentException("Key column names required for MERGE statement");
        }

        final StringJoiner sourceColumns = new StringJoiner(COLUMN_SEPARATOR);
        final StringJoiner insertColumns = new StringJoiner(COLUMN_SEPARATOR);
        final StringJoiner insertValues = new StringJoiner(COLUMN_SEPARATOR);
        final StringJoiner updateAssignments = new StringJoiner(COLUMN_SEPARATOR);
        for (final ColumnDefinition column : columns) {
            final String columnName = quote(column.columnName());
            sourceColumns.add("CAST(? AS %s) AS %s".formatted(InformixDataTypes.getParameterTypeName(column.dataType(), upsertStringLength), columnName));
            insertColumns.add(columnName);
            insertValues.add(SOURCE_ALIAS + QUALIFIER_SEPARATOR + columnName);
            if (!column.primaryKey()) {
                updateAssignments.add("%s = %s.%s".formatted(columnName, SOURCE_ALIAS, columnName));
            }
        }

        final StringJoiner matchConditions = new StringJoiner(" AND ");
        for (final ColumnDefinition keyColumn : keyColumns) {
            final String columnName = quote(keyColumn.columnName());
            matchConditions.add("%s.%s = %s.%s".formatted(TARGET_ALIAS, columnName, SOURCE_ALIAS, columnName));
        }

        final StringBuilder sql = new StringBuilder();
        sql.append("MERGE INTO %s %s USING (SELECT %s FROM %s) %s ON (%s)".formatted(
                getQualifiedTableName(tableDefinition), TARGET_ALIAS, sourceColumns, DUAL_TABLE, SOURCE_ALIAS, matchConditions));
        if (updateOnMatch && updateAssignments.length() > 0) {
            sql.append(" WHEN MATCHED THEN UPDATE SET ").append(updateAssignments);
        }
        sql.append(" WHEN NOT MATCHED THEN INSERT (%s) VALUES (%s)".formatted(insertColumns, insertValues));
        return sql.toString();
    }

    /**
     * Informix places paging in the projection clause: SELECT [SKIP offset] [FIRST limit] columns FROM ...
     * When an index column is supplied, paging is expressed as a range on that column in WHERE instead,
     * matching the behaviour of the built-in database adapters.
     */
    private String getSelectStatement(final StatementRequest statementRequest) {
        if (!(statementRequest instanceof final QueryStatementRequest queryStatementRequest)) {
            throw new IllegalArgumentException("Query Statement Request not found [%s]".formatted(statementRequest.getClass()));
        }

        final TableDefinition tableDefinition = queryStatementRequest.tableDefinition();
        final String qualifiedTableName = getQualifiedTableName(tableDefinition);
        final Optional<PageRequest> pageRequest = queryStatementRequest.pageRequest();
        final boolean indexedPaging = pageRequest.flatMap(PageRequest::indexColumnName).isPresent();

        final StringBuilder sql = new StringBuilder("SELECT");
        if (!indexedPaging) {
            pageRequest.ifPresent(page -> appendSkipFirst(page, sql));
        }

        final Optional<String> derivedTable = queryStatementRequest.derivedTable();
        if (derivedTable.isPresent()) {
            sql.append(" * FROM (%s) AS %s".formatted(derivedTable.get(), qualifiedTableName));
        } else {
            sql.append(" %s FROM %s".formatted(getSelectColumns(tableDefinition.columns()), qualifiedTableName));
        }

        final StringJoiner conditions = new StringJoiner(" AND ");
        queryStatementRequest.whereClause().ifPresent(conditions::add);
        if (indexedPaging) {
            appendIndexRange(pageRequest.get(), conditions);
        }
        if (conditions.length() > 0) {
            sql.append(" WHERE ").append(conditions);
        }

        final Optional<String> orderByClause = queryStatementRequest.orderByClause();
        if (orderByClause.isPresent()) {
            sql.append(" ORDER BY ").append(orderByClause.get());
        }

        return sql.toString();
    }

    private String getSelectColumns(final List<ColumnDefinition> columns) {
        if (columns.isEmpty()) {
            return "*";
        }
        final StringJoiner columnNames = new StringJoiner(COLUMN_SEPARATOR);
        for (final ColumnDefinition column : columns) {
            columnNames.add(quote(column.columnName()));
        }
        return columnNames.toString();
    }

    /**
     * SKIP must precede FIRST and both must immediately follow SELECT. SKIP 0 is omitted.
     */
    private void appendSkipFirst(final PageRequest pageRequest, final StringBuilder sql) {
        final long offset = pageRequest.offset();
        if (offset > 0) {
            sql.append(" SKIP ").append(offset);
        }
        final OptionalLong limit = pageRequest.limit();
        if (limit.isPresent()) {
            sql.append(" FIRST ").append(limit.getAsLong());
        }
    }

    /**
     * Range paging on an index column: offset is the lower bound (inclusive) and offset + limit the upper bound (exclusive)
     */
    private void appendIndexRange(final PageRequest pageRequest, final StringJoiner conditions) {
        final String column = quote(pageRequest.indexColumnName().orElseThrow());
        final long offset = pageRequest.offset();
        conditions.add("%s >= %d".formatted(column, offset));
        final OptionalLong limit = pageRequest.limit();
        if (limit.isPresent()) {
            conditions.add("%s < %d".formatted(column, offset + limit.getAsLong()));
        }
    }

    private String getQualifiedTableName(final TableDefinition tableDefinition) {
        final StringJoiner qualifiedName = new StringJoiner(QUALIFIER_SEPARATOR);
        tableDefinition.catalog().ifPresent(catalog -> qualifiedName.add(quote(catalog)));
        tableDefinition.schemaName().ifPresent(schemaName -> qualifiedName.add(quote(schemaName)));
        qualifiedName.add(quoteQualified(tableDefinition.tableName()));
        return qualifiedName.toString();
    }

    /**
     * Quote each segment of a possibly dot-qualified name: processors may pass "schema.table" as the table name
     */
    private String quoteQualified(final String name) {
        if (!quoteIdentifiers) {
            return name;
        }
        final StringJoiner quoted = new StringJoiner(QUALIFIER_SEPARATOR);
        Arrays.stream(name.split("\\.")).map(this::quote).forEach(quoted::add);
        return quoted.toString();
    }

    private String quote(final String identifier) {
        if (!quoteIdentifiers || isQuoted(identifier)) {
            return identifier;
        }
        return QUOTE + identifier + QUOTE;
    }

    private static boolean isQuoted(final String identifier) {
        return identifier.length() >= 2 && identifier.charAt(0) == QUOTE && identifier.charAt(identifier.length() - 1) == QUOTE;
    }
}
