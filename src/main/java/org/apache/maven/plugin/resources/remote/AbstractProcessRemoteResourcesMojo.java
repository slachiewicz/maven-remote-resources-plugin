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
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.Language;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.SourceRoot;
import org.apache.maven.api.Type;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Organization;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.MavenException;
import org.apache.maven.api.services.ModelBuilder;
import org.apache.maven.api.services.ModelBuilderRequest;
import org.apache.maven.api.services.ModelBuilderResult;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.Sources;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.api.services.xml.XmlReaderRequest;
import org.apache.maven.plugin.resources.remote.io.xpp3.RemoteResourcesBundleXpp3Reader;
import org.apache.maven.plugin.resources.remote.io.xpp3.SupplementalDataModelXpp3Reader;
import org.apache.maven.shared.archiver.MavenArchiver;
import org.apache.maven.shared.artifact.filter.collection.ArtifactFilterException;
import org.apache.maven.shared.artifact.filter.collection.ArtifactIdFilter;
import org.apache.maven.shared.artifact.filter.collection.FilterArtifacts;
import org.apache.maven.shared.artifact.filter.collection.GroupIdFilter;
import org.apache.maven.shared.artifact.filter.collection.ProjectTransitivityFilter;
import org.apache.maven.shared.artifact.filter.collection.ScopeFilter;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenFileFilterRequest;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.MethodInvocationException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.exception.VelocityException;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.codehaus.plexus.resource.DefaultResourceManager;
import org.codehaus.plexus.resource.ResourceManager;
import org.codehaus.plexus.resource.loader.FileResourceLoader;
import org.codehaus.plexus.resource.loader.JarResourceLoader;
import org.codehaus.plexus.resource.loader.ResourceLoader;
import org.codehaus.plexus.resource.loader.ThreadContextClasspathResourceLoader;
import org.codehaus.plexus.resource.loader.URLResourceLoader;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.io.CachingOutputStream;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

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
 * <p>
 * This is a support abstract class, with two non-aggregating and aggregating implementations.
 * </p>
 */
public abstract class AbstractProcessRemoteResourcesMojo implements org.apache.maven.api.plugin.Mojo {
    private static final String TEMPLATE_SUFFIX = ".vm";

    /**
     * <p>
     * In cases where a local resource overrides one from a remote resource bundle, that resource should be filtered if
     * the resource set specifies it. In those cases, this parameter defines the list of delimiters for filterable
     * expressions. These delimiters are specified in the form 'beginToken*endToken'. If no '*' is given, the delimiter
     * is assumed to be the same for start and end.
     * </p>
     * <p>
     * So, the default filtering delimiters might be specified as:
     * </p>
     *
     * <pre>
     * &lt;delimiters&gt;
     *   &lt;delimiter&gt;${*}&lt;/delimiter&gt;
     *   &lt;delimiter&gt;@&lt;/delimiter&gt;
     * &lt;/delimiters&gt;
     * </pre>
     * Since the '@' delimiter is the same on both ends, we don't need to specify '@*@' (though we can).
     *
     * @since 1.1
     */
    @Parameter
    protected List<String> filterDelimiters;

    /**
     * @since 1.1
     */
    @Parameter(defaultValue = "true")
    protected boolean useDefaultFilterDelimiters;

    /**
     * The character encoding scheme to be applied when filtering resources.
     */
    @Parameter(property = "encoding", defaultValue = "${project.build.sourceEncoding}")
    protected String encoding;

    /**
     * The directory where processed resources will be placed for packaging.
     */
    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}/maven-shared-archive-resources")
    private File outputDirectory;

    /**
     * The directory containing extra information appended to the generated resources.
     */
    @Parameter(defaultValue = "${project.basedir}/src/main/appended-resources")
    private File appendedResourcesDirectory;

    /**
     * Supplemental model data. Useful when processing
     * artifacts with incomplete POM metadata.
     * <p/>
     * By default, this Mojo looks for supplemental model data in the file
     * "<code>${appendedResourcesDirectory}/supplemental-models.xml</code>".
     *
     * @since 1.0-alpha-5
     */
    @Parameter
    private String[] supplementalModels;

    /**
     * List of artifacts that are added to the search path when looking
     * for supplementalModels, expressed with <code>groupId:artifactId:version[:type[:classifier]]</code> format.
     *
     * @since 1.1
     */
    @Parameter
    private List<String> supplementalModelArtifacts;

    /**
     * The resource bundles that will be retrieved and processed,
     * expressed with <code>groupId:artifactId:version[:type[:classifier]]</code> format.
     */
    @Parameter(property = "resourceBundles", required = true)
    private List<String> resourceBundles;

    /**
     * Skip remote-resource processing
     *
     * @since 1.0-alpha-5
     */
    @Parameter(property = "remoteresources.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Attaches the resources to the main build of the project as a resource directory.
     *
     * @since 1.5
     */
    @Parameter(defaultValue = "true", property = "attachToMain")
    private boolean attachToMain;

    /**
     * Attaches the resources to the test build of the project as a resource directory.
     *
     * @since 1.5
     */
    @Parameter(defaultValue = "true", property = "attachToTest")
    private boolean attachToTest;

    /**
     * Additional properties to be passed to Velocity.
     * Several properties are automatically added:<ul>
     * <li><code>project</code> - the current project, a {@link RemoteResourcesProject}</li>
     * <li><code>projects</code> - the list of dependency projects, each one a {@link RemoteResourcesProject}</li>
     * <li><code>projectsSortedByOrganization</code> - the list of dependency projects sorted by organization</li>
     * <li><code>projectTimespan</code> - the timespan of the current project (requires inceptionYear in pom)</li>
     * <li><code>locator</code> - the ResourceManager that can be used to retrieve additional resources</li>
     * </ul>
     * See {@link RemoteResourcesProject} for information about the properties of a project.
     */
    @Parameter
    protected Map<String, String> properties = new HashMap<>();

    /**
     * Whether to include properties defined in the project when filtering resources.
     *
     * @deprecated as Maven Project is available in Velocity context we can simply use
     * <code>$project.properties.propertyName</code>
     *
     * @since 1.2
     */
    @Deprecated
    @Parameter(defaultValue = "false")
    protected boolean includeProjectProperties = false;

    /**
     * This parameter is not used. It is kept for backward compatibility only.
     *
     * @deprecated not used anymore
     * @since 1.6
     */
    @Deprecated
    @Parameter(defaultValue = "5242880")
    protected int velocityFilterInMemoryThreshold = 5 * 1024 * 1024;

    /**
     * The Maven session.
     */
    @Inject
    protected Session session;

    /**
     * The current project.
     */
    @Inject
    protected Project project;

    @Inject
    protected Log log;

    /**
     * Scope to include. An Empty string indicates all scopes (default is "runtime").
     *
     * @since 1.0
     */
    @Parameter(property = "includeScope", defaultValue = "runtime")
    protected String includeScope;

    /**
     * Scope to exclude. An Empty string indicates no scopes (default).
     *
     * @since 1.0
     */
    @Parameter(property = "excludeScope", defaultValue = "")
    protected String excludeScope;

    /**
     * When resolving project dependencies, specify the scopes to include.
     * The default is the same as "includeScope" if there are no exclude scopes set.
     * Otherwise, it defaults to "test" to grab all the dependencies so the
     * exclude filters can filter out what is not needed.
     *
     * @since 1.5
     */
    @Parameter
    protected String[] resolveScopes;

    /**
     * Comma separated list of Artifact names to exclude.
     *
     * @since 1.0
     */
    @Parameter(property = "excludeArtifactIds", defaultValue = "")
    protected String excludeArtifactIds;

    /**
     * Comma separated list of Artifact names to include.
     *
     * @since 1.0
     */
    @Parameter(property = "includeArtifactIds", defaultValue = "")
    protected String includeArtifactIds;

    /**
     * Comma separated list of GroupId Names to exclude.
     *
     * @since 1.0
     */
    @Parameter(property = "excludeGroupIds", defaultValue = "")
    protected String excludeGroupIds;

    /**
     * Comma separated list of GroupIds to include.
     *
     * @since 1.0
     */
    @Parameter(property = "includeGroupIds", defaultValue = "")
    protected String includeGroupIds;

    /**
     * If we should exclude transitive dependencies
     *
     * @since 1.0
     */
    @Parameter(property = "excludeTransitive", defaultValue = "false")
    protected boolean excludeTransitive;

    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    /**
     * Indicate if project workspace files with the same name should be used instead of the ones from the bundle.
     *
     * @since 3.3.0
     */
    @Parameter(defaultValue = "false")
    private boolean useProjectFiles;

    /**
     * Map of artifacts to supplemental project object models.
     */
    private Map<String, Model> supplementModels;

    /**
     * Merges supplemental data model with artifact metadata. Useful when processing artifacts with
     * incomplete POM metadata.
     */
    private final ModelInheritanceAssembler inheritanceAssembler = new ModelInheritanceAssembler();

    private VelocityEngine velocity;

    /**
     * Filtering support, for local resources that override those in the remote bundle.
     */
    @Inject
    private MavenFileFilter fileFilter;

    private final ResourceManager locator;

    protected AbstractProcessRemoteResourcesMojo() {
        Map<String, ResourceLoader> loaders = new LinkedHashMap<>();
        loaders.put(FileResourceLoader.ID, new FileResourceLoader());
        loaders.put("jar", new JarResourceLoader());
        loaders.put("url", new URLResourceLoader());
        loaders.put("classloader", new ThreadContextClasspathResourceLoader());
        this.locator = new DefaultResourceManager(loaders);
    }

    @Override
    public void execute() throws MojoException {
        if (skip) {
            log.info("Skipping remote resources execution.");
            return;
        }

        if (encoding == null || encoding.isEmpty()) {
            log.warn("File encoding has not been set, using platform encoding " + Charset.defaultCharset()
                    + ", i.e. build is platform dependent!");
            encoding = Charset.defaultCharset().name();
        }

        if (resolveScopes == null) {
            resolveScopes = new String[] {
                (this.includeScope == null || this.includeScope.isEmpty()) ? "test" : this.includeScope
            };
        }

        if (supplementalModels == null) {
            File sups = new File(appendedResourcesDirectory, "supplemental-models.xml");
            if (sups.exists()) {
                try {
                    supplementalModels = new String[] {sups.toURI().toURL().toString()};
                } catch (MalformedURLException e) {
                    // ignore
                    log.debug("URL issue with supplemental-models.xml: " + e);
                }
            }
        }

        ClassLoader origLoader = Thread.currentThread().getContextClassLoader();
        try {
            configureLocator();
            validate();

            List<File> resourceBundleArtifacts = downloadBundles(resourceBundles);
            supplementModels = loadSupplements(supplementalModels);

            ClassLoader classLoader = initalizeClassloader(resourceBundleArtifacts);

            Thread.currentThread().setContextClassLoader(classLoader);

            velocity = new VelocityEngine();
            velocity.setProperty("resource.loaders", "classpath");
            velocity.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
            velocity.init();

            VelocityContext context = buildVelocityContext();

            processResourceBundles(classLoader, context);

            if (outputDirectory.exists()) {
                // ----------------------------------------------------------------------------
                // Push our newly generated resources directory into the project so that
                // these resources can be picked up by the process-resources phase.
                // ----------------------------------------------------------------------------
                // MRRESOURCES-61 handle main and test resources separately
                ProjectManager projectManager = session.getService(ProjectManager.class);
                if (attachToMain) {
                    projectManager.addSourceRoot(
                            project, ProjectScope.MAIN, Language.RESOURCES, outputDirectory.toPath());
                }
                if (attachToTest) {
                    projectManager.addSourceRoot(
                            project, ProjectScope.TEST, Language.RESOURCES, outputDirectory.toPath());
                }

                // ----------------------------------------------------------------------------
                // Write out archiver dot file
                // ----------------------------------------------------------------------------
                try {
                    Path dotFile = Path.of(project.getBuild().getDirectory(), ".plxarc");
                    Files.createDirectories(dotFile.getParent());
                    Files.writeString(dotFile, outputDirectory.getName());
                } catch (IOException e) {
                    throw new MojoException("Error creating dot file for archiving instructions.", e);
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(origLoader);
        }
    }

    private void configureLocator() throws MojoException {
        if (supplementalModelArtifacts != null && !supplementalModelArtifacts.isEmpty()) {
            List<File> artifacts = downloadBundles(supplementalModelArtifacts);

            for (File artifact : artifacts) {
                if (artifact.isDirectory()) {
                    locator.addSearchPath(FileResourceLoader.ID, artifact.getAbsolutePath());
                } else {
                    try {
                        locator.addSearchPath(
                                JarResourceLoader.ID,
                                "jar:" + artifact.toURI().toURL().toExternalForm());
                    } catch (MalformedURLException e) {
                        throw new MojoException("Could not use jar " + artifact.getAbsolutePath(), e);
                    }
                }
            }
        }

        Path projectFile = project.getPomPath();
        if (projectFile == null) {
            throw new MojoException("The current project has no POM file; cannot configure the resource locator.");
        }
        locator.addSearchPath(
                FileResourceLoader.ID, projectFile.getParent().toAbsolutePath().toString());
        if (appendedResourcesDirectory != null) {
            locator.addSearchPath(FileResourceLoader.ID, appendedResourcesDirectory.getAbsolutePath());
        }
        locator.addSearchPath(URLResourceLoader.ID, "");
        locator.setOutputDirectory(new File(project.getBuild().getDirectory()));
    }

    protected List<RemoteResourcesProject> getProjects() {
        List<RemoteResourcesProject> projects = new ArrayList<>();
        ProjectManager projectManager = session.getService(ProjectManager.class);
        List<RemoteRepository> repositories = projectManager.getRemoteProjectRepositories(project);

        // add filters in well known order, least specific to most specific
        FilterArtifacts filter = new FilterArtifacts();

        Set<Dependency> artifacts = new LinkedHashSet<>(getAllDependencies());
        if (this.excludeTransitive) {
            filter.addFilter(new ProjectTransitivityFilter(getDirectDependencies(), true));
        }

        filter.addFilter(new ScopeFilter(this.includeScope, this.excludeScope));
        filter.addFilter(new GroupIdFilter(this.includeGroupIds, this.excludeGroupIds));
        filter.addFilter(new ArtifactIdFilter(this.includeArtifactIds, this.excludeArtifactIds));

        // perform filtering
        try {
            artifacts = filter.filter(artifacts);
        } catch (ArtifactFilterException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }

        log.debug("PROJECTS: " + artifacts);

        for (Dependency dependency : artifacts) {
            // the POM of a snapshot is located by its base version
            Artifact artifact = session.createArtifact(
                    dependency.getGroupId(),
                    dependency.getArtifactId(),
                    dependency.getBaseVersion().toString(),
                    null,
                    "pom",
                    "pom");

            log.debug("Building project for " + artifact);
            Model model;
            try {
                Path pom = session.resolveArtifact(artifact, repositories).getPath();
                // ProjectBuilder only returns projects that have a base directory, which a POM located in a
                // repository does not have: the effective model is requested from the ModelBuilder instead
                ModelBuilderResult result = session.getService(ModelBuilder.class)
                        .newSession()
                        .build(ModelBuilderRequest.builder()
                                .session(session)
                                .requestType(ModelBuilderRequest.RequestType.CONSUMER_DEPENDENCY)
                                .source(Sources.resolvedSource(pom, artifact.key()))
                                .repositories(repositories)
                                .build());
                model = result.getEffectiveModel();
            } catch (MavenException e) {
                log.debug("Cannot build the project of " + artifact, e);
                model = null;
            }
            if (model == null) {
                log.warn("Invalid project model for artifact [" + dependency.getGroupId() + ":"
                        + dependency.getArtifactId() + ":" + dependency.getBaseVersion() + "]. "
                        + "It will be ignored by the remote resources Mojo.");
                continue;
            }

            String supplementKey = generateSupplementMapKey(model.getGroupId(), model.getArtifactId());

            if (supplementModels.containsKey(supplementKey)) {
                Model mergedModel = mergeModels(model, supplementModels.get(supplementKey));
                mergedModel = Model.newBuilder(mergedModel, true)
                        .version(dependency.getBaseVersion().toString())
                        .build();
                projects.add(new RemoteResourcesProject(mergedModel, dependency, null));
                log.debug("Adding project with groupId [" + mergedModel.getGroupId() + "] (supplemented)");
            } else {
                projects.add(new RemoteResourcesProject(model, dependency, null));
                log.debug("Adding project with groupId [" + model.getGroupId() + "]");
            }
        }
        projects.sort(new ProjectComparator());
        return projects;
    }

    /**
     * Returns the resolved dependencies of a project, including all the transitive ones.
     */
    protected Map<String, Dependency> getAllDependencies(Project project) {
        Map<String, Dependency> result = new LinkedHashMap<>();
        Node root = session.collectDependencies(project, PathScope.TEST_RUNTIME);
        for (Node node : session.flattenDependencies(root, PathScope.TEST_RUNTIME)) {
            // the root node is the project itself
            if (node.getDependency() != null) {
                result.putIfAbsent(node.getDependency().key(), node.getDependency());
            }
        }
        return result;
    }

    /**
     * Returns the direct dependencies of a project.
     */
    protected Map<String, Dependency> getDirectDependencies(Project project) {
        Map<String, Dependency> result = new LinkedHashMap<>();
        Node root = session.collectDependencies(project, PathScope.TEST_RUNTIME);
        for (Node node : root.getChildren()) {
            if (node.getDependency() != null) {
                result.putIfAbsent(node.getDependency().key(), node.getDependency());
            }
        }
        return result;
    }

    /**
     * Returns all the transitive hull of all the involved maven projects.
     */
    protected abstract Set<Dependency> getAllDependencies();

    /**
     * Returns all the direct dependencies of all the involved maven projects.
     */
    protected abstract Set<Dependency> getDirectDependencies();

    protected Map<Organization, List<RemoteResourcesProject>> getProjectsSortedByOrganization(
            List<RemoteResourcesProject> projects) {
        Map<Organization, List<RemoteResourcesProject>> organizations = new TreeMap<>(new OrganizationComparator());
        List<RemoteResourcesProject> unknownOrganization = new ArrayList<>();

        for (RemoteResourcesProject p : projects) {
            if (p.getOrganization() != null
                    && StringUtils.isNotEmpty(p.getOrganization().getName())) {
                organizations
                        .computeIfAbsent(p.getOrganization(), k -> new ArrayList<>())
                        .add(p);
            } else {
                unknownOrganization.add(p);
            }
        }
        if (!unknownOrganization.isEmpty()) {
            Organization unknownOrg =
                    Organization.newBuilder().name("an unknown organization").build();
            organizations.put(unknownOrg, unknownOrganization);
        }

        return organizations;
    }

    protected boolean copyResourceIfExists(
            File outputFile, String bundleResourceName, VelocityContext context, String encoding)
            throws IOException, MojoException {
        ProjectManager projectManager = session.getService(ProjectManager.class);
        List<SourceRoot> resources = projectManager
                .getEnabledSourceRoots(project, ProjectScope.MAIN, Language.RESOURCES)
                .collect(Collectors.toList());
        for (SourceRoot resource : resources) {
            File resourceDirectory = resource.directory().toFile();

            if (!resourceDirectory.exists()) {
                continue;
            }

            // TODO - really should use the resource includes/excludes and name mapping
            File source = new File(resourceDirectory, bundleResourceName);
            File templateSource = new File(resourceDirectory, bundleResourceName + TEMPLATE_SUFFIX);

            if (!source.exists() && templateSource.exists()) {
                source = templateSource;
            }

            if (source.exists() && !source.equals(outputFile)) {
                if (source == templateSource) {
                    log.debug("Use project resource '" + source + "' as resource with Velocity");
                    try (CachingOutputStream os = new CachingOutputStream(outputFile);
                            Writer writer = getWriter(encoding, os);
                            Reader reader = getReader(encoding, source)) {
                        velocity.evaluate(context, writer, "", reader);
                    } catch (ParseErrorException | MethodInvocationException | ResourceNotFoundException e) {
                        throw new MojoException("Error rendering velocity resource: " + source, e);
                    }
                } else if (resource.stringFiltering()) {
                    log.debug("Use project resource '" + source + "' as resource with filtering");

                    MavenFileFilterRequest req = setupRequest(source, outputFile);

                    try {
                        fileFilter.copyFile(req);
                    } catch (MavenFilteringException e) {
                        throw new MojoException("Error filtering resource: " + source, e);
                    }
                } else {
                    log.debug("Use project resource '" + source + "' as resource");
                    Files.copy(source.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                // NOTE: the project resource can no longer be excluded from the resources of the project, the
                // source roots are immutable.
                return true;
            }
        }
        return false;
    }

    private boolean copyProjectRootIfExists(File outputFile, String bundleResourceName) throws IOException {
        if (!useProjectFiles) {
            return false;
        }

        File source = new File(project.getBasedir().toFile(), bundleResourceName);
        if (source.exists()) {
            log.debug("Use project file '" + source + "' as resource");
            Files.copy(source.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        }

        return false;
    }

    private Reader getReader(String readerEncoding, File file) throws IOException {
        return Files.newBufferedReader(
                file.toPath(), Charset.forName(readerEncoding != null ? readerEncoding : encoding));
    }

    private Writer getWriter(String writerEncoding, OutputStream outputStream) throws IOException {
        return new OutputStreamWriter(outputStream, writerEncoding != null ? writerEncoding : encoding);
    }

    private MavenFileFilterRequest setupRequest(File source, File file) {
        MavenFileFilterRequest req = new MavenFileFilterRequest();
        req.setFrom(source.toPath());
        req.setTo(file.toPath());
        req.setFiltering(true);

        req.setMavenProject(project);
        req.setMavenSession(session);
        req.setInjectProjectBuildFilters(true);
        req.setEncoding(encoding);

        if (filterDelimiters != null && !filterDelimiters.isEmpty()) {
            LinkedHashSet<String> delims = new LinkedHashSet<>();
            if (useDefaultFilterDelimiters) {
                delims.addAll(req.getDelimiters());
            }

            for (String delim : filterDelimiters) {
                if (delim == null) {
                    delims.add("${*}");
                } else {
                    delims.add(delim);
                }
            }

            req.setDelimiters(delims);
        }

        return req;
    }

    protected void validate() throws MojoException {
        int bundleCount = 1;

        for (String artifactDescriptor : resourceBundles) {
            // groupId:artifactId:version, groupId:artifactId:version:type
            // or groupId:artifactId:version:type:classifier
            String[] s = StringUtils.split(artifactDescriptor, ":");

            if (s.length < 3 || s.length > 5) {
                String position;

                if (bundleCount == 1) {
                    position = "1st";
                } else if (bundleCount == 2) {
                    position = "2nd";
                } else if (bundleCount == 3) {
                    position = "3rd";
                } else {
                    position = bundleCount + "th";
                }

                throw new MojoException("The " + position
                        + " resource bundle configured must specify a groupId, artifactId, "
                        + " version and, optionally, type and classifier for a remote resource bundle. "
                        + "Must be of the form <resourceBundle>groupId:artifactId:version</resourceBundle>, "
                        + "<resourceBundle>groupId:artifactId:version:type</resourceBundle> or "
                        + "<resourceBundle>groupId:artifactId:version:type:classifier</resourceBundle>");
            }

            bundleCount++;
        }
    }

    private static final String KEY_PROJECTS = "projects";
    private static final String KEY_PROJECTS_ORGS = "projectsSortedByOrganization";

    protected VelocityContext buildVelocityContext() {

        Map<String, Object> contextProperties = new HashMap<>(properties);

        if (includeProjectProperties) {
            contextProperties.putAll(session.getService(ProjectManager.class).getProperties(project));
        }

        // the following properties are expensive to calculate, so we provide them lazily
        VelocityContext context = new VelocityContext(contextProperties) {
            @Override
            public Object internalGet(String key) {
                Object result = super.internalGet(key);
                if (result == null && key != null && key.startsWith(KEY_PROJECTS) && containsKey(key)) {
                    // calculate and put projects* properties
                    List<RemoteResourcesProject> projects = getProjects();
                    put(KEY_PROJECTS, projects);
                    put(KEY_PROJECTS_ORGS, getProjectsSortedByOrganization(projects));
                    return super.internalGet(key);
                }
                return result;
            }
        };
        // to have a consistent getKeys()/containsKey() behaviour, keys must be present from the start
        context.put(KEY_PROJECTS, null);
        context.put(KEY_PROJECTS_ORGS, null);
        // the following properties are cheap to calculate, so we provide them eagerly

        String inceptionYear = project.getModel().getInceptionYear();

        // Reproducible Builds: try to use reproducible output timestamp
        String year = MavenArchiver.parseBuildOutputTimestamp(outputTimestamp)
                .orElseGet(Instant::now)
                .atZone(ZoneId.of("UTC+10"))
                .format(DateTimeFormatter.ofPattern("yyyy"));

        if (inceptionYear == null || inceptionYear.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("inceptionYear not specified, defaulting to " + year);
            }

            inceptionYear = year;
        }
        context.put("project", new RemoteResourcesProject(project.getModel(), null, project.getBasedir()));
        context.put("presentYear", year);
        context.put("locator", locator);

        if (inceptionYear.equals(year)) {
            context.put("projectTimespan", year);
        } else {
            context.put("projectTimespan", inceptionYear + "-" + year);
        }
        return context;
    }

    private List<File> downloadBundles(List<String> bundles) throws MojoException {
        List<File> bundleArtifacts = new ArrayList<>();
        ProjectManager projectManager = session.getService(ProjectManager.class);

        for (String artifactDescriptor : bundles) {
            log.info("Preparing remote bundle " + artifactDescriptor);
            // groupId:artifactId:version[:type[:classifier]]
            String[] s = artifactDescriptor.split(":");

            File artifactFile = null;
            // check if the artifact is part of the reactor
            if (session != null) {
                for (Project p : session.getProjects()) {
                    if (s[0].equals(p.getGroupId()) && s[1].equals(p.getArtifactId()) && s[2].equals(p.getVersion())) {
                        if (s.length >= 4 && "test-jar".equals(s[3])) {
                            artifactFile =
                                    p.getOutputDirectory(ProjectScope.TEST).toFile();
                        } else {
                            artifactFile =
                                    p.getOutputDirectory(ProjectScope.MAIN).toFile();
                        }
                    }
                }
            }
            if (artifactFile == null || !artifactFile.exists()) {
                String g = s[0];
                String a = s[1];
                String v = s[2];
                String typeId = s.length >= 4 ? s[3] : "jar";
                Type type = session.requireType(typeId);
                String classifier = s.length == 5 ? s[4] : type.getClassifier();

                Artifact artifact = session.createArtifact(g, a, v, classifier, type.getExtension(), typeId);

                try {
                    artifactFile = session.resolveArtifact(
                                    artifact, projectManager.getRemoteProjectRepositories(project))
                            .getPath()
                            .toFile();
                } catch (MavenException e) {
                    throw new MojoException("Error processing remote resources", e);
                }
            }
            bundleArtifacts.add(artifactFile);
        }

        return bundleArtifacts;
    }

    private ClassLoader initalizeClassloader(List<File> artifacts) throws MojoException {
        RemoteResourcesClassLoader cl = new RemoteResourcesClassLoader(null);
        try {
            for (File artifact : artifacts) {
                cl.addURL(artifact.toURI().toURL());
            }
            return cl;
        } catch (MalformedURLException e) {
            throw new MojoException("Unable to configure resources classloader: " + e.getMessage(), e);
        }
    }

    protected void processResourceBundles(ClassLoader classLoader, VelocityContext context) throws MojoException {
        List<Map.Entry<String, RemoteResourcesBundle>> remoteResources = new ArrayList<>();
        int bundleCount = 0;
        int resourceCount = 0;

        // list remote resources form bundles
        try {
            RemoteResourcesBundleXpp3Reader bundleReader = new RemoteResourcesBundleXpp3Reader();

            for (Enumeration<URL> e = classLoader.getResources(BundleRemoteResourcesMojo.RESOURCES_MANIFEST);
                    e.hasMoreElements(); ) {
                URL url = e.nextElement();
                bundleCount++;
                log.debug("processResourceBundle on bundle#" + bundleCount + " " + url);

                RemoteResourcesBundle bundle;

                try (InputStream in = url.openStream()) {
                    bundle = bundleReader.read(in);
                }

                verifyRequiredProperties(bundle, url);

                int n = 0;
                for (String bundleResource : bundle.getRemoteResources()) {
                    n++;
                    resourceCount++;
                    log.debug("bundle#" + bundleCount + " resource#" + n + " " + bundleResource);
                    remoteResources.add(new AbstractMap.SimpleEntry<>(bundleResource, bundle));
                }
            }
        } catch (IOException ioe) {
            throw new MojoException("Error finding remote resources manifests", ioe);
        } catch (XmlPullParserException xppe) {
            throw new MojoException("Error parsing remote resource bundle descriptor.", xppe);
        }

        log.info("Copying " + resourceCount + " resource" + ((resourceCount > 1) ? "s" : "") + " from " + bundleCount
                + " bundle" + ((bundleCount > 1) ? "s" : "") + ".");

        String velocityResource = null;
        try {

            for (Map.Entry<String, RemoteResourcesBundle> entry : remoteResources) {
                String bundleResource = entry.getKey();
                RemoteResourcesBundle bundle = entry.getValue();

                String projectResource = bundleResource;

                boolean doVelocity = false;
                if (projectResource.endsWith(TEMPLATE_SUFFIX)) {
                    projectResource = projectResource.substring(0, projectResource.length() - 3);
                    velocityResource = bundleResource;
                    doVelocity = true;
                }

                // Don't overwrite resource that are already being provided.

                File outputFile = new File(outputDirectory, projectResource);

                Files.createDirectories(outputFile.getParentFile().toPath());

                // resource exists in project resources
                if (copyResourceIfExists(outputFile, projectResource, context, bundle.getSourceEncoding())) {
                    continue;
                }

                if (copyProjectRootIfExists(outputFile, projectResource)) {
                    continue;
                }

                if (doVelocity) {
                    String bundleEncoding = bundle.getSourceEncoding();
                    if (bundleEncoding == null) {
                        bundleEncoding = encoding;
                    }

                    try (CachingOutputStream os = new CachingOutputStream(outputFile);
                            Writer writer = getWriter(bundleEncoding, os)) {
                        velocity.mergeTemplate(bundleResource, bundleEncoding, context, writer);
                    }
                } else {
                    URL bundleResourceUrl = classLoader.getResource(bundleResource);
                    if (bundleResourceUrl != null) {
                        try (InputStream in = bundleResourceUrl.openStream()) {
                            Files.copy(in, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }

                File appendedResourceFile = new File(appendedResourcesDirectory, projectResource);
                File appendedVmResourceFile = new File(appendedResourcesDirectory, projectResource + ".vm");

                if (appendedResourceFile.exists()) {
                    log.info("Copying appended resource: " + projectResource);
                    try (InputStream in = Files.newInputStream(appendedResourceFile.toPath());
                            OutputStream out = Files.newOutputStream(
                                    outputFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                        in.transferTo(out);
                    }

                } else if (appendedVmResourceFile.exists()) {
                    log.info("Filtering appended resource: " + projectResource + ".vm");

                    try (CachingOutputStream os = new CachingOutputStream(outputFile);
                            Reader reader = getReader(bundle.getSourceEncoding(), appendedVmResourceFile);
                            Writer writer = getWriter(bundle.getSourceEncoding(), os)) {
                        Velocity.init();
                        Velocity.evaluate(context, writer, "remote-resources", reader);
                    }
                }
            }
        } catch (IOException ioe) {
            throw new MojoException("Error reading remote resource", ioe);
        } catch (VelocityException e) {
            throw new MojoException("Error rendering Velocity resource '" + velocityResource + "'", e);
        }
    }

    private void verifyRequiredProperties(RemoteResourcesBundle bundle, URL url) throws MojoException {
        if (bundle.getRequiredProjectProperties() == null
                || bundle.getRequiredProjectProperties().isEmpty()) {
            return;
        }

        for (String requiredProperty : bundle.getRequiredProjectProperties()) {
            if (!session.getService(ProjectManager.class).getProperties(project).containsKey(requiredProperty)) {
                throw new MojoException(
                        "Required project property: '" + requiredProperty + "' is not present for bundle: " + url);
            }
        }
    }

    protected Model getSupplement(Xpp3Dom supplementModelXml) throws MojoException {
        Model model = null;

        try {
            model = session.getService(ModelXmlFactory.class)
                    .read(XmlReaderRequest.builder()
                            .reader(new StringReader(supplementModelXml.toString()))
                            .strict(false)
                            .build());
            String groupId = model.getGroupId();
            String artifactId = model.getArtifactId();

            if (groupId == null || groupId.trim().isEmpty()) {
                throw new MojoException("Supplemental project XML " + "requires that a <groupId> element be present.");
            }

            if (artifactId == null || artifactId.trim().isEmpty()) {
                throw new MojoException(
                        "Supplemental project XML " + "requires that a <artifactId> element be present.");
            }
        } catch (MavenException e) {
            log.warn("Unable to read supplemental XML: " + e.getMessage(), e);
        }

        return model;
    }

    protected Model mergeModels(Model parent, Model child) {
        return inheritanceAssembler.assembleModelInheritance(child, parent);
    }

    private static String generateSupplementMapKey(String groupId, String artifactId) {
        return groupId.trim() + ":" + artifactId.trim();
    }

    private Map<String, Model> loadSupplements(String[] models) throws MojoException {
        if (models == null) {
            log.debug("Supplemental data models won't be loaded. No models specified.");
            return Collections.emptyMap();
        }

        List<Supplement> supplements = new ArrayList<>();
        for (String set : models) {
            log.debug("Preparing ruleset: " + set);
            try {
                File f = locator.getResourceAsFile(set, getLocationTemp(set));

                if (null == f || !f.exists()) {
                    throw new MojoException("Cold not resolve " + set);
                }
                if (!f.canRead()) {
                    throw new MojoException("Supplemental data models won't be loaded. " + "File " + f.getAbsolutePath()
                            + " cannot be read, check permissions on the file.");
                }

                log.debug("Loading supplemental models from " + f.getAbsolutePath());

                SupplementalDataModelXpp3Reader reader = new SupplementalDataModelXpp3Reader();
                SupplementalDataModel supplementalModel = reader.read(new FileReader(f));
                supplements.addAll(supplementalModel.getSupplement());
            } catch (Exception e) {
                String msg = "Error loading supplemental data models: " + e.getMessage();
                log.error(msg, e);
                throw new MojoException(msg, e);
            }
        }

        log.debug("Loading supplements complete.");

        Map<String, Model> supplementMap = new HashMap<>();
        for (Supplement sd : supplements) {
            Xpp3Dom dom = (Xpp3Dom) sd.getProject();

            Model m = getSupplement(dom);
            supplementMap.put(generateSupplementMapKey(m.getGroupId(), m.getArtifactId()), m);
        }

        return supplementMap;
    }

    /**
     * Convenience method to get the location of the specified file name.
     *
     * @param name the name of the file whose location is to be resolved
     * @return a String that contains the absolute file name of the file
     */
    private String getLocationTemp(String name) {
        String loc = name;
        if (loc.indexOf('/') != -1) {
            loc = loc.substring(loc.lastIndexOf('/') + 1);
        }
        if (loc.indexOf('\\') != -1) {
            loc = loc.substring(loc.lastIndexOf('\\') + 1);
        }
        log.debug("Before: " + name + " After: " + loc);
        return loc;
    }

    static class OrganizationComparator implements Comparator<Organization> {
        @Override
        public int compare(Organization org1, Organization org2) {
            int i = compareStrings(org1.getName(), org2.getName());
            if (i == 0) {
                i = compareStrings(org1.getUrl(), org2.getUrl());
            }
            return i;
        }

        private int compareStrings(String s1, String s2) {
            if (s1 == null && s2 == null) {
                return 0;
            } else if (s1 == null) {
                return 1;
            } else if (s2 == null) {
                return -1;
            }

            return s1.compareToIgnoreCase(s2);
        }
    }

    static class ProjectComparator implements Comparator<RemoteResourcesProject> {
        @Override
        public int compare(RemoteResourcesProject p1, RemoteResourcesProject p2) {
            int i = p1.getGroupId().compareTo(p2.getGroupId());
            if (i == 0) {
                i = p1.getArtifactId().compareTo(p2.getArtifactId());
            }
            if (i == 0) {
                i = p1.getVersion().compareTo(p2.getVersion());
            }
            return i;
        }
    }
}
