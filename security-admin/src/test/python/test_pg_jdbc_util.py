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

import os
import sys
import unittest

SCRIPTS_DIR = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'scripts'))
if SCRIPTS_DIR not in sys.path:
	sys.path.insert(0, SCRIPTS_DIR)

from pg_jdbc_util import (
	DEFAULT_PG_PORT,
	TARGET_SERVER_TYPE_PRIMARY,
	build_pg_host_segment,
	build_pg_jdbc_url,
	build_pg_query_params,
	is_multi_host,
	parse_pg_hosts,
)


class TestIsMultiHost(unittest.TestCase):
	def test_single_host(self):
		self.assertFalse(is_multi_host('localhost'))
		self.assertFalse(is_multi_host('host1:5432'))

	def test_multi_host(self):
		self.assertTrue(is_multi_host('host1:5432,host2:5432'))
		self.assertTrue(is_multi_host('h1,h2'))

	def test_none_and_empty(self):
		self.assertFalse(is_multi_host(None))
		self.assertFalse(is_multi_host(''))

	def test_non_string_input(self):
		self.assertFalse(is_multi_host(5432))
		self.assertFalse(is_multi_host(0))


class TestParsePgHosts(unittest.TestCase):
	def test_host_only(self):
		self.assertEqual(parse_pg_hosts('localhost'), [('localhost', DEFAULT_PG_PORT)])

	def test_host_with_port(self):
		self.assertEqual(parse_pg_hosts('db.example.com:5433'), [('db.example.com', 5433)])

	def test_multi_host_with_ports(self):
		self.assertEqual(
			parse_pg_hosts('host1:5432,host2:5433'),
			[('host1', 5432), ('host2', 5433)],
		)

	def test_multi_host_default_port(self):
		self.assertEqual(
			parse_pg_hosts('host1,host2'),
			[('host1', DEFAULT_PG_PORT), ('host2', DEFAULT_PG_PORT)],
		)

	def test_normalizes_case(self):
		self.assertEqual(parse_pg_hosts('HOST1:5432,Host2'), [('host1', 5432), ('host2', DEFAULT_PG_PORT)])

	def test_empty(self):
		self.assertEqual(parse_pg_hosts(''), [])
		self.assertEqual(parse_pg_hosts(None), [])


class TestBuildPgHostSegment(unittest.TestCase):
	def test_single_host_adds_port(self):
		self.assertEqual(build_pg_host_segment('localhost'), 'localhost:5432')
		self.assertEqual(build_pg_host_segment('localhost', 3306), 'localhost:3306')

	def test_single_host_with_port_preserved(self):
		self.assertEqual(build_pg_host_segment('localhost:5433'), 'localhost:5433')

	def test_multi_host_preserved(self):
		segment = 'host1:5432,host2:5432'
		self.assertEqual(build_pg_host_segment(segment), segment.lower())
		self.assertEqual(build_pg_host_segment(segment, 9999), segment.lower())

	def test_empty(self):
		self.assertEqual(build_pg_host_segment(''), '')
		self.assertEqual(build_pg_host_segment(None), '')


class TestBuildPgQueryParams(unittest.TestCase):
	def test_single_host_no_change(self):
		ssl = '?ssl=true&sslmode=verify-full'
		self.assertEqual(build_pg_query_params(ssl, is_multi_host_flag=False), ssl)
		self.assertEqual(build_pg_query_params('', is_multi_host_flag=False), '')

	def test_multi_host_adds_primary(self):
		self.assertEqual(
			build_pg_query_params('', is_multi_host_flag=True),
			'?%s' % TARGET_SERVER_TYPE_PRIMARY,
		)

	def test_multi_host_with_ssl(self):
		ssl = '?ssl=true&sslmode=verify-full'
		expected = ssl + '&' + TARGET_SERVER_TYPE_PRIMARY
		self.assertEqual(build_pg_query_params(ssl, is_multi_host_flag=True), expected)

	def test_multi_host_detected_from_db_host(self):
		self.assertEqual(
			build_pg_query_params('?ssl=true', db_host='h1:5432,h2:5432'),
			'?ssl=true&%s' % TARGET_SERVER_TYPE_PRIMARY,
		)

	def test_no_duplicate_target_server_type(self):
		existing = '?ssl=true&%s' % TARGET_SERVER_TYPE_PRIMARY
		self.assertEqual(build_pg_query_params(existing, is_multi_host_flag=True), existing)

	def test_multi_host_with_full_ssl_string(self):
		# real db_ssl_param from db_setup.py when db_ssl_certificate_file is set
		ssl = '?ssl=true&sslmode=verify-full&sslrootcert=/etc/ssl/certs/ca.pem'
		expected = ssl + '&' + TARGET_SERVER_TYPE_PRIMARY
		self.assertEqual(build_pg_query_params(ssl, is_multi_host_flag=True), expected)

	def test_multi_host_with_sslfactory_ssl_string(self):
		# real db_ssl_param from dba_script.py (NonValidatingFactory)
		ssl = '?ssl=true&sslfactory=org.postgresql.ssl.NonValidatingFactory'
		expected = ssl + '&' + TARGET_SERVER_TYPE_PRIMARY
		self.assertEqual(build_pg_query_params(ssl, is_multi_host_flag=True), expected)

	def test_param_without_question_mark_multi_host(self):
		# non-standard input without '?' — function must prepend '?' correctly
		result = build_pg_query_params('ssl=true', is_multi_host_flag=True)
		self.assertTrue(result.startswith('?'))
		self.assertIn(TARGET_SERVER_TYPE_PRIMARY, result)

	def test_single_host_full_ssl_string_unchanged(self):
		# single host — long SSL string must not be modified
		ssl = '?ssl=true&sslmode=verify-full&sslrootcert=/etc/ssl/certs/ca.pem'
		self.assertEqual(build_pg_query_params(ssl, is_multi_host_flag=False), ssl)

	def test_none_existing_ssl_param(self):
		self.assertEqual(build_pg_query_params(None, is_multi_host_flag=False), '')
		self.assertEqual(
			build_pg_query_params(None, is_multi_host_flag=True),
			'?' + TARGET_SERVER_TYPE_PRIMARY,
		)


class TestBuildPgJdbcUrl(unittest.TestCase):
	def test_basic(self):
		url = build_pg_jdbc_url('localhost:5432', 'ranger', '?ssl=true')
		self.assertEqual(url, 'jdbc:postgresql://localhost:5432/ranger?ssl=true')

	def test_multi_host_patroni(self):
		host = 'h1:5432,h2:5432'
		query = build_pg_query_params('', is_multi_host_flag=True)
		url = build_pg_jdbc_url(host, 'ranger', query)
		self.assertEqual(
			url,
			'jdbc:postgresql://h1:5432,h2:5432/ranger?%s' % TARGET_SERVER_TYPE_PRIMARY,
		)


if __name__ == '__main__':
	unittest.main()
