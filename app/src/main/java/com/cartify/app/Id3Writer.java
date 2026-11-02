package com.cartify.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes an ID3v2.3 tag (title / artist / album / artwork) in front of an MP3.
 *
 * Android has no tag writer in the platform and Cartify must not depend on
 * third-party libraries, so the few frames we need are encoded by hand.
 */
public final class Id3Writer {

    private Id3Writer() {
    }

    public static void tag(File mp3, String title, String artist, String album, byte[] artwork) throws IOException {
        byte[] audio = readAll(mp3);
        int offset = skipExistingId3(audio);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeTextFrame(body, "TIT2", title);
        writeTextFrame(body, "TPE1", artist);
        if (album != null && !album.isEmpty()) writeTextFrame(body, "TALB", album);
        if (artwork != null && artwork.length > 0) writePictureFrame(body, artwork);

        byte[] frames = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(frames.length + 10 + audio.length);

        out.write('I');
        out.write('D');
        out.write('3');
        out.write(3);          // v2.3
        out.write(0);          // revision
        out.write(0);          // flags
        int size = frames.length;
        out.write((size >> 21) & 0x7F);
        out.write((size >> 14) & 0x7F);
        out.write((size >> 7) & 0x7F);
        out.write(size & 0x7F);
        out.write(frames);
        out.write(audio, offset, audio.length - offset);

        try (FileOutputStream fos = new FileOutputStream(mp3)) {
            fos.write(out.toByteArray());
        }
    }

    private static int skipExistingId3(byte[] data) {
        if (data.length < 10 || data[0] != 'I' || data[1] != 'D' || data[2] != '3') return 0;
        int size = ((data[6] & 0x7F) << 21) | ((data[7] & 0x7F) << 14)
                | ((data[8] & 0x7F) << 7) | (data[9] & 0x7F);
        return Math.min(data.length, 10 + size);
    }

    private static void writeTextFrame(ByteArrayOutputStream out, String id, String value) throws IOException {
        if (value == null) value = "";
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        out.write(id.getBytes(StandardCharsets.ISO_8859_1));
        int size = text.length + 1;
        out.write((size >> 24) & 0xFF);
        out.write((size >> 16) & 0xFF);
        out.write((size >> 8) & 0xFF);
        out.write(size & 0xFF);
        out.write(0);
        out.write(0);          // flags
        out.write(3);          // UTF-8
        out.write(text);
    }

    private static void writePictureFrame(ByteArrayOutputStream out, byte[] image) throws IOException {
        String mime = isPng(image) ? "image/png" : "image/jpeg";
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0);                                    // text encoding
        frame.write(mime.getBytes(StandardCharsets.ISO_8859_1));
        frame.write(0);
        frame.write(3);                                    // front cover
        frame.write(0);                                    // empty description (single terminator)
        frame.write(image);

        byte[] payload = frame.toByteArray();
        out.write("APIC".getBytes(StandardCharsets.ISO_8859_1));
        out.write((payload.length >> 24) & 0xFF);
        out.write((payload.length >> 16) & 0xFF);
        out.write((payload.length >> 8) & 0xFF);
        out.write(payload.length & 0xFF);
        out.write(0);
        out.write(0);
        out.write(payload);
    }

    private static boolean isPng(byte[] b) {
        return b.length > 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(16, f.length()));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
