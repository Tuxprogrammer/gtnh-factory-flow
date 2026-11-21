package com.gtnhplanner.library;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraftforge.common.config.Configuration;

import com.gtnhplanner.core.base.Log;

/**
 * Every remote address the mod may use, read from the config file ({@code config/gtnhplanner.cfg}, category
 * {@value #CATEGORY}) and never from the source: each is empty by default, and an empty address switches its feature
 * off. A jar contacts no host until a pack's config names one.
 * <ul>
 * <li>{@value #SITE_KEY}: the website (an {@code http://} or {@code https://} base): the community library, accounts
 * and
 * posting, bug reports, cloud plans and plan links. Empty: all of that is off.</li>
 * <li>{@value #LIVE_KEY}: the live service ({@code ws://} or {@code wss://} ending in {@code /live}). Empty: derived
 * from the site's address.</li>
 * <li>{@value #UPDATE_KEY}: an address that answers with the release list in GitHub's releases format, for the update
 * offer. Empty: no update check.</li>
 * <li>{@value #SUPPORT_KEY}: a help or chat page the Feedback box links to. Empty: no link.</li>
 * <li>{@value #ORIGINS_KEY}: origins besides the site's own that may use "Open in the mod". Empty: none.</li>
 * <li>{@value #HEADER_NAME_KEY} and {@value #HEADER_VALUE_KEY}: one extra header sent with every request to the site
 * and the live service (and to no other host), for a site behind a proxy that guards it with a pre-shared header.
 * Both empty: none. The value is a secret and is never logged or shown ({@link SiteHeader}).</li>
 * </ul>
 * A system property of the same purpose ({@code -Dgtnhplanner.library.url}, {@code .live.url}, {@code .update.url},
 * {@code .support.url}, {@code gtnhplanner.header.name}, {@code .header.value}) wins over the file, for development and
 * tests. A value that is not a web address of the right
 * kind is ignored with a warning.
 */
public final class SiteConfig {

    public static final String CATEGORY = "network";
    public static final String SITE_KEY = "siteUrl", LIVE_KEY = "liveUrl", UPDATE_KEY = "updateUrl",
        SUPPORT_KEY = "supportUrl", ORIGINS_KEY = "websiteOrigins", HEADER_NAME_KEY = "headerName",
        HEADER_VALUE_KEY = "headerValue";
    public static final String SITE_PROPERTY = "gtnhplanner.library.url", LIVE_PROPERTY = "gtnhplanner.live.url",
        UPDATE_PROPERTY = "gtnhplanner.update.url", SUPPORT_PROPERTY = "gtnhplanner.support.url",
        HEADER_NAME_PROPERTY = "gtnhplanner.header.name", HEADER_VALUE_PROPERTY = "gtnhplanner.header.value";

    private static volatile String live = "", update = "", support = "";
    private static volatile List<String> origins = List.of();

    static {
        // System properties are read as soon as the class is, so a dev run needs no config file.
        live = address(System.getProperty(LIVE_PROPERTY), true);
        update = address(System.getProperty(UPDATE_PROPERTY), false);
        support = address(System.getProperty(SUPPORT_PROPERTY), false);
        SiteHeader.use(System.getProperty(HEADER_NAME_PROPERTY), System.getProperty(HEADER_VALUE_PROPERTY));
    }

    private SiteConfig() {}

    /** Where the settings are read from: the config file in the game, a map in tests. Defaults are empty. */
    public interface Values {

        /** The text under {@code key} in {@value #CATEGORY}, written to the file with {@code comment} when absent. */
        String text(String key, String comment);

        /** The list under {@code key}, likewise. */
        String[] list(String key, String comment);
    }

    /** Reads the settings out of the mod's config file; a system property already set is left as it is. */
    public static void read(final Configuration c) {
        read(new Values() {

            @Override
            public String text(final String key, final String comment) {
                return c.getString(key, CATEGORY, "", comment);
            }

            @Override
            public String[] list(final String key, final String comment) {
                return c.get(CATEGORY, key, new String[0], comment)
                    .getStringList();
            }
        });
    }

    /** {@link #read(Configuration)} from any source of the values. */
    public static void read(final Values v) {
        if (!set(SITE_PROPERTY)) CommunityApi.useSite(
            checked(
                SITE_KEY,
                v.text(
                    SITE_KEY,
                    "The website this install talks to, as http:// or https:// and a host: the community library,"
                        + " accounts and posting, bug reports, cloud plans and plan links. Empty turns all of that"
                        + " off, and nothing is contacted.")));
        if (!set(LIVE_PROPERTY)) live = address(
            v.text(
                LIVE_KEY,
                "The live service for cloud plans, as ws:// or wss:// and a host ending in /live. Empty derives it"
                    + " from the website's address."),
            true);
        if (!set(UPDATE_PROPERTY)) update = address(
            v.text(
                UPDATE_KEY,
                "An address that lists releases in GitHub's releases JSON format, for the update offer. Empty turns"
                    + " the update check off."),
            false);
        if (!set(SUPPORT_PROPERTY)) support = address(
            v.text(SUPPORT_KEY, "A help or chat page the Feedback box links to. Empty hides the link."),
            false);
        final List<String> list = new ArrayList<>();
        for (final String s : v.list(
            ORIGINS_KEY,
            "Web origins (scheme, host and port, such as https://example.org) besides the website's own that may send"
                + " plans to this game with Open in the mod. Empty allows none.")) {
            final String o = address(s, false);
            if (!o.isEmpty()) list.add(originOf(o));
        }
        origins = List.copyOf(list);
        // The value goes straight to SiteHeader, the only place it is held; nothing here logs or keeps it.
        SiteHeader.use(
            set(HEADER_NAME_PROPERTY) ? System.getProperty(HEADER_NAME_PROPERTY)
                : v.text(
                    HEADER_NAME_KEY,
                    "The name of one extra header to send with every request to the website and its live service, and"
                        + " to no other host, for a site behind a proxy that guards it with a pre-shared header (for"
                        + " example X-Access-Key). Empty sends none. Cookie, Host, Content-Length and the other"
                        + " headers the mod sets itself are not allowed."),
            set(HEADER_VALUE_PROPERTY) ? System.getProperty(HEADER_VALUE_PROPERTY)
                : v.text(
                    HEADER_VALUE_KEY,
                    "The value of that header: a secret the site's owner gives you. Both this and the name must be"
                        + " set. It is never written to the log or shown in the game."));
    }

    private static boolean set(final String property) {
        final String p = System.getProperty(property);
        return p != null && !p.isBlank();
    }

    private static String checked(final String key, final String value) {
        final String a = address(value, false);
        if (a.isEmpty() && !value.isBlank()) Log.get()
            .warn("Config {}.{} is not an http:// or https:// address, so it is ignored: {}", CATEGORY, key, value);
        return a;
    }

    /**
     * {@code s} as an address of this kind with trailing slashes removed, or empty when it is blank or not one: web
     * (http or https) or, with {@code socket}, ws or wss, and a host.
     */
    public static String address(@Nullable final String s, final boolean socket) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        final int i = t.indexOf("://");
        if (i <= 0) return "";
        final String scheme = t.substring(0, i)
            .toLowerCase(Locale.ROOT);
        final boolean ok = socket ? scheme.equals("ws") || scheme.equals("wss")
            : scheme.equals("http") || scheme.equals("https");
        final String rest = t.substring(i + 3);
        if (!ok || rest.isEmpty() || rest.startsWith("/") || rest.startsWith(":") || rest.matches(".*\\s.*")) return "";
        return t;
    }

    /** The scheme, host and port of a URL, as a browser sends them in {@code Origin}. */
    public static String originOf(final String url) {
        final int scheme = url.indexOf("://");
        if (scheme < 0) return url;
        final int path = url.indexOf('/', scheme + 3);
        return path < 0 ? url : url.substring(0, path);
    }

    /** The live service's address from the config, or empty (derive it from the site). */
    public static String live() {
        return live;
    }

    /** Where the release list is, or empty for no update check. */
    public static String update() {
        return update;
    }

    /** The help page the Feedback box links to, or empty for none. */
    public static String support() {
        return support;
    }

    /** Whether any website may use "Open in the mod": a site is set, or origins are listed. */
    public static boolean websiteOpens() {
        return CommunityApi.configured() || !origins.isEmpty();
    }

    /** Whether {@code origin} is one the config lists beside the site's own. */
    public static boolean listedOrigin(@Nullable final String origin) {
        return origin != null && origins.contains(origin);
    }

    // region For tests and the dev harness

    public static void useUpdate(@Nullable final String url) {
        update = address(url, false);
    }

    public static void useSupport(@Nullable final String url) {
        support = address(url, false);
    }

    public static void useLive(@Nullable final String url) {
        live = address(url, true);
    }

    /**
     * Sets the extra header (both empty: none); a value or name that is not usable turns it off, as the config does.
     */
    public static void useHeader(@Nullable final String name, @Nullable final String value) {
        SiteHeader.use(name, value);
    }

    public static void useOrigins(final List<String> list) {
        final List<String> out = new ArrayList<>();
        for (final String s : list) {
            final String o = address(s, false);
            if (!o.isEmpty()) out.add(originOf(o));
        }
        origins = List.copyOf(out);
    }

    // endregion
}
