/*-
 * #%L
 * hermetic-maven-plugin
 * %%
 * Copyright (C) 2018 - 2021 Andreas Veithen
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
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketPermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;

@Mojo(
        name = "generate-policy",
        defaultPhase = LifecyclePhase.GENERATE_TEST_RESOURCES,
        threadSafe = true)
public final class GeneratePolicyMojo extends AbstractMojo {
    private static final String[] safeMethods = {
        "org.apache.tools.ant.types.Path.addExisting",
        "org.apache.tools.ant.util.JavaEnvUtils.getJdkExecutable",
    };

    @Parameter(property = "project", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "session", readonly = true, required = true)
    private MavenSession session;

    @Parameter(property = "mojoExecution", readonly = true, required = true)
    protected MojoExecution mojoExecution;

    @Component private ArtifactResolver resolver;

    @Parameter(defaultValue = "${project.build.directory}/test.policy", required = true)
    private File outputFile;

    @Parameter(defaultValue = "false", required = true)
    private boolean skip;

    @Parameter(defaultValue = "false", required = true)
    private boolean debug;

    @Parameter(defaultValue = "false", required = true)
    private boolean allowExec;

    @Parameter(defaultValue = "false", required = true)
    private boolean allowCrossProjectAccess;

    @Parameter(defaultValue = "argLine", required = true)
    private String property;

    @Parameter(defaultValue = "true", required = true)
    private boolean append;

    @Parameter(defaultValue = "false", required = true)
    private boolean generatePolicyOnly;

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
        Log log = getLog();

        if (skip || project.getPackaging().equals("pom")) {
            return;
        }

        File projectDir = project.getBasedir();
        if (allowCrossProjectAccess) {
            File dir = projectDir;
            while ((dir = dir.getParentFile()) != null) {
                if (new File(dir, "pom.xml").exists()) {
                    projectDir = dir;
                }
            }
        }

        outputFile.getParentFile().mkdirs();
        try (Writer out = new OutputStreamWriter(new FileOutputStream(outputFile), "utf-8")) {
            PolicyWriter writer = new PolicyWriter(out, log);
            writer.start();

            String javaHomeProperty = System.getProperty("java.home");
            if (log.isDebugEnabled()) {
                log.debug("java.home = " + javaHomeProperty);
            }
            File javaHome = new File(javaHomeProperty);
            File jdkHome = javaHome.getName().equals("jre") ? javaHome.getParentFile() : javaHome;
            if (log.isDebugEnabled()) {
                log.debug("JDK home is " + jdkHome);
            }
            writer.generateDirPermissions(jdkHome, Integer.MAX_VALUE, false);
            String extDirs = System.getProperty("java.ext.dirs");
            if (extDirs != null) {
                List<File> dirs =
                        Stream.of(extDirs.split(Pattern.quote(File.pathSeparator)))
                                .map(File::new)
                                .filter(dir -> !isDescendant(jdkHome, dir))
                                .collect(Collectors.toList());
                for (File dir : dirs) {
                    writer.generateDirPermissions(dir, 1, false);
                }
            }
            writer.generateDirPermissions(
                    new File(System.getProperty("maven.home")), Integer.MAX_VALUE, false);
            writer.generateDirPermissions(
                    new File(session.getSettings().getLocalRepository()), 0, false);
            writer.generateDirPermissions(projectDir, 0, false);
            writer.writePermission(
                    new FilePermission(
                            session.getRequest().getUserToolchainsFile().getAbsolutePath(),
                            "read"));
            for (MavenProject project : session.getProjects()) {
                File file = project.getArtifact().getFile();
                if (file != null) {
                    writer.writePermission(new FilePermission(file.getAbsolutePath(), "read"));
                }
                for (Artifact attachedArtifact : project.getAttachedArtifacts()) {
                    writer.writePermission(
                            new FilePermission(
                                    attachedArtifact.getFile().getAbsolutePath(), "read"));
                }
            }
            for (String dir :
                    new String[] {
                        project.getBuild().getDirectory(), System.getProperty("java.io.tmpdir")
                    }) {
                writer.generateDirPermissions(new File(dir), 0, true);
            }
            // Some code (like maven-bundle-plugin) uses File#isDirectory() on the home directory.
            // Allow this, but don't allow access to other files.
            writer.writePermission(new FilePermission(System.getProperty("user.home"), "read"));
            writer.writePermission(
                    new SocketPermission("localhost", "connect,listen,accept,resolve"));
            for (NetworkInterface iface :
                    Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
                    InetAddress addr = ifaceAddr.getAddress();
                    if (addr.isLoopbackAddress() || !addr.isLinkLocalAddress()) {
                        writer.writePermission(
                                new SocketPermission(
                                        addr.getHostAddress(), "connect,listen,accept,resolve"));
                    }
                }
            }
            if (allowExec) {
                writer.writePermission(new FilePermission("<<ALL FILES>>", "execute"));
            }
            writer.end();
        } catch (IOException ex) {
            throw new MojoFailureException(String.format("Failed to write %s", outputFile), ex);
        }

        Properties props = project.getProperties();
        List<String> args = new ArrayList<>();
        if (append) {
            String currentValue = props.getProperty(property);
            if (currentValue != null && !currentValue.isEmpty()) {
                args.add(currentValue);
            }
        }
        if (!generatePolicyOnly) {
            DefaultArtifactCoordinate securityManagerArtifact = new DefaultArtifactCoordinate();
            securityManagerArtifact.setGroupId("com.github.veithen");
            securityManagerArtifact.setArtifactId("hermetic-security-manager");
            securityManagerArtifact.setVersion("1.2.0-SNAPSHOT");
            securityManagerArtifact.setExtension("jar");
            File securityManagerJarFile;
            try {
                DefaultProjectBuildingRequest projectBuildingRequest =
                        new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
                projectBuildingRequest.setRemoteRepositories(
                        project.getPluginArtifactRepositories());
                securityManagerJarFile =
                        resolver.resolveArtifact(projectBuildingRequest, securityManagerArtifact)
                                .getArtifact()
                                .getFile();
            } catch (ArtifactResolverException ex) {
                throw new MojoFailureException("Unable to resolve artifact", ex);
            }

            args.add("-Xbootclasspath/a:" + securityManagerJarFile.toString());
            args.add("-Djava.security.manager=com.github.veithen.hermetic.HermeticSecurityManager");
            args.add("-Dhermetic.safeMethods=" + String.join(",", safeMethods));
        }
        // "==" sets the policy instead of adding additional permissions.
        args.add("-Djava.security.policy==" + outputFile.getAbsolutePath().replace('\\', '/'));
        if (debug) {
            args.add("-Djava.security.debug=access,failure");
        }
        String value = String.join(" ", args);
        props.setProperty(property, value);
        log.info(String.format("%s set to %s", property, value));
    }
}
