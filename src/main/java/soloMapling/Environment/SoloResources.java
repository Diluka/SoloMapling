package soloMapling.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Read-only SoloMapling data from the development tree or the executable JAR. */
public final class SoloResources {

    public static final String CP_ROOT = "soloMapling/";
    public static final String LEGACY_FS_ROOT = "src/main/java/soloMapling/";

    private SoloResources() {
    }

    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("SoloMapling resource path is blank");
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.startsWith(LEGACY_FS_ROOT)) {
            normalized = normalized.substring(LEGACY_FS_ROOT.length());
        } else if (normalized.startsWith(CP_ROOT)) {
            normalized = normalized.substring(CP_ROOT.length());
        }
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    public static boolean exists(String path) {
        String relative = normalize(path);
        return Files.isRegularFile(Path.of(path))
                || Files.isRegularFile(Path.of(LEGACY_FS_ROOT, relative))
                || classLoader().getResource(CP_ROOT + relative) != null;
    }

    public static InputStream openStream(String path) throws IOException {
        Path direct = Path.of(path);
        if (Files.isRegularFile(direct)) return Files.newInputStream(direct);
        String relative = normalize(path);
        Path legacy = Path.of(LEGACY_FS_ROOT, relative);
        if (Files.isRegularFile(legacy)) return Files.newInputStream(legacy);
        InputStream stream = classLoader().getResourceAsStream(CP_ROOT + relative);
        if (stream == null) throw new IOException("SoloMapling resource not found: " + path);
        return stream;
    }

    public static Reader openReader(String path) throws IOException {
        return new InputStreamReader(openStream(path), StandardCharsets.UTF_8);
    }

    public static List<String> listFileNames(String directory, String suffix) {
        String relative = normalize(directory);
        if (!relative.endsWith("/")) relative += "/";
        Set<String> names = new LinkedHashSet<>();
        collectDirectory(Path.of(directory), suffix, names);
        collectDirectory(Path.of(LEGACY_FS_ROOT, relative), suffix, names);
        String prefix = CP_ROOT + relative;
        try {
            Enumeration<URL> locations = classLoader().getResources(prefix);
            while (locations.hasMoreElements()) collectUrl(locations.nextElement(), prefix, suffix, names);
            // Some JAR writers omit directory entries. A known resource locates the same data archive.
            URL anchor = classLoader().getResource(CP_ROOT + "FreeMarket/FMNameDesc/randomRealMaplestoryIGNs.txt");
            if (anchor != null) collectUrl(anchor, prefix, suffix, names);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not list SoloMapling resource directory: " + directory, failure);
        }
        return new ArrayList<>(names);
    }

    public static List<String> listBasenames(String directory, String suffix) {
        return listFileNames(directory, suffix).stream().map(name -> {
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }).distinct().toList();
    }

    public static boolean directoryExists(String directory) {
        return Files.isDirectory(Path.of(directory))
                || Files.isDirectory(Path.of(LEGACY_FS_ROOT, normalize(directory)))
                || !listFileNames(directory, null).isEmpty();
    }

    private static void collectDirectory(Path directory, String suffix, Set<String> names) {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(file -> addName(file.getFileName().toString(), suffix, names));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not list SoloMapling directory: " + directory, failure);
        }
    }

    private static void collectUrl(URL resource, String requestedPrefix, String suffix, Set<String> names) throws IOException {
        URLConnection connection = resource.openConnection();
        if (connection instanceof JarURLConnection archive) {
            archive.setUseCaches(false);
            // JarURLConnection also supports Spring Boot's nested archives; do not turn its URL into a filesystem Path.
            try (JarFile jar = archive.getJarFile()) {
                String entryName = archive.getEntryName();
                int packageRoot = entryName.indexOf(CP_ROOT);
                String prefix = (packageRoot < 0 ? "" : entryName.substring(0, packageRoot)) + requestedPrefix;
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
                    String child = entry.getName().substring(prefix.length());
                    if (!child.isEmpty() && !child.contains("/")) addName(child, suffix, names);
                }
            }
        } else if ("file".equals(resource.getProtocol())) {
            try {
                Path location = Path.of(resource.toURI());
                while (location != null && (location.getFileName() == null || !location.getFileName().toString().equals("soloMapling"))) {
                    location = location.getParent();
                }
                if (location != null) collectDirectory(location.resolve(requestedPrefix.substring(CP_ROOT.length())), suffix, names);
            } catch (java.net.URISyntaxException failure) {
                throw new IOException("Invalid SoloMapling resource URL: " + resource, failure);
            }
        }
    }

    private static void addName(String name, String suffix, Set<String> names) {
        if (suffix == null || name.endsWith(suffix)) names.add(name);
    }

    private static ClassLoader classLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? SoloResources.class.getClassLoader() : loader;
    }
}
