package de.tstieh.stoneintelligence.platform.sync.yjs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/**
 * Notiztext lesen und aendern, ohne Yjs in Java nachzubauen (ADR 0008): das Bundle aus
 * {@code yjs-bridge/} (echte Yjs-Bibliothek + die Diff-Logik des Plugins) laeuft in GraalJS.
 * Das Skript hat keinerlei Zugriff auf Java oder das Dateisystem; einzige Verbindung nach aussen
 * ist eine Zufallsquelle fuer Yjs-Client-IDs.
 *
 * <p>Ein GraalJS-Context ist nicht threadsicher. Statt alle Aufrufe auf einen zu serialisieren
 * (Web-Speichern, KI-Schreiben, Versionen und Beitreten warteten aufeinander) haelt die Bridge
 * bis zu {@code size} Contexts (ADR 0013, Schritt 6); weitere entstehen erst bei Bedarf, weil das
 * Laden des Bundles je Context rund 2 s dauert.
 */
public final class YjsBridge implements AutoCloseable {

    private static final String BUNDLE = "/yjs/yjs-bridge.js";

    private record Engine(Context context, Value bridge, Value toArray) { }

    private final String source;
    private final int size;
    private final BlockingQueue<Engine> idle = new LinkedBlockingQueue<>();
    private final List<Engine> all = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    private YjsBridge(String source, int size) {
        this.source = source;
        this.size = size;
    }

    public static YjsBridge load() {
        return load(1);
    }

    /** {@code size}: hoechstens so viele Aufrufe laufen gleichzeitig. */
    public static YjsBridge load(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size muss mindestens 1 sein");
        }
        String source;
        try (var in = YjsBridge.class.getResourceAsStream(BUNDLE)) {
            if (in == null) {
                throw new IllegalStateException("Yjs-Bundle fehlt: " + BUNDLE + " (yjs-bridge: npm run build)");
            }
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var bridge = new YjsBridge(source, size);
        // Der erste Context sofort: ein kaputtes Bundle soll beim Start auffallen, nicht beim ersten Speichern.
        bridge.idle.add(bridge.createEngine());
        return bridge;
    }

    private Engine createEngine() {
        var context = Context.newBuilder("js")
            .allowHostAccess(HostAccess.NONE)
            .allowIO(org.graalvm.polyglot.io.IOAccess.NONE)
            .option("engine.WarnInterpreterOnly", "false")
            .build();
        var random = new SecureRandom();
        context.getBindings("js").putMember("__secureRandomInt", (ProxyExecutable) args -> random.nextInt());
        context.eval("js", "globalThis.crypto = { getRandomValues(a) { for (let i = 0; i < a.length; i++) a[i] = __secureRandomInt(); return a; } };");
        context.eval(Source.create("js", source));
        var engine = new Engine(context, context.getBindings("js").getMember("yjsBridge"),
            context.eval("js", "(...items) => items"));
        all.add(engine);
        return engine;
    }

    /** Text der Notiz aus ihrer Update-Historie (Reihenfolge wie gespeichert). */
    public String textOf(List<byte[]> updates) {
        return call(engine -> engine.bridge().invokeMember("textOf", encoded(engine, updates)).asString());
    }

    /** Minimales Yjs-Update von der Historie zum Zieltext, leer wenn sich nichts aendert. */
    public Optional<byte[]> change(List<byte[]> updates, String nextText) {
        return call(engine -> {
            var result = engine.bridge().invokeMember("change", encoded(engine, updates), nextText);
            return result.isNull() ? Optional.<byte[]>empty() : Optional.of(Base64.getDecoder().decode(result.asString()));
        });
    }

    /** Die Historie als ein einziges Update ({@code Y.mergeUpdates}), gleichwertig zur ganzen Folge. */
    public byte[] merge(List<byte[]> updates) {
        return call(engine -> Base64.getDecoder().decode(
            engine.bridge().invokeMember("merge", encoded(engine, updates)).asString()));
    }

    /** Wie viele Contexts bisher entstanden sind. */
    public int contexts() {
        return all.size();
    }

    private <T> T call(java.util.function.Function<Engine, T> work) {
        var engine = borrow();
        try {
            return work.apply(engine);
        } finally {
            idle.add(engine);
        }
    }

    private Engine borrow() {
        if (closed) {
            throw new IllegalStateException("YjsBridge ist geschlossen");
        }
        var engine = idle.poll();
        if (engine != null) {
            return engine;
        }
        synchronized (this) {
            if (all.size() < size) {
                return createEngine();
            }
        }
        try {
            return idle.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Warten auf die Yjs-Bridge unterbrochen", e);
        }
    }

    private static Value encoded(Engine engine, List<byte[]> updates) {
        return engine.toArray().execute(updates.stream().map(update -> Base64.getEncoder().encodeToString(update)).toArray());
    }

    @Override
    public synchronized void close() {
        closed = true;
        all.forEach(engine -> engine.context().close(true));
    }
}
