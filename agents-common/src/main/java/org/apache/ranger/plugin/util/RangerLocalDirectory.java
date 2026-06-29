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

package org.apache.ranger.plugin.util;

import org.apache.ranger.audit.utils.LocalDirectoryResolver;

import java.io.IOException;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public final class RangerLocalDirectory {
	public static final String SUBDIR_MODE_DISABLED = "disabled";
	public static final String SUBDIR_MODE_PERUSER  = "peruser";
	public static final String SUBDIR_MODE_PERGROUP = "pergroup";

	private RangerLocalDirectory() {
	}

	public static ResolvedDirectory resolve(String baseDir, String subdirMode, Set<PosixFilePermission> defaultDirPerms, Set<PosixFilePermission> defaultFilePerms) {
		return new ResolvedDirectory(LocalDirectoryResolver.resolveLocalDirectory(baseDir, subdirMode, defaultDirPerms, defaultFilePerms));
	}

	public static final class ResolvedDirectory {
		private final LocalDirectoryResolver.ResolvedDirectory delegate;

		private ResolvedDirectory(LocalDirectoryResolver.ResolvedDirectory delegate) {
			this.delegate = delegate;
		}

		public String getPath() {
			return delegate.getPath();
		}

		public Set<PosixFilePermission> getDirPermissions() {
			return delegate.getDirPermissions();
		}

		public Set<PosixFilePermission> getFilePermissions() {
			return delegate.getFilePermissions();
		}

		public void ensureDirectory() throws IOException {
			delegate.ensureDirectory();
		}
	}
}
