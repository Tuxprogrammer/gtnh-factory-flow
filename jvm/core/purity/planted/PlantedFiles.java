public final class PlantedFiles {

    static Object path(final String s) {
        return java.nio.file.Path.of(s); // expect: filesystem
    }

    static Object file(final String s) {
        return new java.io.File(s); // expect: filesystem
    }
}
