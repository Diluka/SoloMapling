package org.gms.soloacceptance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read only the generated public source manifest, never application configuration. */
final class SourceIdentity {
    private static final String RESOURCE = "META-INF/solomapling/source.json";
    private static final List<String> FIELDS = List.of("sourceRepository", "beidouRevision", "soloGitRevision",
            "soloWorkingTreeDirty", "soloSourcesSha256", "integration");

    private SourceIdentity() { }

    static Map<String, Object> read() {
        try (InputStream stream = SourceIdentity.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream != null) return parse(stream);
        } catch (IOException ignored) {
            return Map.of("available", false);
        }
        // The manifest belongs to the outer executable archive rather than BOOT-INF/classes.
        // Boot's nested code-source URL identifies that outer file without guessing a cwd or JAR name.
        try {
            String location = SourceIdentity.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
            Path archive = outerArchive(location);
            if (archive == null) return Map.of("available", false);
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                ZipEntry entry = zip.getEntry(RESOURCE);
                if (entry == null) return Map.of("available", false);
                try (InputStream stream = zip.getInputStream(entry)) {
                    return parse(stream);
                }
            }
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            return Map.of("available", false);
        }
    }

    static Path outerArchive(String location) {
        int nested = location.indexOf("nested:");
        if (nested >= 0) {
            int separator = location.indexOf("/!", nested);
            if (separator > nested) return Path.of(URI.create("file:" + location.substring(nested + 7, separator)));
        }
        String file = location.startsWith("jar:") ? location.substring(4) : location;
        int separator = file.indexOf("!/");
        if (separator >= 0) file = file.substring(0, separator);
        return file.startsWith("file:") && file.endsWith(".jar") ? Path.of(URI.create(file)) : null;
    }

    static Map<String, Object> publicFields(Map<String, Object> manifest) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("available", manifest.containsKey("soloGitRevision") && manifest.containsKey("beidouRevision"));
        for (String field : FIELDS) {
            Object value = manifest.get(field);
            if (value instanceof String || value instanceof Boolean) output.put(field, value);
        }
        return output;
    }

    private static Map<String, Object> parse(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(65_537);
        if (bytes.length > 65_536) return Map.of("available", false);
        Map<String, Object> manifest = new ObjectMapper().readValue(bytes, new TypeReference<>() { });
        return publicFields(manifest);
    }
}
