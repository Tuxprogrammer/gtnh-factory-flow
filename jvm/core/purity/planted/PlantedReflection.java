public final class PlantedReflection {

    static Object byName(final String name) throws Exception {
        return Class.forName(name); // expect: reflection
    }

    static int fields(final Object o) {
        return o.getClass().getDeclaredFields().length; // expect: reflection
    }

    static Object read(final java.lang.reflect.Field f, final Object o) throws Exception {
        return f.get(o); // expect: reflection
    }
}
