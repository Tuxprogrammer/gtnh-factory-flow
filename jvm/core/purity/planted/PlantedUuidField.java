import java.util.List;

// A field's type, and a type argument in its generic signature, are references too.
// expect-class: uuid
public final class PlantedUuidField {

    List<java.util.UUID> ids;
}
