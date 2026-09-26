package de.tstieh.stoneintelligence.worker.embed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Embeddings auf dem eigenen Server (ADR 0012): {@code multilingual-e5-small} per ONNX Runtime, der
 * Text verlaesst den Worker nie. Mittelwert ueber die Token (gewichtet mit der Attention-Maske),
 * dann auf Laenge 1 normiert - wie das Modell es vorsieht. Laengere Texte als das Modellfenster
 * (512 Token) werden abgeschnitten; Notizen werden vorher in Abschnitte geteilt.
 */
public final class LocalEmbedder implements Embedder, AutoCloseable {

    /** Gepinnte Fassung von {@code Xenova/multilingual-e5-small} (Basis {@code intfloat/multilingual-e5-small}, MIT). */
    public static final String MODEL_ID = "multilingual-e5-small@761b726";
    public static final String MODEL_FILE = "model_quantized.onnx";
    public static final String TOKENIZER_FILE = "tokenizer.json";
    public static final int DIMENSIONS = 384;
    static final int MAX_TOKENS = 512;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean needsTypeIds;

    private LocalEmbedder(OrtEnvironment environment, OrtSession session, HuggingFaceTokenizer tokenizer) {
        this.environment = environment;
        this.session = session;
        this.tokenizer = tokenizer;
        this.needsTypeIds = session.getInputNames().contains("token_type_ids");
    }

    /** {@code STONEAI_EMBEDDING_MODEL_DIR}, sonst {@code ~/.cache/stoneintelligence/multilingual-e5-small}. */
    public static Path modelDir(Map<String, String> env) {
        var configured = env.get("STONEAI_EMBEDDING_MODEL_DIR");
        return configured == null || configured.isBlank()
            ? Path.of(System.getProperty("user.home"), ".cache", "stoneintelligence", "multilingual-e5-small")
            : Path.of(configured);
    }

    public static LocalEmbedder load(Path dir) {
        try {
            var environment = OrtEnvironment.getEnvironment();
            var options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            var session = environment.createSession(dir.resolve(MODEL_FILE).toString(), options);
            var tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(dir.resolve(TOKENIZER_FILE))
                .optMaxLength(MAX_TOKENS)
                .optTruncation(true)
                .optPadding(false)
                .build();
            return new LocalEmbedder(environment, session, tokenizer);
        } catch (OrtException e) {
            throw new IllegalStateException("Embedding-Modell unter " + dir + " nicht ladbar", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized float[][] embed(List<String> texts, Kind kind) {
        var vectors = new float[texts.size()][];
        var prefix = kind == Kind.QUERY ? "query: " : "passage: ";
        for (var i = 0; i < texts.size(); i++) {
            vectors[i] = embedOne(prefix + (texts.get(i) == null ? "" : texts.get(i)));
        }
        return vectors;
    }

    private float[] embedOne(String text) {
        var encoding = tokenizer.encode(text);
        var ids = encoding.getIds();
        var mask = encoding.getAttentionMask();
        long[] shape = {1, ids.length};
        try (var idTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape);
             var maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape);
             var typeTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[ids.length]), shape)) {
            var inputs = needsTypeIds
                ? Map.of("input_ids", idTensor, "attention_mask", maskTensor, "token_type_ids", typeTensor)
                : Map.of("input_ids", idTensor, "attention_mask", maskTensor);
            try (var result = session.run(inputs)) {
                var hidden = ((float[][][]) result.get(0).getValue())[0];
                return normalised(meanPool(hidden, mask));
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Embedding fehlgeschlagen", e);
        }
    }

    static float[] meanPool(float[][] hidden, long[] mask) {
        var pooled = new float[hidden[0].length];
        var count = 0;
        for (var token = 0; token < hidden.length; token++) {
            if (mask[token] == 0) {
                continue;
            }
            count++;
            for (var d = 0; d < pooled.length; d++) {
                pooled[d] += hidden[token][d];
            }
        }
        for (var d = 0; d < pooled.length && count > 0; d++) {
            pooled[d] /= count;
        }
        return pooled;
    }

    static float[] normalised(float[] vector) {
        var length = 0.0;
        for (var value : vector) {
            length += value * value;
        }
        length = Math.sqrt(length);
        if (length == 0) {
            return vector;
        }
        for (var d = 0; d < vector.length; d++) {
            vector[d] /= (float) length;
        }
        return vector;
    }

    @Override
    public String model() {
        return MODEL_ID;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException(e);
        }
        tokenizer.close();
    }
}
