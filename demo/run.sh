#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# One command demo: builds the NAR, fetches the IBM JDBC driver, starts Informix + NiFi,
# imports the flow, sets the database password, starts everything and shows rows arriving.
# Requires: docker (compose v2), mvn, java 21, curl. Tested with Git Bash on Windows and bash on Linux.
set -euo pipefail

cd "$(dirname "$0")"
DEMO_DIR=$(pwd)
# project version from the parent pom unless overridden
NAR_VERSION=${NAR_VERSION:-$(sed -n 's#.*<version>\(.*\)</version>.*#\1#p' ../pom.xml | head -1)}
NAR="../nifi-informix-dialect-nar/target/nifi-informix-dialect-nar-${NAR_VERSION}.nar"
DRIVER_VERSION=${INFORMIX_JDBC_VERSION:-15.0.1.4}
DRIVER="drivers/informix-jdbc-${DRIVER_VERSION}.jar"
NIFI_URL=${NIFI_URL:-https://localhost:8443/nifi-api}
NIFI_USER=admin
NIFI_PASSWORD=adminadminadmin
DB_PASSWORD=in4mix

step() { printf '\n==> %s\n' "$*"; }

if [ ! -f "$NAR" ]; then
    step "Building the NAR (mvn -q package -DskipTests)"
    (cd .. && mvn -q -ntp package -DskipTests)
fi
[ -f "$NAR" ] || { echo "NAR not found at $NAR"; exit 1; }

if [ ! -f "$DRIVER" ]; then
    step "Downloading IBM Informix JDBC driver ${DRIVER_VERSION} from Maven Central (IBM license applies)"
    curl -fsSL "https://repo1.maven.org/maven2/com/ibm/informix/jdbc/${DRIVER_VERSION}/jdbc-${DRIVER_VERSION}.jar" -o "$DRIVER"
fi

step "Starting Informix and NiFi (first run pulls ~2 GB of images; Informix takes about a minute to initialise)"
export NAR_VERSION
docker compose up -d

step "Waiting for NiFi"
api() { # method path [json-body]
    if [ $# -ge 3 ]; then
        curl -ksS -X "$1" "$NIFI_URL$2" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "$3"
    else
        curl -ksS -X "$1" "$NIFI_URL$2" -H "Authorization: Bearer $TOKEN"
    fi
}
first_id() { sed 's/.*"id" *: *"\([^"]*\)".*/\1/' | head -1; }
TOKEN=""
for _ in $(seq 1 60); do
    TOKEN=$(curl -ksS -X POST "$NIFI_URL/access/token" -d "username=$NIFI_USER&password=$NIFI_PASSWORD" 2>/dev/null || true)
    if [ -n "$TOKEN" ] && [ "${#TOKEN}" -gt 20 ] && api GET /flow/process-groups/root >/dev/null 2>&1; then
        break
    fi
    TOKEN=""
    sleep 5
done
[ -n "$TOKEN" ] || { echo "NiFi did not become ready; check: docker compose logs nifi"; exit 1; }
ROOT=$(api GET /flow/process-groups/root | grep -o '"id":"[^"]*"' | first_id)

if api GET "/process-groups/$ROOT/process-groups" | grep -q '"name":"Informix demo"'; then
    step "Flow already imported; leaving it as it is"
else
    step "Importing the flow definition"
    PG=$(curl -ksS -X POST "$NIFI_URL/process-groups/$ROOT/process-groups/upload" -H "Authorization: Bearer $TOKEN" \
        -F "groupName=Informix demo" -F "positionX=0" -F "positionY=0" -F "clientId=demo" -F "disconnectedNodeAcknowledged=false" \
        -F "file=@nifi/informix-demo-flow.json;type=application/json" | grep -o '"id":"[^"]*"' | first_id)
    echo "process group $PG"

    step "Setting the Informix password on the connection pool (sensitive values are never exported)"
    DBCP=$(api GET "/flow/process-groups/$PG/controller-services" | sed 's/},{/}\n{/g' | grep '"name":"Informix Connection Pool"' | grep -o '"id":"[^"]*"' | first_id)
    VERSION=$(api GET "/controller-services/$DBCP" | grep -o '"version":[0-9]*' | head -1 | cut -d: -f2)
    api PUT "/controller-services/$DBCP" "{\"revision\":{\"version\":$VERSION,\"clientId\":\"demo\"},\"component\":{\"id\":\"$DBCP\",\"properties\":{\"Password\":\"$DB_PASSWORD\"}}}" >/dev/null

    step "Enabling controller services and starting the flow"
    api PUT "/flow/process-groups/$PG/controller-services" "{\"id\":\"$PG\",\"state\":\"ENABLED\"}" >/dev/null
    sleep 5
    api PUT "/flow/process-groups/$PG" "{\"id\":\"$PG\",\"state\":\"RUNNING\"}" >/dev/null
fi

step "Waiting for the first rows to be copied"
sleep 20
show() { docker exec nifi-informix-demo-db bash -lc "echo 'SELECT id, customer, amount, status FROM orders_copy ORDER BY id;' | dbaccess demo - 2>&1" | grep -v '^$' | grep -v 'Database'; }
show

step "Inserting two more orders into Informix"
docker exec nifi-informix-demo-db bash -lc "printf \"INSERT INTO orders (customer, amount, status, updated) VALUES ('Stark', 5000.00, 'NEW', CURRENT YEAR TO FRACTION(5));\nINSERT INTO orders (customer, amount, status, updated) VALUES ('Wayne', 77.70, 'PAID', CURRENT YEAR TO FRACTION(5));\n\" | dbaccess demo - >/dev/null 2>&1"
sleep 12
show

cat <<EOF

Done. NiFi UI: https://localhost:8443/nifi  (user $NIFI_USER / password $NIFI_PASSWORD, self-signed certificate)
Informix:      jdbc:informix-sqli://localhost:9088/demo:INFORMIXSERVER=informix  (informix / $DB_PASSWORD)
Add rows to 'orders' and watch them appear in 'orders_copy' within a few seconds.
Stop and remove everything with: docker compose down -v
EOF
