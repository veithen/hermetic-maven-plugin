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
import static com.google.common.truth.Truth.assertThat;

import java.io.File;

import javax.xml.bind.JAXBContext;

import org.junit.Test;

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
}
