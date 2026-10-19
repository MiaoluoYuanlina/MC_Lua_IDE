package wiki.ica.xiaomiao.mcluaide.client;

/** Internal bridge; call only on Minecraft's sound executor. */
public interface OggPreviewChannel {
    void mcLuaIde$trackPreview();
    double mcLuaIde$previewSeconds();

    static double bufferSeconds(int bytes, int bits, int channels, int frequency) {
        return bytes > 0 && bits > 0 && channels > 0 && frequency > 0
                ? bytes * 8.0 / bits / channels / frequency : 0;
    }
}
