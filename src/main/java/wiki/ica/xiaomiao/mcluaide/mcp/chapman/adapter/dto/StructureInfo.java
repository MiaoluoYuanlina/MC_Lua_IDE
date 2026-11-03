package wiki.ica.xiaomiao.mcluaide.mcp.chapman.adapter.dto;

/** Metadata about a saved structure template. */
public record StructureInfo(
        String name,
        int sizeX,
        int sizeY,
        int sizeZ,
        long fileSizeBytes,
        boolean onDisk,
        boolean inMemory) {}
