package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.client.UpdateCheck;

/** The update check's reading of versions and of GitHub's list of releases. */
class UpdateCheckTest {

    @Test
    void comparesVersions() {
        assertTrue(UpdateCheck.newer("0.2.0-alpha.6", "0.2.0-alpha.5"));
        assertTrue(UpdateCheck.newer("0.2.0-alpha.10", "0.2.0-alpha.9"));
        assertTrue(UpdateCheck.newer("0.2.0", "0.2.0-alpha.9"));
        assertTrue(UpdateCheck.newer("0.3.0-alpha.1", "0.2.0"));
        assertFalse(UpdateCheck.newer("0.2.0-alpha.5", "0.2.0-alpha.5"));
        assertFalse(UpdateCheck.newer("0.2.0-alpha.4", "0.2.0-alpha.5"));
        // A dev build is its tag's version: the same tag is not newer, the next one is.
        assertFalse(UpdateCheck.newer("0.2.0-alpha.5", "0.2.0-alpha.5-claude-x.2+f69732c5fd-dirty"));
        assertTrue(UpdateCheck.newer("0.2.0-alpha.6", "0.2.0-alpha.5-claude-x.2+f69732c5fd-dirty"));
        assertFalse(UpdateCheck.newer("nightly", "0.2.0-alpha.5"));
        // Alpha, beta, rc, then the release.
        assertTrue(UpdateCheck.newer("0.2.0-beta.1", "0.2.0-alpha.5"));
        assertTrue(UpdateCheck.newer("0.2.0-beta.2", "0.2.0-beta.1"));
        assertTrue(UpdateCheck.newer("0.2.0-rc.1", "0.2.0-beta.9"));
        assertTrue(UpdateCheck.newer("0.2.0", "0.2.0-rc.3"));
        assertFalse(UpdateCheck.newer("0.2.0-alpha.9", "0.2.0-beta.1"));
        assertFalse(UpdateCheck.newer("0.2.0-beta.1", "0.2.0-beta.1-main.3+58f9e3d1a2"));
        assertTrue(UpdateCheck.newer("0.2.0-beta.2", "0.2.0-beta.1-main.3+58f9e3d1a2"));
    }

    @Test
    void ordersForkReleasesAfterTheirBase() {
        assertTrue(UpdateCheck.newer("0.2.0-beta.3-tux.2", "0.2.0-beta.3-tux.1"));
        assertTrue(UpdateCheck.newer("0.2.0-beta.3-tux.10", "0.2.0-beta.3-tux.9"));
        assertTrue(UpdateCheck.newer("0.2.0-beta.3-tux.1", "0.2.0-beta.3"));
        assertFalse(UpdateCheck.newer("0.2.0-beta.3", "0.2.0-beta.3-tux.1"));
        assertTrue(UpdateCheck.newer("0.2.0-beta.4", "0.2.0-beta.3-tux.5"));
        assertFalse(UpdateCheck.newer("0.2.0-beta.3-tux.5", "0.2.0-beta.4"));
        // The fork's own line follows its last suffixed release, and is newer than any 0.x of the original.
        assertTrue(UpdateCheck.newer("1.0.0", "0.2.0-beta.3-tux.5"));
        assertTrue(UpdateCheck.newer("mod-1.1.0", "1.0.0"));
        assertTrue(UpdateCheck.newer("1.0.1", "1.0.0"));
        assertFalse(UpdateCheck.newer("0.3.0", "1.0.0"));
        // The repo's tag names read as their versions; a dev build past a fork tag is that tag's version.
        assertTrue(UpdateCheck.newer("mod-0.2.0-beta.3-tux.2", "0.2.0-beta.3-tux.1"));
        assertFalse(UpdateCheck.newer("0.2.0-beta.3-tux.1", "0.2.0-beta.3-tux.1-main.4+58f9e3d1a2"));
        assertTrue(UpdateCheck.newer("0.2.0-beta.3-tux.2", "0.2.0-beta.3-tux.1-main.4+58f9e3d1a2-dirty"));
        assertTrue(UpdateCheck.newer("0.2.0-beta.3-tux.1", "0.2.0-beta.3-restructure.277+c72641f0c0"));
        // The website's releases share the repo and are never the mod's.
        assertFalse(UpdateCheck.newer("web-4.7.0-tux.2", "0.2.0-beta.3"));
    }

    @Test
    void picksTheNewestForkReleaseFromTheRepoTags() throws Exception {
        final String json = "[{\"tag_name\":\"web-4.7.0-tux.2\",\"html_url\":\"http://127.0.0.1/w\"},"
            + "{\"tag_name\":\"mod-0.2.0-beta.3-tux.1\",\"html_url\":\"http://127.0.0.1/t1\"},"
            + "{\"tag_name\":\"mod-0.2.0-beta.3-tux.2\",\"html_url\":\"http://127.0.0.1/t2\"},"
            + "{\"tag_name\":\"mod-0.2.0-beta.3\",\"html_url\":\"http://127.0.0.1/b3\"}]";
        assertEquals("0.2.0-beta.3-tux.2", newest(json).tag());
    }

    @Test
    void picksTheNewestReleaseThatIsNotADraft() throws Exception {
        final String json = "[{\"tag_name\":\"0.2.0-alpha.7\",\"html_url\":\"http://127.0.0.1/u7\",\"draft\":true},"
            + "{\"tag_name\":\"0.2.0-alpha.5\",\"html_url\":\"http://127.0.0.1/u5\",\"draft\":false},"
            + "{\"tag_name\":\"0.2.0-alpha.6\",\"html_url\":\"http://127.0.0.1/u6\",\"draft\":false,\"prerelease\":true}]";
        final UpdateCheck.Release r = newest(json);
        assertEquals("0.2.0-alpha.6", r.tag());
        assertEquals("http://127.0.0.1/u6", r.url());
        assertNull(newest("{\"message\":\"rate limited\"}"));
    }

    @Test
    void aReleasePageThatIsNotAWebAddressIsNeverOffered() throws Exception {
        final String json = "[{\"tag_name\":\"0.3.0\",\"html_url\":\"file:///etc/passwd\"},"
            + "{\"tag_name\":\"0.2.9\",\"html_url\":\"javascript:alert(1)\"},"
            + "{\"tag_name\":\"0.2.0\",\"html_url\":\"https://releases.test/0.2.0\"}]";
        assertEquals("0.2.0", newest(json).tag());
    }

    @Test
    void withNoAddressConfiguredNothingIsAsked() throws Exception {
        com.gtnhplanner.library.SiteConfig.useUpdate(null);
        UpdateCheck.start();
        final java.lang.reflect.Field started = UpdateCheck.class.getDeclaredField("started");
        started.setAccessible(true);
        assertEquals(false, started.get(null), "no thread, no connection");
    }

    private static UpdateCheck.Release newest(final String json) throws Exception {
        final Method m = UpdateCheck.class.getDeclaredMethod("newest", String.class);
        m.setAccessible(true);
        return (UpdateCheck.Release) m.invoke(null, json);
    }
}
