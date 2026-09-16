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

# Loads a NAR into a real apache/nifi container and checks that the Informix dialect service is
# listed, can be created, and enables without validation errors or ERROR log lines.
#
#   ci/nar-smoke.sh <nifi-version> <path-to-nar>
#
# Requires docker and curl. Exit code 0 only if every check passes.
set -euo pipefail

NIFI_VERSION=${1:?nifi version, e.g. 2.12.0}
NAR=${2:?path to .nar}
[ -f "$NAR" ] || { echo "NAR not found: $NAR"; exit 1; }
# absolute host path in the form Docker expects (pwd -W gives D:/... on Git Bash)
abs_dir() { cd "$1" && { pwd -W 2>/dev/null || pwd; }; }
NAR_ABS=$(abs_dir "$(dirname "$NAR")")/$(basename "$NAR")
CONTAINER=nifi-smoke-${NIFI_VERSION//./-}
PORT=${NIFI_SMOKE_PORT:-8443}
API="https://localhost:$PORT/nifi-api"
TYPE_SUFFIX=".InformixDatabaseDialectService"
PASSWORD=smoketestpassword

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

echo "== apache/nifi:$NIFI_VERSION with $(basename "$NAR")"
docker run -d --name "$CONTAINER" -p "$PORT:8443" \
    -e SINGLE_USER_CREDENTIALS_USERNAME=admin -e "SINGLE_USER_CREDENTIALS_PASSWORD=$PASSWORD" \
    -v "$NAR_ABS:/opt/nifi/nifi-current/nar_extensions/$(basename "$NAR"):ro" \
    "apache/nifi:$NIFI_VERSION" >/dev/null

api() { curl -ksS -X "$1" "$API$2" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" ${3:+-d "$3"}; }
TOKEN=""
for _ in $(seq 1 60); do
    TOKEN=$(curl -ksS -X POST "$API/access/token" -d "username=admin&password=$PASSWORD" 2>/dev/null || true)
    if [ "${#TOKEN}" -gt 20 ] && api GET /flow/process-groups/root >/dev/null 2>&1; then break; fi
    TOKEN=""; sleep 5
done
[ -n "$TOKEN" ] || { echo "FAIL: NiFi did not start"; docker logs "$CONTAINER" 2>&1 | tail -30; exit 1; }
echo "-- NiFi up"

# give the NAR auto-loader a moment; it polls the directory every few seconds
for _ in $(seq 1 12); do
    docker logs "$CONTAINER" 2>&1 | grep -q "Loaded extensions for .*nifi-informix-dialect-nar" && break
    sleep 5
done
docker logs "$CONTAINER" 2>&1 | grep -E "nifi-informix-dialect-nar|informix" | grep -iE "loaded|unable|fail|error|warn" | sed 's/^/   /' | head -5

TYPES=$(api GET /flow/controller-service-types)
echo "$TYPES" | grep -q "\"type\":\"[^\"]*$TYPE_SUFFIX\"" || { echo "FAIL: service type not listed"; exit 1; }
TYPE=$(echo "$TYPES" | grep -o "\"type\":\"[^\"]*$TYPE_SUFFIX\"" | head -1 | cut -d'"' -f4)
BUNDLE=$(echo "$TYPES" | sed 's/},{/}\n{/g' | grep "$TYPE_SUFFIX" | grep -o '"bundle":{[^}]*}' | head -1 | sed 's/"bundle"://')
echo "-- listed: $TYPE $BUNDLE"

ROOT=$(api GET /flow/process-groups/root | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
CS=$(api POST "/process-groups/$ROOT/controller-services" "{\"revision\":{\"version\":0},\"component\":{\"type\":\"$TYPE\",\"bundle\":$BUNDLE}}")
ID=$(echo "$CS" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
VERSION=$(echo "$CS" | grep -o '"version":[0-9]*' | head -1 | cut -d: -f2)
api PUT "/controller-services/$ID/run-status" "{\"revision\":{\"version\":$VERSION},\"state\":\"ENABLED\"}" >/dev/null
sleep 5
STATE=$(api GET "/controller-services/$ID")
echo "$STATE" | grep -q '"state":"ENABLED"' || { echo "FAIL: not ENABLED: $(echo "$STATE" | grep -o '"validationErrors":\[[^]]*\]')"; exit 1; }
echo "$STATE" | grep -q '"validationStatus":"VALID"' || { echo "FAIL: not VALID"; exit 1; }
echo "-- created and ENABLED, VALID"

ERRORS=$(docker logs "$CONTAINER" 2>&1 | grep -c "\bERROR\b" || true)
[ "$ERRORS" -eq 0 ] || { echo "FAIL: $ERRORS ERROR lines in log"; docker logs "$CONTAINER" 2>&1 | grep "\bERROR\b" | head -5; exit 1; }
echo "-- 0 ERROR lines in nifi-app.log"
echo "PASS apache/nifi:$NIFI_VERSION"
