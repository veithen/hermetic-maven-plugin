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
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import jakarta.xml.bind.JAXBContext;

import aQute.lib.io.IO;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.util.JavaEnvUtils;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;

public class PermissionTest {
    @Test
    public void testJaxb() throws Exception {
        JAXBContext.newInstance(String.class);
    }

    @Test
    public void testCreateTempFile() throws Exception {
        File tempFile = File.createTempFile("test", null);
        assertThat(tempFile.delete()).isTrue();
    }

    private static void checkReadPermissions(Path dir) {
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(dir)) {
            for (Path path : directoryStream) {
                while (true) {
                    if (Files.isDirectory(path)) {
                        checkReadPermissions(path);
                    } else {
                        try {
                            new RandomAccessFile(path.toFile(), "r").close();
                        } catch (IOException ex) {
                            // Ignore IOExceptions (e.g. caused by broken links). We are only
                            // interested in security exceptions.
                        }
                    }
                    if (Files.isSymbolicLink(path)) {
                        path = dir.resolve(Files.readSymbolicLink(path)).normalize();
                    } else {
                        break;
                    }
                }
            }
        } catch (IOException ex) {
            // Ignore (See above).
        }
    }

    @Test
    public void testReadJdkFiles() throws Exception {
        File javaHome = new File(System.getProperty("java.home"));
        if (javaHome.getName().equals("jre")) {
            javaHome = javaHome.getParentFile();
        }
        checkReadPermissions(javaHome.toPath());
    }

    @Test
    public void testExternalURLAccess() throws Exception {
        assertThrows(
                SecurityException.class,
                () -> new URL("http://www.google.com").openStream().close());
    }

    @Test
    public void testInvalidHostname() throws Exception {
        assertThrows(
                UnknownHostException.class,
                () -> new URL("http://rfc2606.invalid").openStream().close());
    }

    @Test
    public void testLocalURLAccess() throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.setConnectors(new Connector[] {connector});
        server.start();
        try {
            HttpURLConnection conn =
                    (HttpURLConnection)
                            new URL("http", "localhost", connector.getLocalPort(), "/")
                                    .openConnection();
            conn.connect();
            assertThat(conn.getResponseCode()).isEqualTo(404);
            conn.disconnect();

            for (NetworkInterface iface :
                    Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
                    InetAddress addr = ifaceAddr.getAddress();
                    if (addr.isLoopbackAddress() || !addr.isLinkLocalAddress()) {
                        conn =
                                (HttpURLConnection)
                                        new URL(
                                                        "http",
                                                        addr.getHostAddress(),
                                                        connector.getLocalPort(),
                                                        "/")
                                                .openConnection();
                        conn.connect();
                        assertThat(conn.getResponseCode()).isEqualTo(404);
                        conn.disconnect();
                    }
                }
            }
        } finally {
            server.stop();
        }
    }

    @Test
    public void testUserHomeDirectoryCheck() throws Exception {
        new File(System.getProperty("user.home")).isDirectory();
    }

    @Test
    public void testGetJdkExecutable() {
        assertThat(JavaEnvUtils.getJdkExecutable("javac")).isNotNull();
    }

    @Test
    public void testAddJavaRuntime() {
        org.apache.tools.ant.types.Path path = new org.apache.tools.ant.types.Path(new Project());
        path.addJavaRuntime();
    }

    @Test
    public void testGetJavaExecutablePath() throws Exception {
        IO.getJavaExecutablePath("java");
    }
}
