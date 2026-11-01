import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds src/main/resources/assets/logos/voice/speech_embedding.bin from the ONNX model:
 *
 * <pre>
 * curl -L -o speech-embedding.onnx https://raw.githubusercontent.com/st-matskevich/local-wake/main/lwake/models/speech-embedding.onnx
 * java tools/voice/ExportSpeechEmbedding.java speech-embedding.onnx speech_embedding.bin
 * </pre>
 *
 * The input used for the committed file has SHA-256
 * 0140c2b1455c016783f9b859e16f47b5187c69079503cd053f406b86f9094560. Output: "LSE2", then each
 * tensor as a little-endian count, a bit width (16 or 32) and its values, in the order
 * SpeechEmbedding reads them. The 2×257×512 DFT matrix is left out (the game computes an FFT); the
 * check below confirms it is a plain DFT before dropping it. A protobuf reader is written out here
 * so the tool needs nothing but a JDK.
 */
public class ExportSpeechEmbedding {
    private static byte[] b;
    private static int p;
    private static final Map<String, float[]> TENSORS = new HashMap<>();

    public static void main(String[] args) throws Exception {
        b = Files.readAllBytes(Path.of(args[0]));
        walk(0, b.length, (f, w, s, e, v) -> { if (f == 7) graph(s, e); });

        String m = "embeddings_apply_default/MudpuppyLite/";
        List<String> order = new ArrayList<>(List.of("map/while/stft/hann_window/sub_2:0", "map/while/linear_to_mel_weight_matrix:0",
                m + "ZeroMeanConv2d/sub:0", m + "BatchNorm/ReadVariableOp:0", m + "BatchNorm/ReadVariableOp_1:0",
                m + "BatchNorm/FusedBatchNormV3/ReadVariableOp:0", m + "BatchNorm/FusedBatchNormV3/ReadVariableOp_1:0"));
        for (int i = 0; i <= 17; i++) {
            String c = i == 0 ? "Conv" : "Conv_" + i;
            order.add(m + c + "/Conv2D_weights_fused_bn");
            order.add(m + c + "/Conv2D_bias_fused_bn");
        }
        order.add(m + "Conv_18/Conv2D/ReadVariableOp:0");

        float[] dft = TENSORS.get("cst_rfft_512__100");
        double err = 0;
        for (int k = 0; k < 257; k++) {
            for (int n = 0; n < 512; n++) {
                double a = 2 * Math.PI * k * n / 512;
                err = Math.max(err, Math.abs(dft[k * 512 + n] - Math.cos(a)));
                err = Math.max(err, Math.abs(dft[257 * 512 + k * 512 + n] + Math.sin(a)));
            }
        }
        if (err > 1e-5) throw new IllegalStateException("the model's rfft matrix is not a plain DFT (error " + err + ")");

        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[1]))))) {
            out.write("LSE2".getBytes(StandardCharsets.US_ASCII));
            for (String name : order) {
                float[] t = TENSORS.get(name);
                if (t == null) throw new IllegalStateException("missing tensor " + name);
                // Convolution kernels are 97% of the weights and lose nothing at float16. The mel
                // matrix stays float32: near-silent bands are logged, so its small errors would grow.
                boolean half = name.endsWith("Conv2D_weights_fused_bn") || name.endsWith("Conv2D/ReadVariableOp:0");
                ByteBuffer bb = ByteBuffer.allocate(5 + t.length * (half ? 2 : 4)).order(ByteOrder.LITTLE_ENDIAN).putInt(t.length).put((byte) (half ? 16 : 32));
                for (float v : t) {
                    if (half) bb.putShort(Float.floatToFloat16(v));
                    else bb.putFloat(v);
                }
                out.write(bb.array());
            }
        }
    }

    interface Field {
        void on(int field, int wireType, int start, int end, long value);
    }

    private static long varint() {
        long r = 0;
        for (int s = 0; ; s += 7) {
            int x = b[p++] & 0xff;
            r |= (long) (x & 0x7f) << s;
            if (x < 0x80) return r;
        }
    }

    private static void walk(int start, int end, Field f) {
        int save = p;
        p = start;
        while (p < end) {
            long key = varint();
            int field = (int) (key >>> 3), wt = (int) (key & 7);
            if (wt == 0) f.on(field, 0, 0, 0, varint());
            else if (wt == 2) {
                int len = (int) varint(), s = p;
                p += len;
                f.on(field, 2, s, s + len, 0);
                p = s + len;
            } else if (wt == 1) p += 8;
            else if (wt == 5) p += 4;
        }
        p = save;
    }

    /** GraphProto: initializers (5) and nodes (1), descending into node attributes that hold subgraphs (Loop bodies). */
    private static void graph(int s, int e) {
        walk(s, e, (f, w, a, z, v) -> {
            if (f == 5) tensor(a, z);
            if (f == 1) walk(a, z, (f2, w2, a2, z2, v2) -> {
                if (f2 == 5) walk(a2, z2, (f3, w3, a3, z3, v3) -> { if (f3 == 6) graph(a3, z3); });
            });
        });
    }

    /** TensorProto: keeps float tensors (data type 1) by name, from raw_data (9) or float_data (4). */
    private static void tensor(int s, int e) {
        int[] type = {0}, raw = {0, 0}, packed = {0, 0};
        String[] name = {""};
        walk(s, e, (f, w, a, z, v) -> {
            if (f == 2) type[0] = (int) v;
            if (f == 8) name[0] = new String(b, a, z - a, StandardCharsets.UTF_8);
            if (f == 9) { raw[0] = a; raw[1] = z; }
            if (f == 4 && w == 2) { packed[0] = a; packed[1] = z; }
        });
        if (type[0] != 1) return;
        int[] r = raw[1] > raw[0] ? raw : packed;
        FloatBuffer fb = ByteBuffer.wrap(b, r[0], r[1] - r[0]).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] t = new float[fb.remaining()];
        fb.get(t);
        TENSORS.put(name[0], t);
    }
}
