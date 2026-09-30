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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.model.DeploymentRepository;
import org.apache.maven.api.model.DistributionManagement;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Repository;
import org.apache.maven.api.model.Scm;
import org.apache.maven.api.model.Site;

/**
 * Assembles a supplemental model (the child) with the model of an artifact (the parent): values of the child win, the
 * parent fills the gaps. The Maven 4 model is immutable, so the assembled model is returned.
 * <p>
 * Plugins, plugin management, extensions and reporting are not merged: the assembled model is only used to render the
 * descriptive metadata of a dependency in the Velocity templates.
 */
public class ModelInheritanceAssembler {

    /**
     * @param child the dominant model
     * @param parent the recessive model
     * @return the child, completed with the values of the parent
     */
    public Model assembleModelInheritance(Model child, Model parent) {
        return assembleModelInheritance(child, parent, null);
    }

    /**
     * Completes a model with the values of another model that describes the same artifact, so the URLs of the source
     * are taken as they are and the artifactId of the destination is not appended.
     *
     * @param dest the dominant model
     * @param source the recessive model
     * @return the destination, completed with the values of the source
     */
    public Model copyModel(Model dest, Model source) {
        return assembleModelInheritance(dest, source, null, false);
    }

    /**
     * @param child the dominant model
     * @param parent the recessive model
     * @param childPathAdjustment the path to append to the URLs of the parent, may be {@code null}
     * @return the child, completed with the values of the parent
     */
    public Model assembleModelInheritance(Model child, Model parent, String childPathAdjustment) {
        return assembleModelInheritance(child, parent, childPathAdjustment, true);
    }

    private Model assembleModelInheritance(Model child, Model parent, String childPathAdjustment, boolean appendPaths) {
        // cannot inherit from null parent.
        if (parent == null) {
            return child;
        }

        Model.Builder builder = Model.newBuilder(child, true);

        if (child.getGroupId() == null) {
            builder.groupId(parent.getGroupId());
        }

        // The parent version may have resolved to something different, so we take what we asked for
        if (child.getVersion() == null && child.getParent() != null) {
            builder.version(child.getParent().getVersion());
        }

        if (child.getInceptionYear() == null) {
            builder.inceptionYear(parent.getInceptionYear());
        }

        if (child.getUrl() == null) {
            builder.url(
                    parent.getUrl() != null
                            ? appendPath(parent.getUrl(), child.getArtifactId(), childPathAdjustment, appendPaths)
                            : null);
        }

        builder.distributionManagement(assembleDistribution(child, parent, childPathAdjustment, appendPaths));

        if (child.getIssueManagement() == null) {
            builder.issueManagement(parent.getIssueManagement());
        }

        if (child.getDescription() == null) {
            builder.description(parent.getDescription());
        }

        if (child.getOrganization() == null) {
            builder.organization(parent.getOrganization());
        }

        builder.scm(assembleScm(child, parent, childPathAdjustment, appendPaths));

        if (child.getCiManagement() == null) {
            builder.ciManagement(parent.getCiManagement());
        }

        if (child.getDevelopers().isEmpty()) {
            builder.developers(parent.getDevelopers());
        }

        if (child.getLicenses().isEmpty()) {
            builder.licenses(parent.getLicenses());
        }

        if (child.getContributors().isEmpty()) {
            builder.contributors(parent.getContributors());
        }

        if (child.getMailingLists().isEmpty()) {
            builder.mailingLists(parent.getMailingLists());
        }

        builder.build(assembleBuild(child.getBuild(), parent.getBuild()));

        // dependencies: the ones of the child replace the ones of the parent with the same management key
        Map<String, org.apache.maven.api.model.Dependency> dependencies = new LinkedHashMap<>();
        for (org.apache.maven.api.model.Dependency dependency : parent.getDependencies()) {
            dependencies.put(dependency.getManagementKey(), dependency);
        }
        for (org.apache.maven.api.model.Dependency dependency : child.getDependencies()) {
            dependencies.put(dependency.getManagementKey(), dependency);
        }
        builder.dependencies(new ArrayList<>(dependencies.values()));

        builder.repositories(mergeRepositories(child.getRepositories(), parent.getRepositories()));

        builder.dependencyManagement(assembleDependencyManagement(child, parent));

        Map<String, String> properties = new LinkedHashMap<>(parent.getProperties());
        properties.putAll(child.getProperties());
        builder.properties(properties);

        return builder.build();
    }

    private static Build assembleBuild(Build child, Build parent) {
        if (parent == null) {
            return child;
        }
        Build.Builder builder = child == null ? Build.newBuilder(parent, true) : Build.newBuilder(child, true);
        if (child != null) {
            if (child.getSourceDirectory() == null) {
                builder.sourceDirectory(parent.getSourceDirectory());
            }
            if (child.getScriptSourceDirectory() == null) {
                builder.scriptSourceDirectory(parent.getScriptSourceDirectory());
            }
            if (child.getTestSourceDirectory() == null) {
                builder.testSourceDirectory(parent.getTestSourceDirectory());
            }
            if (child.getOutputDirectory() == null) {
                builder.outputDirectory(parent.getOutputDirectory());
            }
            if (child.getTestOutputDirectory() == null) {
                builder.testOutputDirectory(parent.getTestOutputDirectory());
            }
            if (child.getDirectory() == null) {
                builder.directory(parent.getDirectory());
            }
            if (child.getDefaultGoal() == null) {
                builder.defaultGoal(parent.getDefaultGoal());
            }
            if (child.getFinalName() == null) {
                builder.finalName(parent.getFinalName());
            }
            List<String> filters = new ArrayList<>(child.getFilters());
            for (String filter : parent.getFilters()) {
                if (!filters.contains(filter)) {
                    filters.add(filter);
                }
            }
            builder.filters(filters);
            if (child.getResources().isEmpty()) {
                builder.resources(parent.getResources());
            }
            if (child.getTestResources().isEmpty()) {
                builder.testResources(parent.getTestResources());
            }
        }
        return builder.build();
    }

    private static DependencyManagement assembleDependencyManagement(Model child, Model parent) {
        DependencyManagement parentManagement = parent.getDependencyManagement();
        DependencyManagement childManagement = child.getDependencyManagement();
        if (parentManagement == null) {
            return childManagement;
        }
        if (childManagement == null) {
            return parentManagement;
        }
        Map<String, org.apache.maven.api.model.Dependency> dependencies = new LinkedHashMap<>();
        for (org.apache.maven.api.model.Dependency dependency : childManagement.getDependencies()) {
            dependencies.put(dependency.getManagementKey(), dependency);
        }
        for (org.apache.maven.api.model.Dependency dependency : parentManagement.getDependencies()) {
            dependencies.putIfAbsent(dependency.getManagementKey(), dependency);
        }
        return DependencyManagement.newBuilder(childManagement, true)
                .dependencies(new ArrayList<>(dependencies.values()))
                .build();
    }

    private static List<Repository> mergeRepositories(List<Repository> dominant, List<Repository> recessive) {
        Map<String, Repository> repositories = new LinkedHashMap<>();
        for (Repository repository : recessive) {
            repositories.put(repository.getId(), repository);
        }
        for (Repository repository : dominant) {
            repositories.put(repository.getId(), repository);
        }
        return new ArrayList<>(repositories.values());
    }

    private DistributionManagement assembleDistribution(
            Model child, Model parent, String childPathAdjustment, boolean appendPaths) {
        DistributionManagement parentManagement = parent.getDistributionManagement();
        DistributionManagement childManagement = child.getDistributionManagement();
        if (parentManagement == null) {
            return childManagement;
        }

        DistributionManagement.Builder builder = childManagement == null
                ? DistributionManagement.newBuilder(true)
                : DistributionManagement.newBuilder(childManagement, true);
        DistributionManagement current =
                childManagement == null ? DistributionManagement.newBuilder().build() : childManagement;

        if (current.getSite() == null && parentManagement.getSite() != null) {
            Site site = parentManagement.getSite();
            Site.Builder siteBuilder =
                    Site.newBuilder(true).id(site.getId()).name(site.getName()).url(site.getUrl());
            if (site.getUrl() != null) {
                siteBuilder.url(appendPath(site.getUrl(), child.getArtifactId(), childPathAdjustment, appendPaths));
            }
            builder.site(siteBuilder.build());
        }

        if (current.getRepository() == null && parentManagement.getRepository() != null) {
            builder.repository(copyDistributionRepository(parentManagement.getRepository()));
        }

        if (current.getSnapshotRepository() == null && parentManagement.getSnapshotRepository() != null) {
            builder.snapshotRepository(copyDistributionRepository(parentManagement.getSnapshotRepository()));
        }

        if (current.getDownloadUrl() == null || current.getDownloadUrl().isEmpty()) {
            builder.downloadUrl(parentManagement.getDownloadUrl());
        }

        // NOTE: We SHOULD NOT be inheriting status, since this is an assessment of the POM quality.
        // NOTE: We SHOULD NOT be inheriting relocation, since this relates to a single POM
        return builder.build();
    }

    private static DeploymentRepository copyDistributionRepository(DeploymentRepository parentRepository) {
        return DeploymentRepository.newBuilder(true)
                .id(parentRepository.getId())
                .name(parentRepository.getName())
                .url(parentRepository.getUrl())
                .layout(parentRepository.getLayout())
                .uniqueVersion(parentRepository.isUniqueVersion())
                .build();
    }

    private Scm assembleScm(Model child, Model parent, String childPathAdjustment, boolean appendPaths) {
        Scm parentScm = parent.getScm();
        Scm childScm = child.getScm();
        if (parentScm == null) {
            return childScm;
        }

        Scm.Builder builder = childScm == null ? Scm.newBuilder(true) : Scm.newBuilder(childScm, true);
        Scm current = childScm == null ? Scm.newBuilder().build() : childScm;

        if (isEmpty(current.getConnection()) && !isEmpty(parentScm.getConnection())) {
            builder.connection(
                    appendPath(parentScm.getConnection(), child.getArtifactId(), childPathAdjustment, appendPaths));
        }

        if (isEmpty(current.getDeveloperConnection()) && !isEmpty(parentScm.getDeveloperConnection())) {
            builder.developerConnection(appendPath(
                    parentScm.getDeveloperConnection(), child.getArtifactId(), childPathAdjustment, appendPaths));
        }

        if (isEmpty(current.getUrl()) && !isEmpty(parentScm.getUrl())) {
            builder.url(appendPath(parentScm.getUrl(), child.getArtifactId(), childPathAdjustment, appendPaths));
        }
        return builder.build();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    // TODO This should eventually be migrated to DefaultPathTranslator.
    protected String appendPath(String parentPath, String childPath, String pathAdjustment, boolean appendPaths) {
        String uncleanPath = parentPath;

        if (appendPaths) {
            if (pathAdjustment != null) {
                uncleanPath += "/" + pathAdjustment;
            }

            if (childPath != null) {
                uncleanPath += "/" + childPath;
            }
        }

        String cleanedPath = "";

        int protocolIdx = uncleanPath.indexOf("://");

        if (protocolIdx > -1) {
            cleanedPath = uncleanPath.substring(0, protocolIdx + 3);
            uncleanPath = uncleanPath.substring(protocolIdx + 3);
        }

        return cleanedPath + resolvePath(uncleanPath);
    }

    /**
     * Normalizes the path part of an SCM URL. A {@code ".."} segment that cannot be resolved is kept as is.
     */
    private static String resolvePath(String uncleanPath) {
        boolean trailingSeparator = uncleanPath.endsWith("/");
        boolean absolute = uncleanPath.startsWith("/");

        LinkedList<String> pathElements = new LinkedList<>();

        for (String token : uncleanPath.split("/", -1)) {
            switch (token) {
                case "":
                case ".":
                    // Redundant separators and current-directory (".") segments are removed.
                    break;
                case "..":
                    if (!pathElements.isEmpty() && !"..".equals(pathElements.getLast())) {
                        pathElements.removeLast();
                    } else {
                        // Kept rather than rejected: the URL comes from a dependency's POM, and an odd value
                        // there must not fail the build.
                        pathElements.addLast(token);
                    }
                    break;
                default:
                    pathElements.addLast(token);
                    break;
            }
        }

        StringBuilder cleanedPath = new StringBuilder();
        if (absolute) {
            cleanedPath.append('/');
        }

        while (!pathElements.isEmpty()) {
            cleanedPath.append(pathElements.removeFirst());
            if (!pathElements.isEmpty()) {
                cleanedPath.append('/');
            }
        }

        if (trailingSeparator && cleanedPath.length() > 0 && cleanedPath.charAt(cleanedPath.length() - 1) != '/') {
            cleanedPath.append('/');
        }

        return cleanedPath.toString();
    }
}
