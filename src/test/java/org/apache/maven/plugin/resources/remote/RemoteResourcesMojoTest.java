/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugin.resources.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RemoteResources plugin Test Case. The Maven 4 API is mocked: the mojos are created directly and only see a
 * {@link Session}, a {@link Project} and the services behind them.
 */
class RemoteResourcesMojoTest {

    @TempDir
    Path tempDir;

    private Path localRepo;

    private Session session;

    private Project project;

    private Path basedir;

    private Model model;

    private final Map<String, String> projectProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        localRepo = tempDir.resolve("local-repo");
        basedir = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(basedir.resolve("target/classes"));
        Files.writeString(basedir.resolve("pom.xml"), "<project/>");

        session = mock(Session.class);
        project = mock(Project.class);
        ProjectManager projectManager = mock(ProjectManager.class);
        when(session.getService(ProjectManager.class)).thenReturn(projectManager);
        when(projectManager.getProperties(project)).thenReturn(projectProperties);
        when(projectManager.getEnabledSourceRoots(any(), any(), any())).thenAnswer(i -> Stream.empty());
        when(projectManager.getRemoteProjectRepositories(project)).thenReturn(Collections.emptyList());

        Node root = mock(Node.class);
        when(root.getChildren()).thenReturn(Collections.emptyList());
        when(session.collectDependencies(project, PathScope.TEST_RUNTIME)).thenReturn(root);
        when(session.flattenDependencies(root, PathScope.TEST_RUNTIME)).thenReturn(Collections.emptyList());
        when(session.getProjects()).thenReturn(List.of(project));

        when(session.requireType(any())).thenAnswer(i -> {
            Type t = mock(Type.class);
            String id = i.getArgument(0);
            when(t.getExtension()).thenReturn("test-jar".equals(id) ? "jar" : id);
            when(t.getClassifier()).thenReturn("test-jar".equals(id) ? "tests" : null);
            return t;
        });

        model = Model.newBuilder()
                .groupId("test")
                .artifactId("project")
                .version("1.0")
                .name("Test Project")
                .inceptionYear("2007")
                .build(Build.newBuilder()
                        .directory(basedir.resolve("target").toString())
                        .outputDirectory(basedir.resolve("target/classes").toString())
                        .build())
                .build();
        when(project.getModel()).thenReturn(model);
        when(project.getBuild()).thenReturn(model.getBuild());
        when(project.getBasedir()).thenReturn(basedir);
        when(project.getPomPath()).thenReturn(basedir.resolve("pom.xml"));
        when(project.getGroupId()).thenReturn("test");
        when(project.getArtifactId()).thenReturn("project");
        when(project.getVersion()).thenReturn("1.0");
        when(project.getOutputDirectory(ProjectScope.MAIN)).thenReturn(basedir.resolve("target/classes"));
        when(project.getOutputDirectory(ProjectScope.TEST)).thenReturn(basedir.resolve("target/test-classes"));
    }

    /**
     * check test environment
     */
    @Test
    void testEnvironment() {
        assertNotNull(processMojo());
    }

    @Test
    void noBundles() throws Exception {
        processMojo().execute();
    }

    @Test
    void configureLocatorRequiresProjectFile() {
        when(project.getPomPath()).thenReturn(null);
        ProcessRemoteResourcesMojo mojo = processMojo();

        MojoException e = assertThrows(MojoException.class, mojo::execute);
        assertTrue(e.getMessage().contains("no POM file"));
    }

    @Test
    void createBundle() throws Exception {
        List<String> resources =
                Arrays.asList("FILTER.txt.vm", "ISO-8859-1.bin.vm", "PROPERTIES.txt.vm", "SIMPLE.txt", "UTF-8.bin.vm");
        buildResourceBundle("default-createbundle", null, resources.toArray(new String[0]), null);
    }

    @Test
    void simpleBundles() throws Exception {
        ProcessRemoteResourcesMojo mojo = processMojo("test:test:1.0");
        buildResourceBundle(
                "default-simplebundles-create", null, new String[] {"SIMPLE.txt"}, bundleJar("test", "1.0"));

        mojo.execute();

        assertTrue(Files.exists(outputDirectory(mojo).resolve("SIMPLE.txt")));
    }

    @Test
    void simpleBundlesWithType() throws Exception {
        ProcessRemoteResourcesMojo mojo = processMojo("test:test:1.0:war");
        buildResourceBundle(
                "default-simplebundles-create", null, new String[] {"SIMPLE.txt"}, bundleJar("test", "1.0"));

        mojo.execute();

        assertTrue(Files.exists(outputDirectory(mojo).resolve("SIMPLE.txt")));
    }

    @Test
    void simpleBundlesWithClassifier() throws Exception {
        ProcessRemoteResourcesMojo mojo = processMojo("test:test:1.0:jar:test");
        buildResourceBundle(
                "default-simplebundles-create", null, new String[] {"SIMPLE.txt"}, bundleJar("test", "1.0"));

        mojo.execute();

        assertTrue(Files.exists(outputDirectory(mojo).resolve("SIMPLE.txt")));
    }

    @Test
    void velocityUTF8() throws Exception {
        ProcessRemoteResourcesMojo mojo = processMojo("test:test:1.2");
        buildResourceBundle("default-utf8-create", "UTF-8", new String[] {"UTF-8.bin.vm"}, bundleJar("test", "1.2"));

        mojo.execute();

        byte[] data = Files.readAllBytes(outputDirectory(mojo).resolve("UTF-8.bin"));
        byte[] expected = "äöüÄÖÜß".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, data);
    }

    @Test
    void velocityISO88591() throws Exception {
        ProcessRemoteResourcesMojo mojo = processMojo("test:test:1.3");
        buildResourceBundle(
                "default-iso88591-create", "ISO-8859-1", new String[] {"ISO-8859-1.bin.vm"}, bundleJar("test", "1.3"));

        mojo.execute();

        byte[] data = Files.readAllBytes(outputDirectory(mojo).resolve("ISO-8859-1.bin"));
        byte[] expected = "äöüÄÖÜß".getBytes(StandardCharsets.ISO_8859_1);
        assertArrayEquals(expected, data);
    }

    @Test
    void filteredBundles() throws Exception {
        ProcessRemoteResourcesMojo mojo = processMojo("test:test:1.1");
        buildResourceBundle(
                "default-filterbundles-create", null, new String[] {"FILTER.txt.vm"}, bundleJar("test", "1.1"));

        mojo.execute();
        // executing a second time (example: forked lifecycle) should still work
        mojo.execute();

        String data = Files.readString(outputDirectory(mojo).resolve("FILTER.txt"));
        assertTrue(data.contains("project.name: Test Project"), data);
        assertTrue(data.contains("projectTimespan: 2007-2019"), data);
        assertTrue(data.contains("projects: ["), data);
        assertTrue(data.contains("projectsSortedByOrganization: {"), data);
    }

    @Test
    void filteredBundlesWithProjectProperties() throws Exception {
        ProcessRemoteResourcesMojo mojo = processMojo("test-filtered-bundles:test-filtered-bundles:2");
        set(mojo, "includeProjectProperties", true);
        projectProperties.put("testingPropertyOne", "maven");
        projectProperties.put("testingPropertyTwo", "rules");
        buildResourceBundle(
                "default-filterbundles-two-create",
                null,
                new String[] {"PROPERTIES.txt.vm"},
                bundleJar("test-filtered-bundles", "2"));

        mojo.execute();
        // executing a second time (example: forked lifecycle) should still work
        mojo.execute();

        String data = Files.readString(outputDirectory(mojo).resolve("PROPERTIES.txt"));
        assertTrue(data.contains("maven"), data);
        assertTrue(data.contains("rules"), data);
    }

    private Path outputDirectory(ProcessRemoteResourcesMojo mojo) throws Exception {
        return ((java.io.File) get(mojo, "outputDirectory")).toPath();
    }

    /**
     * The path in the local repository where the bundle with the given coordinates is found.
     */
    private Path bundleJar(String groupId, String version) throws IOException {
        Path jar = localRepo.resolve(groupId).resolve(version).resolve("bundle.jar");
        Files.createDirectories(jar.getParent());
        return jar;
    }

    private ProcessRemoteResourcesMojo processMojo(String... bundles) {
        ProcessRemoteResourcesMojo mojo = new ProcessRemoteResourcesMojo();
        mojo.session = session;
        mojo.project = project;
        mojo.log = mock(Log.class);
        try {
            set(mojo, "fileFilter", mock(MavenFileFilter.class));
            set(
                    mojo,
                    "outputDirectory",
                    basedir.resolve("target/maven-shared-archive-resources").toFile());
            set(
                    mojo,
                    "appendedResourcesDirectory",
                    basedir.resolve("src/main/appended-resources").toFile());
            set(mojo, "resourceBundles", new ArrayList<>(Arrays.asList(bundles)));
            set(mojo, "outputTimestamp", "2019-01-01T00:00:00Z");
            set(mojo, "useDefaultFilterDelimiters", true);
            mojo.encoding = "UTF-8";
            mojo.includeScope = "runtime";
            mojo.excludeScope = "";
            mojo.includeArtifactIds = "";
            mojo.excludeArtifactIds = "";
            mojo.includeGroupIds = "";
            mojo.excludeGroupIds = "";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // resolve every bundle to the jar created by the test for its groupId and version
        when(session.createArtifact(any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            Artifact artifact = mock(Artifact.class);
            when(artifact.getGroupId()).thenReturn(i.getArgument(0));
            when(artifact.getArtifactId()).thenReturn(i.getArgument(1));
            when(artifact.key()).thenReturn(i.getArgument(0) + ":" + i.getArgument(1) + ":" + i.getArgument(2));
            return artifact;
        });
        when(session.resolveArtifact(any(Artifact.class), anyList())).thenAnswer(i -> {
            Artifact artifact = i.getArgument(0);
            String[] key = artifact.key().split(":");
            DownloadedArtifact downloaded = mock(DownloadedArtifact.class);
            when(downloaded.getPath())
                    .thenReturn(localRepo.resolve(key[0]).resolve(key[2]).resolve("bundle.jar"));
            return downloaded;
        });
        return mojo;
    }

    private void buildResourceBundle(String id, String sourceEncoding, String[] resourceNames, Path jarName)
            throws Exception {
        Path resourceDir = Files.createDirectories(tempDir.resolve(id).resolve("src/main/resources"));
        Path outputDir = Files.createDirectories(tempDir.resolve(id).resolve("target/classes"));

        BundleRemoteResourcesMojo mojo = new BundleRemoteResourcesMojo();
        set(mojo, "resourcesDirectory", resourceDir.toFile());
        set(mojo, "outputDirectory", outputDir.toFile());
        set(mojo, "sourceEncoding", sourceEncoding);
        set(mojo, "log", mock(Log.class));

        for (String resourceName : resourceNames) {
            URL source = getClass().getResource("/" + resourceName);
            try (InputStream in = source.openStream()) {
                Files.copy(in, resourceDir.resolve(resourceName));
            }
        }

        mojo.execute();

        Path xmlFile = outputDir.resolve("META-INF/maven/remote-resources.xml");
        assertTrue(Files.exists(xmlFile));

        String data = Files.readString(xmlFile);

        List<String> expectedOrder = new ArrayList<>(Arrays.asList(resourceNames));
        Collections.sort(expectedOrder);
        assertContainsInOrder(data, expectedOrder);

        if (null != jarName) {
            try (OutputStream fos = Files.newOutputStream(jarName);
                    JarOutputStream jar = new JarOutputStream(fos)) {
                jar.putNextEntry(new ZipEntry("META-INF/maven/remote-resources.xml"));
                jar.write(data.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();

                for (String resourceName : resourceNames) {
                    jar.putNextEntry(new ZipEntry(resourceName));
                    Files.copy(resourceDir.resolve(resourceName), jar);
                    jar.closeEntry();
                }
            }
        }
    }

    /**
     * Asserts that {@code actual} contains every string in {@code expected}, in order, allowing
     * unrelated text in between (equivalent to Hamcrest's {@code stringContainsInOrder}).
     */
    private static void assertContainsInOrder(String actual, List<String> expected) {
        int fromIndex = 0;
        for (String s : expected) {
            int idx = actual.indexOf(s, fromIndex);
            assertTrue(idx >= 0, "expected \"" + s + "\" to occur in order in: " + actual);
            fromIndex = idx + s.length();
        }
    }

    private static Object get(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }
}
