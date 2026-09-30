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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.Project;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * <p>
 * Pull down resourceBundles containing remote resources and process the resources contained inside. When that is done,
 * the resources are injected into the current (in-memory) Maven project, making them available to the process-resources
 * phase.
 * </p>
 * <p>
 * Resources that end in ".vm" are treated as Velocity templates. For those, the ".vm" is stripped off for the final
 * artifact name and it's fed through Velocity to have properties expanded, conditions processed, etc...
 * </p>
 * Resources that don't end in ".vm" are copied "as is".
 */
@Mojo(name = "aggregate", defaultPhase = "generate-resources", aggregator = true)
public class AggregateProcessRemoteResourcesMojo extends AbstractProcessRemoteResourcesMojo {

    @Override
    protected Set<Dependency> getAllDependencies() {
        Map<String, Dependency> result = new LinkedHashMap<>();
        for (Project mavenProject : session.getProjects()) {
            getAllDependencies(mavenProject).forEach(result::putIfAbsent);
        }
        return new LinkedHashSet<>(result.values());
    }

    @Override
    protected Set<Dependency> getDirectDependencies() {
        Map<String, Dependency> result = new LinkedHashMap<>();
        for (Project mavenProject : session.getProjects()) {
            getDirectDependencies(mavenProject).forEach(result::putIfAbsent);
        }
        return new LinkedHashSet<>(result.values());
    }
}
