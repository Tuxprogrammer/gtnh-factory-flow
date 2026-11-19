package com.gtnhplanner.harness;

import java.lang.reflect.Field;
import java.util.Random;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

import com.gtnhplanner.data.flowchart.Group;

/**
 * Every test starts the colours of new groups from the same seed. A group made without an id ({@code new Group()},
 * every new shared machine) takes the next colour from one source the whole test JVM shares, so a test that writes such
 * a group (a save, an export, a committed plan) wrote a different colour depending on which tests ran before it. The
 * game draws from that one source on purpose; the tests must not depend on their order. Registered in
 * {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}.
 */
public final class GroupColorsPerTest implements TestExecutionListener {

    /** The seed the game starts from ({@code Group.colorRandom}). */
    private static final long SEED = 12345;

    @Override
    public void executionStarted(final TestIdentifier test) {
        if (!test.isTest()) return;
        try {
            final Field source = Group.class.getDeclaredField("colorRandom");
            source.setAccessible(true);
            source.set(null, new Random(SEED));
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Group's colour source moved: update the test support", e);
        }
    }
}
