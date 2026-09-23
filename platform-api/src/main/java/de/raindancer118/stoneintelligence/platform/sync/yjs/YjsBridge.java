package de.raindancer118.stoneintelligence.platform.sync.yjs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/**
 * Notiztext lesen und aendern, ohne Yjs in Java nachzubauen (ADR 0008): das Bundle aus
 * {@code yjs-bridge/} (echte Yjs-Bibliothek + die Diff-Logik des Plugins) laeuft in einem
 * GraalJS-Context. Das Skript hat keinerlei Zugriff auf Java oder das Dateisystem; einzige
 * Verbindung nach aussen ist eine Zufallsquelle fuer Yjs-Client-IDs.
 *
 * <p>Ein Context ist nicht threadsicher - Aufrufe werden serialisiert. Einzelne Operationen
 * dauern Millisekunden; das Laden des Bundles (~2 s) passiert einmal beim Start.
 */
public final class YjsBridge implements AutoCloseable {

    private static final String BUNDLE = "/yjs/yjs-bridge.js";

    private final Context context;
    private final Value bridge;
    private final Value toArray;

    private YjsBridge(Context context, Value bridge) {
        this.context = context;
        this.bridge = bridge;
        this.toArray = context.eval("js", "(...items) => items");
    }

    public static YjsBridge load() {
        String source;
        try (var in = YjsBridge.class.getResourceAsStream(BUNDLE)) {
            if (in == null) {
                throw new IllegalStateException("Yjs-Bundle fehlt: " + BUNDLE + " (yjs-bridge: npm run build)");
            }
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var context = Context.newBuilder("js")
            .allowHostAccess(HostAccess.NONE)
            .allowIO(org.graalvm.polyglot.io.IOAccess.NONE)
            .option("engine.WarnInterpreterOnly", "false")
            .build();
        var random = new SecureRandom();
        context.getBindings("js").putMember("__secureRandomInt", (ProxyExecutable) args -> random.nextInt());
        context.eval("js", "globalThis.crypto = { getRandomValues(a) { for (let i = 0; i < a.length; i++) a[i] = __secureRandomInt(); return a; } };");
        context.eval(Source.create("js", source));
        return new YjsBridge(context, context.getBindings("js").getMember("yjsBridge"));
    }

    /** Text der Notiz aus ihrer Update-Historie (Reihenfolge wie gespeichert). */
    public synchronized String textOf(List<byte[]> updates) {
        return bridge.invokeMember("textOf", encoded(updates)).asString();
    }

    /** Minimales Yjs-Update von der Historie zum Zieltext, leer wenn sich nichts aendert. */
    public synchronized Optional<byte[]> change(List<byte[]> updates, String nextText) {
        var result = bridge.invokeMember("change", encoded(updates), nextText);
        return result.isNull() ? Optional.empty() : Optional.of(Base64.getDecoder().decode(result.asString()));
    }

    private Value encoded(List<byte[]> updates) {
        return toArray.execute(updates.stream().map(update -> Base64.getEncoder().encodeToString(update)).toArray());
    }

    @Override
    public synchronized void close() {
        context.close();
    }
}
