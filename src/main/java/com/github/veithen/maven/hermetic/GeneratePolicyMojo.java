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

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketPermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

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
import org.codehaus.plexus.util.IOUtil;

@Mojo(
        name = "generate-policy",
        defaultPhase = LifecyclePhase.GENERATE_TEST_RESOURCES,
        threadSafe = true)
public final class GeneratePolicyMojo extends AbstractMojo {
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

    @Parameter private String[] safeMethods;

    @Deprecated
    @Parameter(defaultValue = "false", required = true)
    private boolean allowExec;

    @Parameter(defaultValue = "argLine", required = true)
    private String property;

    @Parameter(defaultValue = "true", required = true)
    private boolean append;

    @Parameter(defaultValue = "false", required = true)
    private boolean generatePolicyOnly;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Log log = getLog();

        if (skip || project.getPackaging().equals("pom")) {
            return;
        }

        outputFile.getParentFile().mkdirs();
        try (Writer out = new OutputStreamWriter(new FileOutputStream(outputFile), "utf-8")) {
            PolicyWriter writer = new PolicyWriter(out, log);
            writer.start();

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
            writer.writePermission(
                    new FilePermission(
                            "<<ALL FILES>>",
                            allowExec ? "read,readlink,execute" : "read,readlink"));
            for (String dir :
                    new String[] {
                        project.getBuild().getDirectory(), System.getProperty("java.io.tmpdir")
                    }) {
                writer.writePermission(new FilePermission(dir, "read,readlink,write"));
                writer.writePermission(
                        new FilePermission(
                                new File(dir, "-").toString(), "read,readlink,write,delete"));
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
            try (InputStream in =
                    GeneratePolicyMojo.class.getResourceAsStream(
                            "hermetic-security-manager.version")) {
                securityManagerArtifact.setVersion(IOUtil.toString(in, "utf-8"));
            } catch (IOException ex) {
                throw new MojoFailureException("Failed to read version information", ex);
            }
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
            if (safeMethods != null) {
                args.add("-Dhermetic.safeMethods=" + String.join(",", Arrays.asList(safeMethods)));
            }
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
