// source-only: Lombok is not on the self-test's compile path; the source scan alone must find it.
import lombok.Getter; // expect: lombok

@lombok.Value // expect: lombok
public class PlantedLombok {

    @Getter // the annotation itself is not the finding, its import is
    int x;
}
