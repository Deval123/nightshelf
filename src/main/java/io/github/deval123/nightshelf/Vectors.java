package io.github.deval123.nightshelf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Float32 vectors as BLOBs, and cosine similarity. */
final class Vectors {

    private Vectors() {
    }

    static byte[] toBlob(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    static float[] fromBlob(byte[] blob) {
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[blob.length / Float.BYTES];
        for (int i = 0; i < v.length; i++) {
            v[i] = buf.getFloat();
        }
        return v;
    }

    static boolean allFinite(float[] v) {
        for (float f : v) {
            if (!Float.isFinite(f)) {
                return false;
            }
        }
        return true;
    }

    /** Cosine similarity; 0 when either vector is all zeros or the lengths differ. */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
}
