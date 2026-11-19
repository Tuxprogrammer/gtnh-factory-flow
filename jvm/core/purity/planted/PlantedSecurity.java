public final class PlantedSecurity {

    static byte[] md5(final byte[] b) throws Exception {
        return java.security.MessageDigest.getInstance("MD5").digest(b); // expect: security
    }
}
