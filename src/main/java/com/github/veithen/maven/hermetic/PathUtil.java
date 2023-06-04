/*-
 * #%L
 * hermetic-maven-plugin
 * %%
 * Copyright (C) 2018 - 2023 Andreas Veithen
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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class PathUtil {
    private PathUtil() {}

    private static void enumeratePaths(
            Path rootDir, Path dir, int depth, int maxDepth, Collection<PathSpec> result)
            throws IOException {
        if (dir == rootDir) {
            result.add(PathSpec.create(dir, depth, true));
        }
        if (!Files.exists(dir) || depth == maxDepth) {
            return;
        }
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(dir)) {
            for (Path path : directoryStream) {
                if (Files.isSymbolicLink(path)) {
                    Path target = dir.resolve(Files.readSymbolicLink(path)).normalize();
                    if (!target.startsWith(rootDir)) {
                        if (Files.isDirectory(path)) {
                            enumeratePaths(target, target, depth + 1, maxDepth, result);
                        } else {
                            result.add(PathSpec.create(target, depth + 1, false));
                        }
                    }
                } else if (Files.isDirectory(path)) {
                    enumeratePaths(rootDir, path, depth + 1, maxDepth, result);
                }
            }
        }
    }

    static Collection<PathSpec> enumeratePaths(Path dir, int maxDepth) throws IOException {
        Set<PathSpec> result = new HashSet<>();
        enumeratePaths(dir, dir, 0, maxDepth, result);
        List<PathSpec> canonicalized = new ArrayList<>(result.size());
        for (PathSpec pathSpec : result) {
            if (Files.exists(pathSpec.path())) {
                canonicalized.add(
                        PathSpec.create(
                                pathSpec.path().toRealPath(),
                                pathSpec.depth(),
                                pathSpec.directory()));
            }
        }
        result.addAll(canonicalized);
        return result;
    }
}
