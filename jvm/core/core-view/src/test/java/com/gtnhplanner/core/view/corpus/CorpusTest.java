package com.gtnhplanner.core.view.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * The pattern of a view record's JVM test: name the corpus file, the input record and how the core makes the view as
 * JSON, and every case of the file must come out as the website made it. About ten lines per record:
 *
 * <pre>
 * class RailCasesTest extends CorpusTest&lt;RailInput&gt; {
 *     RailCasesTest() { super("rail", "cases", RailInput.class, 40); }
 *     protected String render(RailInput in) { return RailBuilder.build(in).toJson(); }
 * }
 * </pre>
 *
 * The comparison is on the JSON text, key order and number spelling included (a parsed Gson number keeps its text), so
 * a writer that orders a key differently or spells a number differently fails, not only one that changes a value.
 */
public abstract class CorpusTest<I> {

    private final String record;
    private final String set;
    private final Class<I> input;
    private final int minimumCases;

    /** {@code minimumCases} fails a corpus that lost cases: a count at or above what the file held. */
    protected CorpusTest(final String record, final String set, final Class<I> input, final int minimumCases) {
        this.record = record;
        this.set = set;
        this.input = input;
        this.minimumCases = minimumCases;
    }

    /** The view of {@code in} as the core writes it: the JSON text the {@code Json} writer made. */
    protected abstract String render(I in);

    /** The measure the file must name; {@code none} unless the record puts text through a {@code TextMeasure}. */
    protected String measure() {
        return ViewCorpus.NONE;
    }

    /** The record the view is (or a list of), to read the written JSON back through; null to skip that check. */
    protected Class<?> viewRecord() {
        return null;
    }

    /** Whether the view is a list of {@link #viewRecord()}. */
    protected boolean viewIsList() {
        return false;
    }

    @TestFactory
    public Stream<DynamicTest> casesMatchTheWebsite() throws IOException {
        final ViewCorpus corpus = ViewCorpus.open(record, set);
        assertEquals(measure(), corpus.measure, record + "/" + set + " names another text measure");
        final List<ViewCorpus.Case> cases = corpus.cases();
        assertTrue(cases.size() >= minimumCases, record + "/" + set + " lost cases: " + cases.size());
        return cases.stream()
            .map(c -> DynamicTest.dynamicTest(c.name(), () -> {
                final String made = render(RecordReader.read(input, c.input()));
                final JsonElement parsed = new JsonParser().parse(made);
                assertEquals(c.view(), parsed);
                assertEquals(c.view().toString(), parsed.toString(), "key order or number spelling");
                if (viewRecord() != null) {
                    // The writer's JSON is a value of the record: the reader refuses a key the record lacks.
                    if (viewIsList()) RecordReader.readList(viewRecord(), parsed);
                    else RecordReader.read(viewRecord(), parsed);
                }
            }));
    }
}
