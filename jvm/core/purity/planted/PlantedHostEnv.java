public final class PlantedHostEnv {

    static String home() {
        return System.getProperty("user.home"); // expect: host-env
    }

    static String path() {
        return System.getenv("PATH"); // expect: host-env
    }
}
