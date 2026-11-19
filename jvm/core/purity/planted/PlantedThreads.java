import java.util.List;

public final class PlantedThreads {

    static void start(final Runnable r) {
        new Thread(r).start(); // expect: threads
    }

    static Object pool() {
        return java.util.concurrent.Executors.newFixedThreadPool(2); // expect: threads
    }

    static long count(final List<String> xs) {
        return xs.parallelStream().count(); // expect: threads
    }
}
