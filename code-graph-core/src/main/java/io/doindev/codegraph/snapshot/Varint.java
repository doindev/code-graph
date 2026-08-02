package io.doindev.codegraph.snapshot;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** LEB128-style unsigned varint encoding for non-negative values. */
final class Varint {

    private Varint() {
    }

    static void write(DataOutputStream out, int value) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("varint must be >= 0: " + value);
        }
        writeLong(out, value);
    }

    static void writeLong(DataOutputStream out, long value) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("varint must be >= 0: " + value);
        }
        while ((value & ~0x7FL) != 0) {
            out.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.writeByte((int) value);
    }

    static int read(DataInputStream in) throws IOException {
        long value = readLong(in);
        if (value > Integer.MAX_VALUE) {
            throw new IOException("varint too large for int: " + value);
        }
        return (int) value;
    }

    static long readLong(DataInputStream in) throws IOException {
        long value = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 63) {
                throw new IOException("malformed varint");
            }
        }
    }
}
