public final class PlantedZip {

    static long crc(final byte[] b) {
        final java.util.zip.CRC32 c = new java.util.zip.CRC32(); // expect: zip
        c.update(b); // expect: zip
        return c.getValue(); // expect: zip
    }
}
