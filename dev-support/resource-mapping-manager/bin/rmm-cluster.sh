#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euo pipefail

DEFAULT_SERVICE="hdfs"
SCHEMATOOL_CMD='/opt/hive/bin/schematool -initSchema -dbType hive -metaDbType postgres -url jdbc:hive2://localhost:10000/default'
BEELINE_CMD="beeline -u 'jdbc:hive2://localhost:10000/'"

usage() {
  echo "Usage: $0 <start|stop|beeline> [--service=hdfs|ozone]"
  echo ""
  echo "Examples:"
  echo "  $0 start"
  echo "  $0 stop --service=ozone"
  echo "  $0 beeline"
  exit 1
}

die() {
  echo "Error: $*" >&2
  exit 1
}

get_compose_file() {
  case "$1" in
    hdfs) echo "docker-compose-rmm-hdfs.yaml" ;;
    ozone) echo "docker-compose-rmm-ozone.yaml" ;;
    *) die "Unknown service: $1. Must be one of: hdfs, ozone." ;;
  esac
}

[[ $# -lt 1 ]] && usage

COMMAND="$1"
shift

SERVICE="$DEFAULT_SERVICE"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --service=*) SERVICE="${1#*=}" ;;
    *) die "Unknown option: $1" ;;
  esac
  shift
done

COMPOSE_FILE="$(get_compose_file "$SERVICE")"

start_cluster() {
  docker compose -f "$COMPOSE_FILE" up -d
  docker compose -f "$COMPOSE_FILE" exec rmm-hive-server2 bash -c "$SCHEMATOOL_CMD"
}

stop_cluster() {
  docker compose -f "$COMPOSE_FILE" down -v
}

beeline_shell() {
  docker compose -f "$COMPOSE_FILE" exec -it rmm-hive-server2 $BEELINE_CMD
}

case "$COMMAND" in
  start) start_cluster ;;
  stop) stop_cluster ;;
  beeline) beeline_shell ;;
  *) usage ;;
esac
