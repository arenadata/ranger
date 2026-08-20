#!/bin/bash

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

# Publishable Ranger Admin entrypoint: first-start DB setup, then embedded Tomcat.
# Does not seed sandbox demo services (dev_hdfs/dev_hive/...) and does not
# require docker-compose hostname "ranger".
# Requires PostgreSQL reachable as db_host (default ranger-db), override with RANGER_DB_HOST.

INSTALL_PROPERTIES="${RANGER_HOME}/admin/install.properties"

if [ -n "${RANGER_DB_HOST}" ] && [ -f "${INSTALL_PROPERTIES}" ]
then
  sed -i "s|^db_host=.*|db_host=${RANGER_DB_HOST}|" "${INSTALL_PROPERTIES}"
fi

if [ ! -e ${RANGER_HOME}/.setupDone ]
then
  SETUP_RANGER=true
else
  SETUP_RANGER=false
fi

if [ "${SETUP_RANGER}" == "true" ]
then
  cd "${RANGER_HOME}"/admin || exit
  if ./setup.sh;
  then
    touch "${RANGER_HOME}"/.setupDone

  else
    echo "Ranger Admin Setup Script didn't complete proper execution."
    exit 1
  fi
fi

cd ${RANGER_HOME}/admin && ./ews/ranger-admin-services.sh start

RANGER_ADMIN_PID=`ps -ef  | grep -v grep | grep -i "org.apache.ranger.server.tomcat.EmbeddedServer" | awk '{print $2}'`

# prevent the container from exiting
if [ -z "$RANGER_ADMIN_PID" ]
then
  echo "Ranger Admin process probably exited, no process id found!"
  exit 1
else
  tail --pid=$RANGER_ADMIN_PID -f /dev/null
fi
