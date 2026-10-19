package wiki.ica.xiaomiao.mcluaide.client;

import net.minecraft.client.sounds.JOrbisAudioStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads page positions and bounded Vorbis comments, without decoding the whole audio file. */
record LuaOggMetadata(int channels, int sampleRate, double duration, int nominalBitrate,
                      String encoder, List<String> comments) {
    static LuaOggMetadata read(byte[] bytes) throws IOException {
        int channels, rate;
        try (JOrbisAudioStream stream = new JOrbisAudioStream(new ByteArrayInputStream(bytes))) {
            channels = stream.getFormat().getChannels();
            rate = Math.round(stream.getFormat().getSampleRate());
        }
        long serial = -1, lastSample = -1;
        boolean ended = false;
        int packetNumber = 0, nominal = 0;
        String encoder = "";
        List<String> comments = new ArrayList<>();
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        for (int offset = 0; offset < bytes.length;) {
            if (bytes.length - offset < 27 || bytes[offset] != 'O' || bytes[offset + 1] != 'g'
                    || bytes[offset + 2] != 'g' || bytes[offset + 3] != 'S' || bytes[offset + 4] != 0)
                throw new IOException("OGG 页面头损坏或文件不完整");
            int segments = bytes[offset + 26] & 255;
            int body = offset + 27 + segments;
            if (body > bytes.length) throw new IOException("OGG 分段表不完整");
            int end = body;
            for (int i = 0; i < segments; i++) end += bytes[offset + 27 + i] & 255;
            if (end > bytes.length) throw new IOException("OGG 页面数据不完整");
            long pageSerial = Integer.toUnsignedLong(integer(bytes, offset + 14));
            if (serial == -1) serial = pageSerial; // Match the stream Minecraft's decoder opens.
            if (pageSerial == serial) {
                long granule = longInteger(bytes, offset + 6);
                if (granule >= 0) lastSample = Math.max(lastSample, granule);
                ended |= (bytes[offset + 5] & 4) != 0;
                for (int i = 0, cursor = body; i < segments; i++) {
                    int length = bytes[offset + 27 + i] & 255;
                    if (packetNumber < 2) {
                        if (packet.size() + length > 1024 * 1024)
                            throw new IOException("OGG 元数据超过 1 MiB");
                        packet.write(bytes, cursor, length);
                    }
                    cursor += length;
                    if (length < 255) {
                        byte[] value = packet.toByteArray();
                        if (packetNumber == 0 && signature(value, 1) && value.length >= 30)
                            nominal = Math.max(0, integer(value, 20));
                        else if (packetNumber == 1 && signature(value, 3)) {
                            int vendorLength = integer(value, 7);
                            int vendorEnd = checkedEnd(value, 11, vendorLength);
                            encoder = text(value, 11, vendorLength);
                            int count = integer(value, vendorEnd);
                            int position = vendorEnd + 4;
                            if (count < 0 || count > 65536) throw new IOException("OGG 注释数量无效");
                            for (int c = 0; c < count; c++) {
                                int size = integer(value, position);
                                position += 4;
                                int next = checkedEnd(value, position, size);
                                if (comments.size() < 128) comments.add(text(value, position, size));
                                position = next;
                            }
                        }
                        packet.reset();
                        packetNumber++;
                    }
                }
            }
            offset = end;
        }
        return new LuaOggMetadata(channels, rate, ended && lastSample >= 0 && rate > 0
                ? lastSample / (double) rate : -1, nominal, encoder, List.copyOf(comments));
    }

    double averageBitrate(int fileSize) { return duration > 0 ? fileSize * 8.0 / duration : -1; }

    static String time(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) return "未知";
        long tenths = (long) (seconds * 10 + 0.00001);
        return tenths >= 36000
                ? String.format(Locale.ROOT, "%d:%02d:%02d.%d", tenths / 36000, tenths / 600 % 60, tenths / 10 % 60, tenths % 10)
                : String.format(Locale.ROOT, "%02d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10);
    }

    private static boolean signature(byte[] bytes, int type) {
        return bytes.length >= 7 && bytes[0] == type
                && new String(bytes, 1, 6, StandardCharsets.US_ASCII).equals("vorbis");
    }
    private static int integer(byte[] bytes, int offset) throws IOException {
        checkedEnd(bytes, offset, 4);
        return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8
                | (bytes[offset + 2] & 255) << 16 | bytes[offset + 3] << 24;
    }
    private static long longInteger(byte[] bytes, int offset) throws IOException {
        return Integer.toUnsignedLong(integer(bytes, offset)) | (long) integer(bytes, offset + 4) << 32;
    }
    private static int checkedEnd(byte[] bytes, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > bytes.length || length > bytes.length - offset)
            throw new IOException("OGG 元数据长度无效");
        return offset + length;
    }
    private static String text(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, Math.min(length, 2048), StandardCharsets.UTF_8)
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ");
    }
}
