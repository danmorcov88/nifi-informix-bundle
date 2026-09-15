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

import java.sql.JDBCType;
import java.sql.Types;

/**
 * Mapping from java.sql.Types codes to Informix column type names for CREATE TABLE and ALTER TABLE.
 * NiFi only provides the JDBC type code, not length, precision or scale, so each mapping picks a
 * general-purpose Informix type.
 */
final class InformixDataTypes {
    /** Largest fixed precision Informix allows; ten fractional digits leaves room for currency and measurements */
    static final String DECIMAL_TYPE = "DECIMAL(32,10)";

    /** Variable-length string up to 2048 bytes without an explicit length; counts toward the 32767 byte row limit */
    static final String STRING_TYPE = "LVARCHAR";

    /** Bounded string for key columns: LVARCHAR exceeds the maximum index key size (error -550) */
    static final String KEY_STRING_TYPE = "VARCHAR(255)";

    /** Length of LVARCHAR when declared without one; the only string cast length Informix 14.10 accepts in a MERGE source */
    static final int DEFAULT_STRING_LENGTH = 2048;

    /** Largest LVARCHAR length Informix allows */
    static final int MAX_STRING_LENGTH = 32739;

    /** Simple large object for binary data; unlike BLOB it needs no sbspace */
    static final String BINARY_TYPE = "BYTE";

    static final String TIME_TYPE = "DATETIME HOUR TO SECOND";

    static final String TIMESTAMP_TYPE = "DATETIME YEAR TO FRACTION(5)";

    private InformixDataTypes() {
    }

    /**
     * Type name for a column that is part of the primary key
     */
    static String getKeyTypeName(final int jdbcType) {
        final String typeName = getTypeName(jdbcType);
        return STRING_TYPE.equals(typeName) ? KEY_STRING_TYPE : typeName;
    }

    /**
     * Type name for casting a statement parameter, e.g. CAST(? AS type), so that Informix can resolve
     * the parameter type inside a MERGE source query. String parameters are cast to LVARCHAR of the
     * given length; Informix silently truncates longer values. The length is left implicit when it
     * equals the LVARCHAR default because Informix 14.10 rejects an explicit length in that position.
     */
    static String getParameterTypeName(final int jdbcType, final int stringLength) {
        final String typeName = getTypeName(jdbcType);
        if (!STRING_TYPE.equals(typeName)) {
            return typeName;
        }
        return stringLength == DEFAULT_STRING_LENGTH ? STRING_TYPE : "%s(%d)".formatted(STRING_TYPE, stringLength);
    }

    static String getTypeName(final int jdbcType) {
        return switch (jdbcType) {
            case Types.BIT, Types.BOOLEAN -> "BOOLEAN";
            case Types.TINYINT, Types.SMALLINT -> "SMALLINT";
            case Types.INTEGER -> "INTEGER";
            case Types.BIGINT -> "BIGINT";
            case Types.REAL -> "SMALLFLOAT";
            case Types.FLOAT, Types.DOUBLE -> "FLOAT";
            case Types.DECIMAL, Types.NUMERIC -> DECIMAL_TYPE;
            case Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR,
                 Types.OTHER, Types.SQLXML -> STRING_TYPE;
            case Types.CLOB, Types.NCLOB -> "CLOB";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> BINARY_TYPE;
            case Types.BLOB -> "BLOB";
            case Types.DATE -> "DATE";
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> TIME_TYPE;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> TIMESTAMP_TYPE;
            default -> JDBCType.valueOf(jdbcType).getName();
        };
    }
}
