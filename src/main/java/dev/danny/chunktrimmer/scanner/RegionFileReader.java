package dev.danny.chunktrimmer.scanner;

import net.querz.nbt.io.NBTDeserializer;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;

import java.io.*;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads Minecraft region files (.mca) and provides raw NBT CompoundTag
 * data for each chunk. Bypasses Querz NBT's MCAFile/Chunk classes to
 * avoid 1.18+ format compatibility issues.
 *
 * Region file format:
 * - Bytes 0-4095: Location table (1024 entries × 4 bytes)
 * - Bytes 4096-8191: Timestamp table (1024 entries × 4 bytes)
 * - Bytes 8192+: Chunk data sectors (each sector = 4096 bytes)
 *
 * Each location entry: 3 bytes offset (in sectors from file start) + 1 byte sector count.
 * Each chunk data block: 4 bytes length + 1 byte compression type + compressed NBT data.
 */
public class RegionFileReader {

    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SECTORS = 2; // location table + timestamp table
    private static final int CHUNKS_PER_REGION = 32;

    private static final int COMPRESSION_GZIP = 1;
    private static final int COMPRESSION_ZLIB = 2;
    private static final int COMPRESSION_NONE = 3;

    /**
     * Parses region coordinates from a filename like "r.5.-3.mca".
     * Returns [regionX, regionZ] or null if the filename doesn't match.
     */
    public static int[] parseRegionCoords(String filename) {
        if (!filename.startsWith("r.") || !filename.endsWith(".mca")) return null;
        String[] parts = filename.substring(2, filename.length() - 4).split("\\.");
        if (parts.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads all chunks from a region file, calling the consumer for each
     * chunk that exists with its absolute chunk coordinates and raw NBT data.
     *
     * @param regionFile path to the .mca file
     * @param regionX    region X coordinate (from filename)
     * @param regionZ    region Z coordinate (from filename)
     * @param consumer   called with (absoluteChunkX, absoluteChunkZ, chunkNBT) for each chunk
     */
    public static void readRegion(Path regionFile, int regionX, int regionZ,
                                  ChunkConsumer consumer) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(regionFile.toFile(), "r")) {
            long fileLength = raf.length();
            if (fileLength < SECTOR_SIZE * HEADER_SECTORS) return; // too small, no chunks

            // Read the location table (first 4096 bytes)
            byte[] locationTable = new byte[SECTOR_SIZE];
            raf.readFully(locationTable);

            for (int localZ = 0; localZ < CHUNKS_PER_REGION; localZ++) {
                for (int localX = 0; localX < CHUNKS_PER_REGION; localX++) {
                    int index = (localZ * CHUNKS_PER_REGION + localX) * 4;

                    // 3 bytes offset (big-endian), 1 byte sector count
                    int offset = ((locationTable[index] & 0xFF) << 16)
                            | ((locationTable[index + 1] & 0xFF) << 8)
                            | (locationTable[index + 2] & 0xFF);
                    int sectorCount = locationTable[index + 3] & 0xFF;

                    if (offset == 0 || sectorCount == 0) continue; // chunk doesn't exist

                    long byteOffset = (long) offset * SECTOR_SIZE;
                    if (byteOffset + 5 > fileLength) continue; // offset beyond file

                    int absChunkX = regionX * CHUNKS_PER_REGION + localX;
                    int absChunkZ = regionZ * CHUNKS_PER_REGION + localZ;

                    try {
                        CompoundTag chunkData = readChunkData(raf, byteOffset);
                        if (chunkData != null) {
                            consumer.accept(absChunkX, absChunkZ, chunkData);
                        }
                    } catch (Exception e) {
                        // Skip corrupt or in-progress chunks
                        System.err.println("Warning: Failed to read chunk (" +
                                absChunkX + ", " + absChunkZ + "): " + e.getMessage());
                    }
                }
            }
        }
    }

    private static CompoundTag readChunkData(RandomAccessFile raf, long byteOffset) throws IOException {
        raf.seek(byteOffset);

        int dataLength = raf.readInt();
        if (dataLength <= 1) return null; // empty chunk

        int compressionType = raf.readByte() & 0xFF;
        int compressedLength = dataLength - 1; // subtract the compression type byte

        byte[] compressedData = new byte[compressedLength];
        raf.readFully(compressedData);

        InputStream decompressed = switch (compressionType) {
            case COMPRESSION_GZIP -> new GZIPInputStream(new ByteArrayInputStream(compressedData));
            case COMPRESSION_ZLIB -> new InflaterInputStream(new ByteArrayInputStream(compressedData));
            case COMPRESSION_NONE -> new ByteArrayInputStream(compressedData);
            default -> throw new IOException("Unknown compression type: " + compressionType);
        };

        NBTDeserializer deserializer = new NBTDeserializer(false); // false = not compressed (we already decompressed)
        NamedTag namedTag = deserializer.fromStream(new BufferedInputStream(decompressed));
        return (CompoundTag) namedTag.getTag();
    }

    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(int chunkX, int chunkZ, CompoundTag data);
    }
}
