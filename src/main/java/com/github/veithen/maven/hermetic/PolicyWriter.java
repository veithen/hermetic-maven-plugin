/*-
 * #%L
 * hermetic-maven-plugin
 * %%
 * Copyright (C) 2018 - 2019 Andreas Veithen
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

import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permission;

import org.apache.commons.text.StringEscapeUtils;

final class PolicyWriter {
    private final Writer out;

    PolicyWriter(Writer out) {
        this.out = out;
    }

    void start() throws IOException {
        out.write("grant {\n");
    }

    void writePermission(String permissionClassName, String targetName, String action) throws IOException {
        out.write("  permission ");
        out.write(permissionClassName);
        out.write(" \"");
        out.write(StringEscapeUtils.escapeJava(targetName));
        out.write('"');
        if (action != null) {
            out.write(", \"");
            out.write(StringEscapeUtils.escapeJava(action));
            out.write('"');
        }
        out.write(";\n");
    }

    void writePermission(Permission permission) throws IOException {
        String actions = permission.getActions();
        writePermission(permission.getClass().getName(), permission.getName(), actions.isEmpty() ? null : actions);
    }

    private void generateSymlinkPermissions(Path rootDir, Path dir, boolean recursive) throws IOException {
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(dir)) {
            for (Path path : directoryStream) {
                if (Files.isSymbolicLink(path)) {
                    Path target = dir.resolve(Files.readSymbolicLink(path)).normalize();
                    if (!target.startsWith(rootDir)) {
                        if (Files.isDirectory(path)) {
                            if (recursive) {
                                generateDirReadPermissions(target, true, true);
                            }
                        } else {
                            // We need to grant the readlink permission on the target of the link. This is
                            // counter-intuitive, but depending on the Java version and the value of the
                            // jdk.io.permissionsUseCanonicalPath system property, the permission may be
                            // checked against the canonical path, i.e. the link target.
                            writePermission(new FilePermission(target.toString(), "read,readlink"));
                        }
                    }
                } else if (recursive && Files.isDirectory(path)) {
                    generateSymlinkPermissions(rootDir, path, true);
                }
            }
        }
    }

    private void generateDirReadPermissions(Path dir, boolean recursive, boolean symlinks) throws IOException {
        String actions = symlinks ? "read,readlink" : "read";
        writePermission(new FilePermission(dir.toString(), actions));
        if (Files.exists(dir)) {
            writePermission(new FilePermission(dir.resolve(recursive ? "-" : "*").toString(), actions));
            if (symlinks) {
                generateSymlinkPermissions(dir, dir, recursive);
            }
        }
    }
    
    void generateDirReadPermissions(File dir, boolean recursive, boolean symlinks) throws IOException {
        generateDirReadPermissions(dir.getAbsoluteFile().toPath(), recursive, symlinks);
    }

    void end() throws IOException {
        out.write("};\n");
    }
}
