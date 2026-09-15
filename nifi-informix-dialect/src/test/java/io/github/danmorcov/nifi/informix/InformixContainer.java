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

import org.testcontainers.containers.JdbcDatabaseContainer;

import java.time.Duration;

/**
 * Testcontainers wrapper for the IBM Informix developer edition image. Starting the container
 * accepts the IBM license for the developer edition; the image is pulled from icr.io and takes
 * about a minute to initialise its root dbspace.
 */
class InformixContainer extends JdbcDatabaseContainer<InformixContainer> {
    static final String DEFAULT_IMAGE = "icr.io/informix/informix-developer-database:15.0.1.0.3";

    static final String IMAGE_PROPERTY = "informix.image";

    private static final int PORT = 9088;

    private static final String SERVER_NAME = "informix";

    private static final String USERNAME = "informix";

    private static final String PASSWORD = "in4mix";

    /** Database that always exists; the tests create their own */
    private static final String SYSTEM_DATABASE = "sysmaster";

    InformixContainer() {
        super(System.getProperty(IMAGE_PROPERTY, DEFAULT_IMAGE));
        withEnv("LICENSE", "accept");
        withExposedPorts(PORT);
        withStartupTimeout(Duration.ofMinutes(5));
        withConnectTimeoutSeconds(300);
    }

    @Override
    public String getDriverClassName() {
        return "com.informix.jdbc.IfxDriver";
    }

    @Override
    public String getJdbcUrl() {
        return getJdbcUrl(SYSTEM_DATABASE, "");
    }

    /**
     * JDBC URL for a specific database, with optional extra connection properties such as ";DELIMIDENT=Y"
     */
    String getJdbcUrl(final String database, final String properties) {
        return "jdbc:informix-sqli://%s:%d/%s:INFORMIXSERVER=%s%s".formatted(getHost(), getMappedPort(PORT), database, SERVER_NAME, properties);
    }

    @Override
    public String getUsername() {
        return USERNAME;
    }

    @Override
    public String getPassword() {
        return PASSWORD;
    }

    @Override
    protected String getTestQueryString() {
        return "SELECT 1 FROM sysmaster:sysdual";
    }
}
