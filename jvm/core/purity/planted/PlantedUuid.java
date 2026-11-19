import java.util.UUID;

public final class PlantedUuid {

    static String fresh() {
        return UUID.randomUUID().toString(); // expect: uuid
    }
}
