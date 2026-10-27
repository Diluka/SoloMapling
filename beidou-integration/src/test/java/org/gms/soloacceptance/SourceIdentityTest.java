package org.gms.soloacceptance;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SourceIdentityTest {
    @Test
    void publicManifestProjectionNeverReturnsUnexpectedConfigurationFields() {
        Map<String, Object> publicIdentity = SourceIdentity.publicFields(Map.of(
                "soloGitRevision", "solo", "beidouRevision", "engine", "soloWorkingTreeDirty", false,
                "password", "secret", "database", Map.of("password", "other-secret")));
        assertEquals(true, publicIdentity.get("available"));
        assertEquals(4, publicIdentity.size());
        assertFalse(publicIdentity.toString().contains("secret"));
    }

    @Test
    void bootNestedAndStandardJarUrlsResolveTheOuterArchiveWithoutUsingTheWorkingDirectory() {
        assertEquals(Path.of("/opt/server backup/BeiDou.jar"),
                SourceIdentity.outerArchive("jar:nested:/opt/server%20backup/BeiDou.jar/!BOOT-INF/classes/!/"));
        assertEquals(Path.of("/opt/BeiDou.jar"),
                SourceIdentity.outerArchive("jar:file:/opt/BeiDou.jar!/BOOT-INF/classes/"));
        assertNull(SourceIdentity.outerArchive("file:/tmp/test-classes/"));
    }
}
