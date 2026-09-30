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

import java.lang.reflect.Field;
import java.util.Arrays;

import org.apache.maven.api.Project;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BundleVersionTest {

    @Test
    void versionComesFromDependency() throws Exception {
        Model model = Model.newBuilder()
                .dependencies(Arrays.asList(dependency("org.example", "bundle", "1.2")))
                .build();
        assertEquals(
                Arrays.asList("org.example:bundle:1.2"),
                mojo(model, "org.example:bundle").versionedBundles());
    }

    @Test
    void versionComesFromDependencyManagement() throws Exception {
        Model model = Model.newBuilder()
                .dependencyManagement(DependencyManagement.newBuilder()
                        .dependencies(Arrays.asList(dependency("org.example", "bundle", "2.0")))
                        .build())
                .build();
        assertEquals(
                Arrays.asList("org.example:bundle:2.0"),
                mojo(model, "org.example:bundle").versionedBundles());
    }

    @Test
    void versionedDescriptorsAreKept() throws Exception {
        assertEquals(
                Arrays.asList("org.example:bundle:1.0:jar"),
                mojo(Model.newBuilder().build(), "org.example:bundle:1.0:jar").versionedBundles());
    }

    @Test
    void missingVersionFails() throws Exception {
        AbstractProcessRemoteResourcesMojo mojo = mojo(Model.newBuilder().build(), "org.example:bundle");
        MojoException e = assertThrows(MojoException.class, mojo::versionedBundles);
        assertTrue(e.getMessage().contains("has no version"), e.getMessage());
    }

    private static Dependency dependency(String groupId, String artifactId, String version) {
        return Dependency.newBuilder()
                .groupId(groupId)
                .artifactId(artifactId)
                .version(version)
                .build();
    }

    private static AbstractProcessRemoteResourcesMojo mojo(Model model, String... bundles) throws Exception {
        AbstractProcessRemoteResourcesMojo mojo = new ProcessRemoteResourcesMojo();
        Project project = mock(Project.class);
        when(project.getModel()).thenReturn(model);
        mojo.project = project;
        set(mojo, "resourceBundles", Arrays.asList(bundles));
        return mojo;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = AbstractProcessRemoteResourcesMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
