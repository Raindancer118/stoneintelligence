package de.raindancer118.stoneintelligence.worker.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** {@link PlatformApi} ueber HTTP mit dem Worker-Token. */
public final class PlatformHttpClient implements PlatformApi {

    static final String TOKEN_HEADER = "X-StoneIntelligence-Worker-Token";

    private final HttpClient http;
    private final String baseUrl;
    private final String token;
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public PlatformHttpClient(String baseUrl, String token) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUrl, token);
    }

    PlatformHttpClient(HttpClient http, String baseUrl, String token) {
        this.http = http;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    @Override
    public Optional<ClaimedJob> claim() {
        var response = send(request("/internal/ai/jobs/claim").POST(HttpRequest.BodyPublishers.noBody()));
        return response.statusCode() == 204 ? Optional.empty() : Optional.of(parse(response, new TypeReference<ClaimedJob>() { }));
    }

    @Override
    public byte[] document(UUID jobId) {
        try {
            var response = http.send(request("/internal/ai/jobs/" + jobId + "/document").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            check(response.statusCode(), () -> new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted", e));
        }
    }

    @Override
    public void progress(UUID jobId, String message, Integer percent) {
        var body = new LinkedHashMap<String, Object>();
        body.put("message", message);
        body.put("percent", percent);
        post("/internal/ai/jobs/" + jobId + "/progress", body);
    }

    @Override
    public void complete(UUID jobId) {
        send(request("/internal/ai/jobs/" + jobId + "/complete").POST(HttpRequest.BodyPublishers.noBody()));
    }

    @Override
    public void fail(UUID jobId, String error, boolean retryable) {
        post("/internal/ai/jobs/" + jobId + "/fail", Map.of("error", error, "retryable", retryable));
    }

    @Override
    public void waitForCapacity(UUID jobId, String error, java.time.Instant availableAt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", error);
        body.put("availableAt", availableAt == null ? null : availableAt.toString());
        post("/internal/ai/jobs/" + jobId + "/wait-for-capacity", body);
    }

    @Override
    public List<String> services() {
        return parse(send(request("/internal/ai/services").GET()), new TypeReference<List<ServiceRef>>() { }).stream()
            .map(ServiceRef::id).toList();
    }

    @Override
    public void reportCapacity(String serviceId, List<ProviderReport> providers) {
        send(request("/internal/ai/services/" + java.net.URLEncoder.encode(serviceId, java.nio.charset.StandardCharsets.UTF_8) + "/capacity")
            .PUT(HttpRequest.BodyPublishers.ofString(write(Map.of("providers", providers))))
            .header("Content-Type", "application/json"));
    }

    @Override
    public List<ListedNote> notes(String vaultId, UUID changeSetId) {
        return parse(send(request(notesPath(vaultId, changeSetId)).GET()), new TypeReference<List<ListedNote>>() { });
    }

    @Override
    public String read(String vaultId, UUID changeSetId, String noteId) {
        return parse(send(request(notesPath(vaultId, changeSetId) + "/" + noteId).GET()), new TypeReference<NoteText>() { }).text();
    }

    @Override
    public String create(String vaultId, UUID changeSetId, String path, String text, int level) {
        return parse(post(notesPath(vaultId, changeSetId), Map.of("path", path, "text", text, "level", level)),
            new TypeReference<NoteRef>() { }).noteId();
    }

    @Override
    public void update(String vaultId, UUID changeSetId, String noteId, String text) {
        send(request(notesPath(vaultId, changeSetId) + "/" + noteId)
            .PUT(HttpRequest.BodyPublishers.ofString(write(Map.of("text", text))))
            .header("Content-Type", "application/json"));
    }

    @Override
    public String storeFile(String vaultId, UUID changeSetId, String path, byte[] content, String contentType, int level) {
        var url = "/internal/ai/vaults/" + vaultId + "/change-sets/" + changeSetId + "/files?path="
            + java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8) + "&level=" + level;
        return parse(send(request(url).POST(HttpRequest.BodyPublishers.ofByteArray(content)).header("Content-Type", contentType)),
            new TypeReference<NoteRef>() { }).path();
    }

    private static String notesPath(String vaultId, UUID changeSetId) {
        return "/internal/ai/vaults/" + vaultId + "/change-sets/" + changeSetId + "/notes";
    }

    private HttpResponse<String> post(String path, Object body) {
        return send(request(path).POST(HttpRequest.BodyPublishers.ofString(write(body))).header("Content-Type", "application/json"));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(60)).header(TOKEN_HEADER, token);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            check(response.statusCode(), response::body);
            return response;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted", e));
        }
    }

    /** 410: Job abgebrochen/beendet; sonst 4xx: fachlich abgelehnt (mit der Begruendung des Servers); 5xx: voruebergehend. */
    private static void check(int status, java.util.function.Supplier<String> body) throws IOException {
        if (status == 410) {
            throw new JobGoneException("HTTP 410: " + reason(body.get()));
        }
        if (status >= 400 && status < 500) {
            throw new PlatformRefusedException("HTTP " + status + ": " + reason(body.get()));
        }
        if (status >= 500) {
            throw new IOException("platform-api antwortete mit HTTP " + status);
        }
    }

    private static String reason(String body) {
        if (body == null) {
            return "";
        }
        var matcher = java.util.regex.Pattern.compile("\"(?:message|detail)\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : body.length() > 200 ? body.substring(0, 200) : body;
    }

    private <T> T parse(HttpResponse<String> response, TypeReference<T> type) {
        try {
            return json.readValue(response.body(), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record NoteText(String noteId, String path, String text) { }
    record NoteRef(String noteId, String path) { }
    record ServiceRef(String id) { }
}
