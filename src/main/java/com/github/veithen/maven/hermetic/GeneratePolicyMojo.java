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
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.ManagementPermission;
import java.lang.reflect.ReflectPermission;
import java.net.NetPermission;
import java.net.SocketPermission;
import java.security.SecurityPermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.PropertyPermission;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.management.MBeanPermission;
import javax.management.MBeanServerPermission;
import javax.management.MBeanTrustPermission;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

@Mojo(name="generate-policy", defaultPhase=LifecyclePhase.GENERATE_TEST_RESOURCES, threadSafe=true)
public final class GeneratePolicyMojo extends AbstractMojo {
    @Parameter(property="project", readonly=true, required=true)
    private MavenProject project;

    @Parameter(property="session", readonly=true, required=true)
    private MavenSession session;

    @Parameter(defaultValue="${project.build.directory}/test.policy", required=true)
    private File outputFile;

    @Parameter
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip || project.getPackaging().equals("pom")) {
            return;
        }
        outputFile.getParentFile().mkdirs();
        try (Writer out = new OutputStreamWriter(new FileOutputStream(outputFile), "utf-8")) {
            PolicyWriter writer = new PolicyWriter(out);
            writer.start();
            List<File> dirs = new ArrayList<>();
            File javaHome = new File(System.getProperty("java.home"));
            if (javaHome.getName().equals("jre")) {
                javaHome = javaHome.getParentFile();
            }
            dirs.add(javaHome);
            Stream.of(System.getProperty("java.ext.dirs").split(Pattern.quote(File.pathSeparator))).map(File::new).forEach(dirs::add);
            dirs.add(new File(System.getProperty("maven.home")));
            dirs.add(new File(session.getSettings().getLocalRepository()));
            dirs.add(project.getBasedir());
            for (File dir : dirs) {
                writer.writePermission(new FilePermission(dir.getAbsolutePath(), "read"));
                writer.writePermission(new FilePermission(new File(dir, "-").getAbsolutePath(), "read"));
            }
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
                writer.writePermission(new FilePermission(dir, "read,write"));
                writer.writePermission(new FilePermission(new File(dir, "-").getAbsolutePath(), "read,write,delete"));
            }
            writer.writePermission(new RuntimePermission("*"));
            writer.writePermission(new ManagementPermission("monitor"));
            writer.writePermission(new ReflectPermission("*"));
            writer.writePermission(new NetPermission("*"));
            writer.writePermission(new SocketPermission("localhost", "connect,listen,accept,resolve"));
            writer.writePermission(new SecurityPermission("*"));
            writer.writePermission(new PropertyPermission("*", "read,write"));
            writer.writePermission(new MBeanPermission("*", "*"));
            writer.writePermission(new MBeanServerPermission("*"));
            writer.writePermission(new MBeanTrustPermission("*"));
            writer.writePermission("javax.xml.bind.JAXBPermission", "*", null);
            writer.writePermission("javax.xml.ws.WebServicePermission", "publishEndpoint", null);
            writer.writePermission("org.osgi.framework.AdminPermission", "*", "*");
            writer.writePermission("org.osgi.framework.ServicePermission", "*", "register,get");
            writer.end();
        } catch (IOException ex) {
            throw new MojoFailureException(String.format("Failed to write %s", outputFile), ex);
        }
        Properties props = project.getProperties();
        String argLine = props.getProperty("argLine");
        StringBuilder buffer = new StringBuilder();
        if (argLine != null) {
            buffer.append(argLine);
            buffer.append(" ");
        }
        // "==" sets the policy instead of adding additional permissions.
        buffer.append("-Djava.security.manager -Djava.security.policy==");
        buffer.append(outputFile.getAbsolutePath().replace('\\', '/'));
        props.setProperty("argLine", buffer.toString());
    }
}
