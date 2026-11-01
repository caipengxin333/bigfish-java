package cn.bigfish.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.springframework.web.util.UriUtils;

/** Optional public asset directory, fixed at startup and independent of sessions and provider credentials. */
@Configuration(proxyBeanMethods = false)
public class AvatarAssetsConfiguration {
    private static final String PREFIX = "/avatar-assets/";
    private static final List<String> MODEL_JSON = List.of(".model3.json", ".physics3.json", ".pose3.json",
            ".motion3.json", ".exp3.json", ".cdi3.json", ".userdata3.json", ".motionsync3.json");
    private static final Map<String, MediaType> TYPES = Map.of("json", MediaType.APPLICATION_JSON,
            "moc3", MediaType.APPLICATION_OCTET_STREAM, "png", MediaType.IMAGE_PNG,
            "js", MediaType.parseMediaType("text/javascript"));
    private final Path root;

    public AvatarAssetsConfiguration(@Value("${bigfish.avatar.assets-dir:}") String directory,
                                     @Value("${bigfish.data-dir:./runtime}") String dataDirectory) {
        root = validateRoot(directory, dataDirectory);
    }

    private static Path validateRoot(String directory, String dataDirectory) {
        if (directory.isBlank()) return null;
        try {
            Path path = Path.of(directory);
            if (!path.isAbsolute()) throw new IllegalArgumentException();
            path = path.toRealPath();
            Path data = Path.of(dataDirectory).toAbsolutePath().normalize();
            if (Files.exists(data)) data = data.toRealPath();
            if (!Files.isDirectory(path) || !Files.isReadable(path) || !Files.isExecutable(path) || path.getParent() == null
                    || path.equals(Path.of(System.getProperty("user.home")).toRealPath())
                    || path.equals(Path.of("").toRealPath()) || path.startsWith(data)) throw new IllegalArgumentException();
            return path;
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            // Do not retain the supplied path or raw filesystem exception in startup diagnostics.
            throw new IllegalStateException("AVATAR_ASSETS_DIR_INVALID: BIGFISH_AVATAR_ASSETS_DIR "
                    + "(bigfish.avatar.assets-dir) must be an existing readable and searchable absolute asset directory, "
                    + "separate from the filesystem root, home, working directory and runtime data directory.");
        }
    }

    @Bean ResourceHttpRequestHandler avatarAssetsHandler() {
        var handler = new ResourceHttpRequestHandler() {
            @Override protected Resource getResource(HttpServletRequest request) {
                if (root == null) return null;
                String path = relativePath(request);
                if (!allowedAsset(path)) return null;
                try {
                    Path file = root.resolve(path).toRealPath();
                    // Resolve again on every request: no stale resource cache after local replacement.
                    if (!file.startsWith(root) || !allowedAsset(root.relativize(file).toString().replace('\\', '/'))
                            || !Files.isRegularFile(file) || !Files.isReadable(file)) return null;
                    return new FileSystemResource(file);
                } catch (IOException | IllegalArgumentException | SecurityException missing) { return null; }
            }
            @Override protected MediaType getMediaType(HttpServletRequest request, Resource resource) {
                String name = resource.getFilename();
                return TYPES.get(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
            }
        };
        handler.setSupportedMethods("GET", "HEAD");
        handler.setCacheControl(CacheControl.noCache().cachePrivate());
        // Last-Modified has only second precision. A metadata ETag also detects same-second replacements.
        handler.setUseLastModified(false);
        handler.setEtagGenerator(AvatarAssetsConfiguration::etag);
        return handler;
    }

    @Bean SimpleUrlHandlerMapping avatarAssetsMapping(ResourceHttpRequestHandler avatarAssetsHandler) {
        // Reserve even the disabled/missing prefix: never fall through to classpath assets or SPA HTML.
        return new SimpleUrlHandlerMapping(Map.of("/avatar-assets", avatarAssetsHandler,
                "/avatar-assets/**", avatarAssetsHandler), -1);
    }

    @Bean FilterRegistrationBean<OncePerRequestFilter> avatarAssetsPathGuard() {
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                       FilterChain chain) throws ServletException, IOException {
                String path = decodedPath(request);
                // Tomcat can normalize a traversal into a different handler before Spring mapping.
                // Check the original URI here, including encoded prefix characters, before that handler runs.
                if (path != null && (path.equals("/avatar-assets") || path.startsWith(PREFIX))) {
                    response.setHeader("X-Content-Type-Options", "nosniff");
                    // Include 304 and missing-file responses; a file added locally must not remain negatively cached.
                    response.setHeader("Cache-Control", "no-cache, private");
                    if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")) {
                        response.setHeader("Allow", "GET, HEAD");
                        response.setStatus(405);
                        return;
                    }
                    if (!allowedAsset(relativePath(request))) {
                        response.setHeader("Cache-Control", "no-store");
                        response.setStatus(404);
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(-100);
        return registration;
    }

    private static String decodedPath(HttpServletRequest request) {
        try {
            String path = UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8);
            String context = request.getContextPath();
            return path.startsWith(context + "/") ? path.substring(context.length()) : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static String relativePath(HttpServletRequest request) {
        String path = decodedPath(request);
        return path != null && path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : null;
    }

    private static boolean allowedAsset(String path) {
        if (path == null || path.isEmpty() || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || path.indexOf('%') >= 0 || path.indexOf(';') >= 0 || path.chars().anyMatch(c -> c < 32 || c == 127)) return false;
        String[] segments = path.split("/", -1);
        for (String part : segments) if (part.isEmpty() || part.startsWith(".")) return false;
        if (path.equals("sdk/live2dcubismcore.min.js")) return true;
        if (segments.length < 3 || !segments[0].equals("models")) return false;
        String name = segments[segments.length - 1].toLowerCase(Locale.ROOT);
        return name.endsWith(".moc3") || name.endsWith(".png") || MODEL_JSON.stream().anyMatch(name::endsWith);
    }

    private static String etag(Resource resource) {
        try {
            var attributes = Files.readAttributes(resource.getFile().toPath(), BasicFileAttributes.class);
            String metadata = attributes.lastModifiedTime() + ":" + attributes.size() + ":" + attributes.fileKey();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(metadata.getBytes(StandardCharsets.UTF_8));
            return "W/\"" + HexFormat.of().formatHex(hash) + "\"";
        } catch (Exception unavailable) { return null; }
    }
}
