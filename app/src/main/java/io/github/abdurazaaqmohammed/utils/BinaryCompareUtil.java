package io.github.abdurazaaqmohammed.utils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Byte-level diff of two files of any kind.
 *
 * <p>The text comparison only makes sense for text; a byte diff also answers the questions that
 * come up with binaries: are these two files identical apart from padding, where does the content
 * start to diverge, and how far apart are they overall.
 */
public final class BinaryCompareUtil {

    /** How many differing runs are reported before the output is cut off. */
    private static final int MAX_RUNS = 200;
    /** Bytes of context shown either side of a differing run. */
    private static final int CONTEXT = 8;

    public static final class Run {
        public final long offset;
        public final int length;
        public final String left;
        public final String right;

        Run(long offset, int length, String left, String right) {
            this.offset = offset;
            this.length = length;
            this.left = left;
            this.right = right;
        }
    }

    public static final class Result {
        public final long sizeLeft;
        public final long sizeRight;
        public final long firstDifference;
        public final long differingBytes;
        public final List<Run> runs;
        public final boolean truncated;

        Result(long sizeLeft, long sizeRight, long firstDifference, long differingBytes,
               List<Run> runs, boolean truncated) {
            this.sizeLeft = sizeLeft;
            this.sizeRight = sizeRight;
            this.firstDifference = firstDifference;
            this.differingBytes = differingBytes;
            this.runs = runs;
            this.truncated = truncated;
        }

        public boolean identical() {
            return differingBytes == 0 && sizeLeft == sizeRight;
        }
    }

    private BinaryCompareUtil() {
    }

    public static Result compare(File left, File right) throws IOException {
        long sizeLeft = left.length();
        long sizeRight = right.length();
        long shared = Math.min(sizeLeft, sizeRight);
        List<Run> runs = new ArrayList<>();
        long differing = 0;
        long first = -1;
        boolean truncated = false;

        try (RandomAccessFile a = new RandomAccessFile(left, "r");
             RandomAccessFile b = new RandomAccessFile(right, "r")) {
            final int chunk = 1 << 16;
            byte[] bufA = new byte[chunk];
            byte[] bufB = new byte[chunk];
            long offset = 0;
            while (offset < shared) {
                int want = (int) Math.min(chunk, shared - offset);
                a.readFully(bufA, 0, want);
                b.readFully(bufB, 0, want);
                int i = 0;
                while (i < want) {
                    if (bufA[i] == bufB[i]) {
                        i++;
                        continue;
                    }
                    int start = i;
                    while (i < want && bufA[i] != bufB[i]) i++;
                    int length = i - start;
                    long at = offset + start;
                    if (first < 0) first = at;
                    differing += length;
                    if (runs.size() < MAX_RUNS) {
                        int from = (int) Math.max(0, at - CONTEXT);
                        int to = (int) Math.min(want, start + length + CONTEXT);
                        runs.add(new Run(at, length,
                                hex(slice(bufA, from, to)),
                                hex(slice(bufB, from, to))));
                    } else {
                        truncated = true;
                    }
                }
                offset += want;
            }
        }
        if (first < 0 && sizeLeft != sizeRight) first = shared;
        return new Result(sizeLeft, sizeRight, first, differing, runs, truncated);
    }

    private static byte[] slice(byte[] source, int from, int to) {
        byte[] result = new byte[to - from];
        System.arraycopy(source, from, result, 0, to - from);
        return result;
    }

    /** Renders bytes as hex plus the printable ASCII alongside. */
    static String hex(byte[] data) {
        StringBuilder hexPart = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        for (byte b : data) {
            int value = b & 0xff;
            hexPart.append(String.format("%02x ", value));
            ascii.append(value >= 0x20 && value < 0x7f ? (char) value : '.');
        }
        return hexPart.toString().trim() + "   |" + ascii + "|";
    }
}