-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- Demo database. Two NiFi paths read new rows from orders incrementally (by id):
--   QueryDatabaseTableRecord -> PutDatabaseRecord UPSERT (MERGE) into orders_copy
--   GenerateTableFetch (SKIP/FIRST pages of 2) -> ExecuteSQLRecord -> PutDatabaseRecord INSERT_IGNORE into orders_paged
CREATE DATABASE demo WITH LOG;

CREATE TABLE orders (
    id       SERIAL NOT NULL PRIMARY KEY,
    customer VARCHAR(100) NOT NULL,
    amount   DECIMAL(12,2) NOT NULL,
    status   VARCHAR(20) NOT NULL,
    updated  DATETIME YEAR TO FRACTION(5) NOT NULL
);

CREATE TABLE orders_copy (
    id       INTEGER NOT NULL PRIMARY KEY,
    customer VARCHAR(100) NOT NULL,
    amount   DECIMAL(12,2) NOT NULL,
    status   VARCHAR(20) NOT NULL,
    updated  DATETIME YEAR TO FRACTION(5) NOT NULL
);

CREATE TABLE orders_paged (
    id       INTEGER NOT NULL PRIMARY KEY,
    customer VARCHAR(100) NOT NULL,
    amount   DECIMAL(12,2) NOT NULL,
    status   VARCHAR(20) NOT NULL,
    updated  DATETIME YEAR TO FRACTION(5) NOT NULL
);

INSERT INTO orders (customer, amount, status, updated) VALUES ('Acme',     120.50, 'NEW',     CURRENT YEAR TO FRACTION(5));
INSERT INTO orders (customer, amount, status, updated) VALUES ('Globex',   999.99, 'SHIPPED', CURRENT YEAR TO FRACTION(5));
INSERT INTO orders (customer, amount, status, updated) VALUES ('Initech',   42.00, 'NEW',     CURRENT YEAR TO FRACTION(5));
INSERT INTO orders (customer, amount, status, updated) VALUES ('Umbrella', 310.75, 'PAID',    CURRENT YEAR TO FRACTION(5));
INSERT INTO orders (customer, amount, status, updated) VALUES ('Hooli',     15.20, 'NEW',     CURRENT YEAR TO FRACTION(5));
