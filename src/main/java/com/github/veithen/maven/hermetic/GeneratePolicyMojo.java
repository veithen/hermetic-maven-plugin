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
import java.io.FileOutputStream;
import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.SocketPermission;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

@Mojo(name="generate-policy", defaultPhase=LifecyclePhase.GENERATE_TEST_RESOURCES, threadSafe=true)
public final class GeneratePolicyMojo extends AbstractMojo {
    @Parameter(property="project", readonly=true, required=true)
    private MavenProject project;

    @Parameter(property="session", readonly=true, required=true)
    private MavenSession session;

    @Parameter(property="mojoExecution", readonly=true, required=true)
    protected MojoExecution mojoExecution;

    @Parameter(defaultValue="${project.build.directory}/test.policy", required=true)
    private File outputFile;

    @Parameter(defaultValue="${project.build.directory}/secmgr.jar", readonly=true, required=true)
    private File securityManagerJarFile;

    @Parameter(defaultValue="false", required=true)
    private boolean skip;

    @Parameter(defaultValue="false", required=true)
    private boolean debug;

    @Parameter(defaultValue="false", required=true)
    private boolean allowExec;

    @Parameter(defaultValue="argLine", required=true)
    private String property;

    @Parameter(defaultValue="true", required=true)
    private boolean append;

    private static File getJavaHome() {
        File javaHome = new File(System.getProperty("java.home"));
        return javaHome.getName().equals("jre") ? javaHome.getParentFile() : javaHome;
    }

    private static boolean isDescendant(File dir, File path) {
        do {
            if (path.equals(dir)) {
                return true;
            }
            path = path.getParentFile();
        } while (path != null);
        return false;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip || project.getPackaging().equals("pom")) {
            return;
        }
        
        outputFile.getParentFile().mkdirs();
        try (Writer out = new OutputStreamWriter(new FileOutputStream(outputFile), "utf-8")) {
            PolicyWriter writer = new PolicyWriter(out);
            writer.start();
            File javaHome = getJavaHome();
            writer.generateDirPermissions(javaHome, Integer.MAX_VALUE, false);
            String extDirs = System.getProperty("java.ext.dirs");
            if (extDirs != null) {
                List<File> dirs = Stream.of(extDirs.split(Pattern.quote(File.pathSeparator)))
                        .map(File::new)
                        .filter(dir -> !isDescendant(javaHome, dir))
                        .collect(Collectors.toList());
                for (File dir : dirs) {
                    writer.generateDirPermissions(dir, 1, false);
                }
            }
            writer.generateDirPermissions(new File(System.getProperty("maven.home")), 0, false);
            writer.generateDirPermissions(new File(session.getSettings().getLocalRepository()), 0, false);
            writer.generateDirPermissions(project.getBasedir(), 0, false);
            writer.writePermission(new FilePermission(session.getRequest().getUserToolchainsFile().getAbsolutePath(), "read"));
            for (MavenProject project : session.getProjects()) {
                File file = project.getArtifact().getFile();
                if (file != null) {
                    writer.writePermission(new FilePermission(file.getAbsolutePath(), "read"));
                }
                for (Artifact attachedArtifact : project.getAttachedArtifacts()) {
                    writer.writePermission(new FilePermission(attachedArtifact.getFile().getAbsolutePath(), "read"));
                }
            }
            for (String dir : new String[] { project.getBuild().getDirectory(), System.getProperty("java.io.tmpdir") }) {
                writer.generateDirPermissions(new File(dir), 0, true);
            }
            writer.writePermission(new SocketPermission("localhost", "connect,listen,accept,resolve"));
            if (allowExec) {
                writer.writePermission(new FilePermission("<<ALL FILES>>", "execute"));
            }
            writer.end();
        } catch (IOException ex) {
            throw new MojoFailureException(String.format("Failed to write %s", outputFile), ex);
        }
        
        try (InputStream in = GeneratePolicyMojo.class.getResourceAsStream("HermeticSecurityManager.class")) {
            try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(securityManagerJarFile))) {
                jar.putNextEntry(new JarEntry("secmgr/HermeticSecurityManager.class"));
                Remapper remapper = new Remapper() {
                    @Override
                    public String map(String typeName) {
                        if (typeName.equals("com/github/veithen/maven/hermetic/HermeticSecurityManager")) {
                            return "secmgr/HermeticSecurityManager";
                        }
                        return super.map(typeName);
                    }
                };
                ClassReader reader = new ClassReader(in);
                ClassWriter writer = new ClassWriter(reader, 0);
                reader.accept(new ClassRemapper(writer, remapper), 0);
                jar.write(writer.toByteArray());
            }
        } catch (IOException ex) {
            throw new MojoFailureException("Failed to generate security manager JAR", ex);
        }
        
        Properties props = project.getProperties();
        StringBuilder buffer = new StringBuilder();
        if (append) {
            String currentValue = props.getProperty(property);
            if (currentValue != null) {
                buffer.append(currentValue);
                buffer.append(" ");
            }
        }
        buffer.append("-Xbootclasspath/a:");
        buffer.append(securityManagerJarFile.toString());
        buffer.append(" -Djava.security.manager=secmgr.HermeticSecurityManager");
        // "==" sets the policy instead of adding additional permissions.
        buffer.append(" -Djava.security.policy==");
        buffer.append(outputFile.getAbsolutePath().replace('\\', '/'));
        if (debug) {
            buffer.append(" -Djava.security.debug=access,failure");
        }
        String value = buffer.toString();
        props.setProperty(property, value);
        getLog().info(String.format("%s set to %s", property, value));
    }
}
