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

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.CiManagement;
import org.apache.maven.api.model.Contributor;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.Developer;
import org.apache.maven.api.model.DistributionManagement;
import org.apache.maven.api.model.IssueManagement;
import org.apache.maven.api.model.License;
import org.apache.maven.api.model.MailingList;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Organization;
import org.apache.maven.api.model.Scm;

/**
 * The object exposed to the Velocity templates as {@code project} and as the elements of {@code projects}. It replaces
 * the {@code org.apache.maven.project.MavenProject} of earlier versions and exposes the same descriptive getters,
 * backed by the immutable Maven 4 {@link Model}. Everything else of the model is available through {@link #getModel()}.
 */
public final class RemoteResourcesProject {

    private final Model model;

    private final Artifact artifact;

    private final Path basedir;

    /**
     * @param model the effective model of the project
     * @param artifact the artifact of the project, {@code null} for the current project
     * @param basedir the base directory of the project, {@code null} for dependencies
     */
    public RemoteResourcesProject(Model model, Artifact artifact, Path basedir) {
        this.model = model;
        this.artifact = artifact;
        this.basedir = basedir;
    }

    public Model getModel() {
        return model;
    }

    /**
     * @return the artifact of the project, rendered as {@code groupId:artifactId:type[:classifier]:version}
     */
    public Object getArtifact() {
        return artifact == null ? null : new ArtifactCoordinates(artifact, model.getPackaging());
    }

    public File getBasedir() {
        return basedir == null ? null : basedir.toFile();
    }

    public String getGroupId() {
        return model.getGroupId();
    }

    public String getArtifactId() {
        return model.getArtifactId();
    }

    public String getVersion() {
        return model.getVersion();
    }

    public String getPackaging() {
        return model.getPackaging();
    }

    public String getName() {
        return model.getName();
    }

    public String getDescription() {
        return model.getDescription();
    }

    public String getUrl() {
        return model.getUrl();
    }

    public String getInceptionYear() {
        return model.getInceptionYear();
    }

    public Organization getOrganization() {
        return model.getOrganization();
    }

    public List<License> getLicenses() {
        return model.getLicenses();
    }

    public List<Developer> getDevelopers() {
        return model.getDevelopers();
    }

    public List<Contributor> getContributors() {
        return model.getContributors();
    }

    public List<MailingList> getMailingLists() {
        return model.getMailingLists();
    }

    public Scm getScm() {
        return model.getScm();
    }

    public IssueManagement getIssueManagement() {
        return model.getIssueManagement();
    }

    public CiManagement getCiManagement() {
        return model.getCiManagement();
    }

    public DistributionManagement getDistributionManagement() {
        return model.getDistributionManagement();
    }

    public Build getBuild() {
        return model.getBuild();
    }

    public List<Dependency> getDependencies() {
        return model.getDependencies();
    }

    public Map<String, String> getProperties() {
        return model.getProperties();
    }

    public String getId() {
        return model.getGroupId() + ":" + model.getArtifactId() + ":" + model.getPackaging() + ":" + model.getVersion();
    }

    @Override
    public String toString() {
        return getId();
    }

    /**
     * Keeps the textual form of the former Maven 3 artifact, which the bundled {@code DEPENDENCIES} templates print.
     */
    public static final class ArtifactCoordinates {
        private final Artifact artifact;

        private final String type;

        ArtifactCoordinates(Artifact artifact, String type) {
            this.artifact = artifact;
            this.type = type;
        }

        public String getGroupId() {
            return artifact.getGroupId();
        }

        public String getArtifactId() {
            return artifact.getArtifactId();
        }

        public String getVersion() {
            return artifact.getVersion().toString();
        }

        public String getClassifier() {
            return artifact.getClassifier();
        }

        public String getType() {
            return type;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getGroupId())
                    .append(':')
                    .append(getArtifactId())
                    .append(':')
                    .append(type);
            String classifier = getClassifier();
            if (classifier != null && !classifier.isEmpty()) {
                sb.append(':').append(classifier);
            }
            return sb.append(':').append(getVersion()).toString();
        }
    }
}
