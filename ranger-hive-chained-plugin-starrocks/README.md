<!---
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Hive chained plugin for StarRocks

`StarRocksHiveChainedPlugin` is a chained plugin of the StarRocks FE Ranger plugin (service type
`starrocks`). It authorizes StarRocks resources of the external catalogs backed by a Hive
Metastore (Hive, Iceberg, ...) with the policies of the Ranger **Hive** service, so a Hive policy
on `db.table` allows the same table in StarRocks without a duplicate StarRocks policy.

## How it works

1. [Resource Mapping Manager](../resource-mapping-manager) (RMM) publishes `hive -> starrocks`
   mappings for the Hive databases and tables of the Metastore. A mapping links the Hive entity
   name (`<hms catalog>.<database>[.<table>]`) to its StarRocks name
   (`<starrocks catalog>.<database>[.<table>]`) for every StarRocks catalog listed in
   `ranger.rmm.starrocks.catalogs`.
2. The chained plugin polls the mapping changes from Ranger Admin (the same mechanism as the
   HDFS and Ozone chained plugins), keeps them in a local trie and persists them to a file, so
   mapping changes are applied without an FE restart.
3. For every StarRocks access request on `catalog.database[.table[.column]]` the plugin looks
   up the longest mapped prefix:
   * a table mapping authorizes the table and its columns,
   * a database mapping authorizes the database and, by name, every table/column in it
     (e.g. Hive views, which RMM does not publish),
   * no mapping (internal catalog, a catalog unknown to RMM, non-table resources such as
     `system`, `view`, `function`) leaves the request to the native StarRocks policies only.
4. The mapped Hive request is evaluated with the Hive service policies (access, row filter and
   data mask policies) and its result overrides the StarRocks result with the
   `POLICY_PRIORITY_OVERRIDE` priority (configurable, see below). The Hive evaluation is
   audited as an event of the Hive service.

## Access type mapping

StarRocks access types are the lower-cased StarRocks privilege names as sent by the FE plugin
(multi-word ones contain spaces). Defaults:

| Hive object | StarRocks access type                                                     | Hive access type |
|-------------|---------------------------------------------------------------------------|------------------|
| database    | `_any`, `usage`                                                           | `USE` (`_any`)   |
| database    | `create table`, `create view`, `create materialized view`, `create function` | `CREATE`      |
| database    | `drop` / `alter`                                                          | `DROP` / `ALTER` |
| table       | `_any`, `usage`                                                           | `USE` (`_any`)   |
| table       | `select`, `export`, `refresh`                                             | `SELECT`         |
| table       | `insert`, `update`, `delete`                                              | `UPDATE`         |
| table       | `drop` / `alter`                                                          | `DROP` / `ALTER` |
| column      | `_any`                                                                    | `USE` (`_any`)   |
| column      | `select`                                                                  | `SELECT`         |

A mapping is overridden with
`ranger.plugin.starrocks.hive.<database|table|column>.access.mappings.<access type>` where the
spaces of the access type are replaced with underscores. The value is a comma-separated list of
Hive access types (`_any` is accepted as a synonym of `USE`), e.g.

```xml
<property>
  <name>ranger.plugin.starrocks.hive.table.access.mappings.refresh</name>
  <value>alter</value>
</property>
<property>
  <name>ranger.plugin.starrocks.hive.database.access.mappings.create_table</name>
  <value>create,alter</value>
</property>
```

Access types not listed in the table above (`grant`, `node`, `operate`, `impersonate`, ...) are not
mapped: the request falls back to the native StarRocks policies.

## Configuration

### RMM

```xml
<property>
  <name>ranger.rmm.starrocks.catalogs</name>
  <value>hive_catalog,iceberg_catalog</value>
</property>
```

Run a full sync once after adding a catalog (`ranger.rmm.hms.sync.full=true`) to publish the
mappings of the already existing Hive entities.

### StarRocks FE (`ranger-starrocks-security.xml`)

See [conf/ranger-starrocks-security-chained.xml.template](conf/ranger-starrocks-security-chained.xml.template).

| Property                                                            | Default                             | Description |
|---------------------------------------------------------------------|-------------------------------------|-------------|
| `ranger.plugin.starrocks.chained.services`                          |                                     | Name of the Hive service in Ranger Admin |
| `ranger.plugin.starrocks.chained.services.<hive>.impl`              |                                     | `org.apache.ranger.hive.chained.starrocks.StarRocksHiveChainedPlugin` |
| `ranger.plugin.starrocks.hive.resource.mappings.file.location`      | `/opt/ranger/hive-resource-mappings` | Local copy of the mappings (must be writable by the FE process) |
| `ranger.plugin.starrocks.hive.resource.mappings.refresh.interval.ms`| `30000`                             | Mapping poll interval |
| `ranger.plugin.starrocks.hive.resource.mappings.file.flush.interval.ms` | `60000`                         | Local copy flush interval |
| `ranger.plugin.starrocks.chained.plugin.result.priority.high`       | `true`                              | Chained results get the override priority |
| `ranger.plugin.starrocks.bypass.chained.plugin.evaluation.if.access.is.determined` | `false`              | Skip the chained plugin when a StarRocks policy already decided |

The Hive service policies are downloaded with the Ranger Admin connection settings of the
StarRocks plugin (`ranger.plugin.starrocks.policy.rest.url`, SSL and Kerberos settings are
inherited by the chained plugin).

### Deployment

Put the jars of the `ranger-<version>-starrocks-hive-chained-plugin.tar.gz` archive
(`ranger-hive-chained-plugin-starrocks`, `ranger-hive-chained-plugin-base`, `ranger-hive-plugin`)
on the class path of the StarRocks FE next to the Ranger plugin libraries and restart the FE.

## Build

```shell
# the plugin jar and its dependencies
mvn -P ranger-starrocks-plugin install -DskipTests
# the ranger-<version>-starrocks-hive-chained-plugin.tar.gz archive is produced by the full build
mvn -P all package -DskipTests
```
