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

cp -r /etc/ozone-plugin/ranger-ozone-plugin /opt/hadoop/
cp /etc/hive/ranger-hive-plugin/lib/ranger-hive-plugin-impl/*.jar /opt/hadoop/ranger-ozone-plugin/lib/libext/ranger-ozone-plugin-impl/
cp /opt/hadoop/ranger-ozone-plugin/ext/*.jar /opt/hadoop/ranger-ozone-plugin/lib/libext/ranger-ozone-plugin-impl/
rm /opt/hadoop/ranger-ozone-plugin/install/conf.templates/enable/ranger-ozone-security.xml
rm /opt/hadoop/ranger-ozone-plugin/install/conf.templates/enable/ranger-ozone-security-changes.cfg

cp -f /etc/ozone-config/ranger-ozone-plugin-install.properties /opt/hadoop/ranger-ozone-plugin/install.properties
cp -f /etc/ozone-config/ranger-ozone-setup.sh /opt/hadoop/ranger-ozone-plugin
cp -f /etc/ozone-config/enable-ozone-plugin.sh /opt/hadoop/ranger-ozone-plugin
chmod +x /opt/hadoop/ranger-ozone-plugin/ranger-ozone-setup.sh

mkdir -p /opt/hadoop/ranger-ozone-plugin/conf/
cp /etc/ozone-config/ranger-ozone-security.xml /opt/hadoop/ranger-ozone-plugin/conf/ranger-ozone-security.xml
cp /etc/ozone-config/ranger-hive-audit.xml /opt/hadoop/ranger-ozone-plugin/conf/ranger-hive-audit.xml

/opt/hadoop/ranger-ozone-plugin/ranger-ozone-setup.sh && /opt/hadoop/bin/ozone om
