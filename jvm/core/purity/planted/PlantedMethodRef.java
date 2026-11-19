import java.util.function.DoubleFunction;

// A method reference leaves no call instruction, only a method handle in the constant pool.
// expect-class: float-text
public final class PlantedMethodRef {

    static final DoubleFunction<String> SPELL = Double::toString;
}
