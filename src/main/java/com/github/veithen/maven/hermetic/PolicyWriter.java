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

import java.io.IOException;
import java.io.Writer;
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

    void end() throws IOException {
        out.write("};\n");
    }
}
