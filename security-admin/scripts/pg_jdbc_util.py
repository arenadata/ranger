#!/usr/bin/env python

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""Helpers for PostgreSQL JDBC URL host segments and query parameters (Patroni multi-host)"""

import argparse
import sys

DEFAULT_PG_PORT = 5432
TARGET_SERVER_TYPE_PRIMARY = 'targetServerType=primary'


def is_multi_host(db_host):
	if db_host is None:
		return False
	return ',' in str(db_host)


def parse_pg_hosts(db_host, default_port=DEFAULT_PG_PORT):
	"""
	Parse db_host into a list of (host, port) tuples

	Supported formats:
	  host
	  host:port
	  host1:port1,host2:port2
	  host1,host2  (default_port applied when port omitted)
	"""
	if db_host is None or not str(db_host).strip():
		return []

	result = []
	for segment in str(db_host).split(','):
		segment = segment.strip().lower()
		if not segment:
			continue
		if ':' in segment:
			host_part, port_part = segment.rsplit(':', 1)
			if port_part.isdigit():
				result.append((host_part, int(port_part)))
			else:
				result.append((segment, default_port))
		else:
			result.append((segment, default_port))
	return result


def build_pg_host_segment(host, port=None):
	"""
	Build the host segment for jdbc:postgresql://{segment}/{db}

	For Patroni multi-host (comma in host), returns host unchanged
	For a single host without port, appends :port (default 5432)
	"""
	if host is None:
		return ''
	host = str(host).strip().lower()
	if not host:
		return ''
	if is_multi_host(host):
		return host
	port_str = str(port if port is not None else DEFAULT_PG_PORT).strip()
	if ':' in host:
		return host
	return '%s:%s' % (host, port_str)


def build_pg_query_params(existing_ssl_param, is_multi_host_flag=None, db_host=None):
	"""
	Merge SSL/query parameters with targetServerType=primary for multi-host URLs

	existing_ssl_param is typically empty or starts with '?' (e.g. ?ssl=true&sslmode=...)
	When connecting to multiple hosts, PostgreSQL JDBC may pick a read-only replica;
	targetServerType=primary directs DDL/migration traffic to the Patroni leader
	"""
	if is_multi_host_flag is None:
		is_multi_host_flag = is_multi_host(db_host)

	param = existing_ssl_param if existing_ssl_param is not None else ''
	if not is_multi_host_flag:
		return param
	if TARGET_SERVER_TYPE_PRIMARY in param:
		return param
	if not param:
		return '?' + TARGET_SERVER_TYPE_PRIMARY
	if param.startswith('?'):
		return param + '&' + TARGET_SERVER_TYPE_PRIMARY
	return '?' + param + '&' + TARGET_SERVER_TYPE_PRIMARY


def build_pg_jdbc_url(host_segment, db_name, query_suffix=''):
	host_segment = host_segment if host_segment is not None else ''
	db_name = db_name if db_name is not None else ''
	query_suffix = query_suffix if query_suffix is not None else ''
	return 'jdbc:postgresql://%s/%s%s' % (host_segment, db_name, query_suffix)


def _main():
	parser = argparse.ArgumentParser(description='PostgreSQL JDBC URL helpers for Ranger install scripts')
	sub = parser.add_subparsers(dest='command', required=True)

	query_parser = sub.add_parser('build-query-params', help='Build JDBC query suffix for SSL + Patroni')
	query_parser.add_argument('--ssl-param', default='', help='Existing query string (e.g. ?ssl=true)')
	query_parser.add_argument('--db-host', default='', help='db_host value to detect multi-host')

	host_parser = sub.add_parser('build-host-segment', help='Build JDBC host segment from host and port')
	host_parser.add_argument('--host', required=True)
	host_parser.add_argument('--port', default=str(DEFAULT_PG_PORT))

	args = parser.parse_args()
	if args.command == 'build-query-params':
		multi = is_multi_host(args.db_host)
		sys.stdout.write(build_pg_query_params(args.ssl_param, is_multi_host_flag=multi))
	elif args.command == 'build-host-segment':
		sys.stdout.write(build_pg_host_segment(args.host, args.port))
	return 0


if __name__ == '__main__':
	sys.exit(_main())
