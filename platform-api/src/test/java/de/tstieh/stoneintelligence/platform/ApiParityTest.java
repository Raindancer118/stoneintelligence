package de.tstieh.stoneintelligence.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0011, Punkt 6: alles, was der Server kann, muss sowohl aus Obsidian als auch im Web-Dashboard
 * erreichbar sein. Jeder oeffentliche Endpunkt ({@code /api/v1/**}) muss deshalb im Plugin UND in der
 * Webapp vorkommen - ausser er steht mit Begruendung in den Ausnahmen unten. Eine neue Funktion ohne
 * Oberflaeche in einem der beiden faellt so in CI auf.
 */
class ApiParityTest {

    /** Nur im Plugin noetig - mit Begruendung. */
    private static final Map<String, String> NOT_IN_WEBAPP = Map.of(
        "/api/v1/vaults/*/sync-tickets", "Live-Sync-Verbindung gibt es nur im Plugin",
        "/api/v1/files/limits", "Grenzen fuer den Datei-Sync, die Webapp laedt keine Vault-Dateien hoch",
        "/api/v1/vaults/*/access/grants", "Kennzeichen im Obsidian-Dateibaum; die Webapp zeigt Freigaben je Eintrag",
        "/api/v1/vaults/*/folders/rename", "Ordner verschiebt man im Obsidian-Dateibaum, die Webapp hat keinen Ordnerbaum",
        "/api/v1/vaults/*/ai/jobs/from-files", "Die Webapp laedt Dokumente direkt hoch statt aus dem Vault",
        "/api/v1/vaults/*/files", "Dateien legt der Datei-Sync an; die Webapp zeigt und laedt sie nur",
        "/api/v1/vaults/*/folders", "Ordner verwaltet man im Obsidian-Dateibaum, die Webapp hat keinen Ordnerbaum");

    /** Nur in der Webapp noetig - mit Begruendung. */
    private static final Map<String, String> NOT_IN_PLUGIN = Map.of(
        "/api/v1/invitations/*", "Einladungen nimmt man ueber den Link aus der Mail im Browser an",
        "/api/v1/invitations/*/accept", "Einladungen nimmt man ueber den Link aus der Mail im Browser an",
        "/api/v1/vaults/*/notes/*/content", "Das Plugin uebertraegt Inhalte live ueber die Sync-Verbindung (Yjs)");

    private static final Pattern CLIENT_PATH = Pattern.compile("/api/v1/[^\"'`\\s?]*");

    @Test
    void should_offerEveryPublicEndpoint_inObsidianAndOnTheWeb() throws IOException {
        var server = serverPaths();
        var plugin = clientPaths(Path.of("../plugin/src"));
        var webapp = clientPaths(Path.of("../webapp/src"));

        assertThat(server).as("server endpoints found").hasSizeGreaterThan(40);
        assertThat(missing(server, plugin, NOT_IN_PLUGIN)).as("endpoints the plugin does not use").isEmpty();
        assertThat(missing(server, webapp, NOT_IN_WEBAPP)).as("endpoints the webapp does not use").isEmpty();
        assertThat(server).as("exceptions must name existing endpoints")
            .containsAll(NOT_IN_PLUGIN.keySet()).containsAll(NOT_IN_WEBAPP.keySet());
    }

    private static Set<String> missing(Set<String> server, Set<String> client, Map<String, String> exceptions) {
        var missing = new TreeSet<>(server);
        missing.removeAll(client);
        missing.removeAll(exceptions.keySet());
        return missing;
    }

    private static Set<String> serverPaths() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var paths = new TreeSet<String>();
        for (var candidate : scanner.findCandidateComponents("de.tstieh.stoneintelligence.platform")) {
            Class<?> controller;
            try {
                controller = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            var classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            var prefixes = classMapping == null || classMapping.path().length == 0 ? new String[] {""} : classMapping.path();
            for (var method : controller.getDeclaredMethods()) {
                var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                var own = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                for (var prefix : prefixes) {
                    for (var path : own) {
                        var full = prefix + path;
                        if (full.startsWith("/api/v1/")) {
                            paths.add(full.replaceAll("\\{[^}]+}", "*"));
                        }
                    }
                }
            }
        }
        return paths;
    }

    private static Set<String> clientPaths(Path root) throws IOException {
        var paths = new TreeSet<String>();
        try (Stream<Path> files = Files.walk(root)) {
            for (var file : files.filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".svelte")).toList()) {
                var matcher = CLIENT_PATH.matcher(Files.readString(file));
                while (matcher.find()) {
                    paths.add(matcher.group().replaceAll("\\$\\{[^}]*}", "*").replaceAll("/+$", ""));
                }
            }
        }
        return paths;
    }
}
