package com.rinko.openradio.spike;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 16bit 单声道 WAV 写出。只给 spike 用 —— 正式版不需要把音频落盘。
 *
 * <p>手写 44 字节头而不是引第三方库：这是个一次性的验证工具，
 * 多一个依赖不值得，而 WAV 头是固定结构，没有出错空间。
 */
public final class WavWriter {

    public static void write(Path file, short[] samples, int sampleRate) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        int dataSize = samples.length * 2;
        // little endian：WAV 规定如此，与 JVM 字节序无关，所以必须显式指定。
        ByteBuffer data = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            data.putShort(sample);
        }

        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
            writeInt(out, 0x46464952);          // "RIFF"
            writeInt(out, 36 + dataSize);
            writeInt(out, 0x45564157);          // "WAVE"
            writeInt(out, 0x20746D66);          // "fmt "
            writeInt(out, 16);                  // fmt chunk 大小
            writeShort(out, 1);                 // PCM
            writeShort(out, 1);                 // 单声道
            writeInt(out, sampleRate);
            writeInt(out, sampleRate * 2);      // 字节率 = 采样率 × 声道 × 位深/8
            writeShort(out, 2);                 // 块对齐
            writeShort(out, 16);                // 位深
            writeInt(out, 0x61746164);          // "data"
            writeInt(out, dataSize);
            out.write(data.array());
        }
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private WavWriter() {
    }
}
