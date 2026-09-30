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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The output file of a remote resource must stay inside the output directory, also through symbolic links.
 */
class OutputPathTest {

    @TempDir
    Path tempDir;

    @Test
    void cannotEscapeOutputDirectory() throws Exception {
        File outputDirectory = tempDir.resolve("remote-resource-output").toFile();
        File canonicalOutputDirectory = outputDirectory.getCanonicalFile();

        MojoException e = assertThrows(
                MojoException.class,
                () -> AbstractProcessRemoteResourcesMojo.resolveOutputFile(
                        outputDirectory, "../outside.txt", canonicalOutputDirectory));
        assertTrue(e.getMessage().contains("must remain within the output directory"));

        e = assertThrows(
                MojoException.class,
                () -> AbstractProcessRemoteResourcesMojo.resolveOutputFile(
                        outputDirectory,
                        new File(outputDirectory.getParentFile(), "absolute.txt").getAbsolutePath(),
                        canonicalOutputDirectory));
        assertTrue(e.getMessage().contains("Absolute remote resource paths are not allowed"));

        File outputFile = AbstractProcessRemoteResourcesMojo.resolveOutputFile(
                outputDirectory, "nested/resource.txt", canonicalOutputDirectory);
        assertEquals(new File(outputDirectory, "nested/resource.txt").getCanonicalFile(), outputFile);
    }

    @Test
    void cannotEscapeOutputDirectoryThroughSymlink() throws Exception {
        Path outputDirectory = Files.createDirectory(tempDir.resolve("output"));
        Path outsideDirectory = Files.createDirectory(tempDir.resolve("outside"));
        symlink(outputDirectory.resolve("external"), outsideDirectory);

        File output = outputDirectory.toFile();
        File canonicalOutputDirectory = output.getCanonicalFile();
        MojoException e = assertThrows(
                MojoException.class,
                () -> AbstractProcessRemoteResourcesMojo.resolveOutputFile(
                        output, "external/escaped.txt", canonicalOutputDirectory));
        assertTrue(e.getMessage().contains("must remain within the output directory"));
    }

    @Test
    void resolvesNestedSymlinksBeforeCheckingContainment() throws Exception {
        Path outputDirectory = Files.createDirectory(tempDir.resolve("output"));
        Path insideDirectory = Files.createDirectory(outputDirectory.resolve("inside"));
        Path outsideDirectory = Files.createDirectory(tempDir.resolve("outside"));
        symlink(outputDirectory.resolve("inside-link"), insideDirectory);
        symlink(insideDirectory.resolve("outside-link"), outsideDirectory);

        File output = outputDirectory.toFile();
        File canonicalOutputDirectory = output.getCanonicalFile();
        File safeOutputFile = AbstractProcessRemoteResourcesMojo.resolveOutputFile(
                output, "inside-link/not-created/resource.txt", canonicalOutputDirectory);
        assertEquals(
                insideDirectory.toRealPath().resolve("not-created/resource.txt").toFile(), safeOutputFile);

        MojoException e = assertThrows(
                MojoException.class,
                () -> AbstractProcessRemoteResourcesMojo.resolveOutputFile(
                        output, "inside-link/outside-link/not-created/resource.txt", canonicalOutputDirectory));
        assertTrue(e.getMessage().contains("must remain within the output directory"));
    }

    @Test
    void failsClosedWhenPathCannotBeInspected() throws Exception {
        Path outputDirectory = Files.createDirectory(tempDir.resolve("output"));
        Path restrictedDirectory = Files.createDirectory(outputDirectory.resolve("restricted"));
        Path outsideDirectory = Files.createDirectory(tempDir.resolve("outside"));

        EnumSet<PosixFilePermission> originalPermissions;
        try {
            originalPermissions = EnumSet.copyOf(Files.getPosixFilePermissions(restrictedDirectory));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "no POSIX permissions");
            return;
        }
        symlink(restrictedDirectory.resolve("external"), outsideDirectory);
        Files.setPosixFilePermissions(
                restrictedDirectory, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        try {
            // running as root ignores the permissions
            assumeTrue(!Files.isReadable(restrictedDirectory.resolve("external")), "permissions not enforced");
            assertThrows(
                    IOException.class,
                    () -> AbstractProcessRemoteResourcesMojo.resolveOutputFile(
                            outputDirectory.toFile(),
                            "restricted/external/escaped.txt",
                            outputDirectory.toFile().getCanonicalFile()));
        } finally {
            Files.setPosixFilePermissions(restrictedDirectory, originalPermissions);
        }
    }

    private static void symlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links not supported: " + e);
        }
    }
}
