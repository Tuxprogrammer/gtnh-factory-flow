public final class PlantedGame {

    static String words(final String key) {
        return net.minecraft.util.StatCollector.translateToLocal(key); // expect: game
    }
}
