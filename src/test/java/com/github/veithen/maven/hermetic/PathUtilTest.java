/*-
 * #%L
 * hermetic-maven-plugin
 * %%
 * Copyright (C) 2018 - 2024 Andreas Veithen
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.github.veithen.maven.hermetic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

public class PathUtilTest {
    private FileSystem fs;

    @BeforeEach
    public void setUp() {
        fs = Jimfs.newFileSystem(Configuration.unix());
    }

    @Test
    public void testExtDirWithSymlink() throws Exception {
        Path extDir = fs.getPath("/Library/Java/Extensions");
        Files.createDirectories(extDir);
        Path libDir = fs.getPath("/usr/local/lib");
        Files.createDirectories(libDir);
        Path lib = libDir.resolve("libdummy.dylib");
        Files.createFile(lib);
        Path symlink = extDir.resolve("libdummy.dylib");
        Files.createSymbolicLink(symlink, lib);
        assertThat(PathUtil.enumeratePaths(extDir, 1))
                .containsExactlyInAnyOrder(
                        PathSpec.create(extDir, 0, true), PathSpec.create(lib, 1, false));
    }

    @Test
    public void testJavaHomeWithSymlink() throws Exception {
        Path javaHome = fs.getPath("/usr/lib/jvm/java-8-oracle");
        Path securityDir = javaHome.resolve("jre/lib/security");
        Files.createDirectories(securityDir);
        Path symlink = securityDir.resolve("cacerts");
        Path etcDir = fs.getPath("/etc/ssl/certs/java");
        Files.createDirectories(etcDir);
        Path cacerts = etcDir.resolve("cacerts");
        Files.createFile(cacerts);
        Files.createSymbolicLink(symlink, cacerts);
        assertThat(PathUtil.enumeratePaths(javaHome, Integer.MAX_VALUE))
                .containsExactlyInAnyOrder(
                        PathSpec.create(javaHome, 0, true), PathSpec.create(cacerts, 4, false));
    }

    @Test
    public void testNonExistingDirectory() throws Exception {
        Path dir = fs.getPath("/Users/dummy/Library/Java/Extensions");
        assertThat(PathUtil.enumeratePaths(dir, 0))
                .containsExactlyInAnyOrder(PathSpec.create(dir, 0, true));
    }

    @Test
    public void testSymlinkToDirectory() throws Exception {
        Path dir = fs.getPath("/dir1");
        Files.createDirectory(dir);
        Path target = fs.getPath("/dir2");
        Files.createDirectory(target);
        Files.createSymbolicLink(dir.resolve("link"), target);
        assertThat(PathUtil.enumeratePaths(dir, Integer.MAX_VALUE))
                .containsExactlyInAnyOrder(
                        PathSpec.create(dir, 0, true), PathSpec.create(target, 1, true));
    }

    @Test
    public void testDepth0() throws Exception {
        Path dir = fs.getPath("/dir");
        Files.createDirectories(dir);
        Files.createSymbolicLink(dir.resolve("link"), fs.getPath("/some/target"));
        assertThat(PathUtil.enumeratePaths(dir, 0))
                .containsExactlyInAnyOrder(PathSpec.create(dir, 0, true));
    }

    @Test
    public void testAncestorIsSymlink() throws Exception {
        Path privateVar = fs.getPath("/private/var");
        Path privateVarTmp = privateVar.resolve("tmp");
        Files.createDirectories(privateVarTmp);
        Path var = fs.getPath("/var");
        Files.createSymbolicLink(var, privateVar);
        Path tmp = var.resolve("tmp");
        assertThat(PathUtil.enumeratePaths(tmp, 0))
                .containsExactlyInAnyOrder(
                        PathSpec.create(tmp, 0, true), PathSpec.create(privateVarTmp, 0, true));
    }
}
