package com.java2nb.common.security;

import com.java2nb.common.config.Constant;
import com.java2nb.common.config.JnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class UploadPathResolverTest {
    @TempDir Path temp;

    private UploadPathResolver resolver() throws Exception {
        var config = new JnConfig();
        config.setUploadPath(Files.createDirectories(temp.resolve("uploads")).toString());
        return new UploadPathResolver(config);
    }

    @ParameterizedTest @ValueSource(strings = {"../secret", "a/../../secret", "/etc/passwd", "C:/secret", "..\\secret", "%2e%2e/secret", "%252e%252e/secret", "a/../secret", ".", ""})
    void traversalAbsoluteAndEncodedPathsAreRejected(String path) throws Exception {
        var resolver = resolver();
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(path));
    }

    @Test void legitimateFilesResolveInsideTheUploadDirectory() throws Exception {
        var resolver = resolver(); var file = resolver.resolve("2026/10/08/cover.png");
        Files.createDirectories(file.getParent()); Files.writeString(file, "fixture");
        assertEquals(file, resolver.existingFile("2026/10/08/cover.png"));
        assertEquals(file, resolver.resolveUrl(Constant.UPLOAD_FILES_PREFIX + "2026/10/08/cover.png"));
    }

    @Test void neitherLeafNorParentSymlinksCanEscape() throws Exception {
        var resolver = resolver(); var secret = temp.resolve("secret"); Files.writeString(secret, "preserve");
        Files.createSymbolicLink(temp.resolve("uploads/leaf"), secret);
        Files.createSymbolicLink(temp.resolve("uploads/parent"), temp);
        assertThrows(IllegalArgumentException.class, () -> resolver.existingFile("leaf"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("parent/secret"));
        assertEquals("preserve", Files.readString(secret));
    }
}
