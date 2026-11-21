package com.gtnhplanner.library;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import com.gtnhplanner.cloud.net.CloudApi;
import com.gtnhplanner.core.base.Log;

/**
 * The one extra header an install may send to its own site: a site behind a guarding reverse proxy lets a request
 * through only when it carries a pre-shared header, and the mod cannot follow that proxy's sign-in redirect. The name
 * and the value come from the config ({@link SiteConfig}); both empty (the default) sends nothing.
 * <p>
 * The value is a secret. It is held here and nowhere else, there is no getter, nothing here logs it, no message or
 * exception carries it. It goes out only on a request whose address is the site's own or the live service's (host and
 * port both), and a redirect is never followed, so it cannot reach another
 * host by that road either.
 */
public final class SiteHeader {

    /**
     * What the player is told when the site answers with a redirect to a sign-in: the mod cannot follow one. Names no
     * value, address or header.
     */
    public static final String SIGN_IN = "The site asked for a sign-in the mod cannot do. If its owner gave you an"
        + " access header, set it in the mod's config.";

    /** Headers the client sets itself or that change how a request is framed: a config line may not take them over. */
    private static final Set<String> OWN = Set.of(
        "accept",
        "accept-encoding",
        "connection",
        "content-encoding",
        "content-length",
        "content-type",
        "cookie",
        "expect",
        "host",
        "keep-alive",
        "origin",
        "set-cookie",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "user-agent");

    private static volatile String name = "";
    private static volatile String value = "";

    private SiteHeader() {}

    /**
     * Takes the two config values. Both must be present and usable, else the header is off and the one warning names
     * the key (never the value).
     */
    static void use(@Nullable final String headerName, @Nullable final String headerValue) {
        final String n = headerName == null ? "" : headerName.trim();
        final String v = headerValue == null ? "" : headerValue.trim();
        name = "";
        value = "";
        if (n.isEmpty() && v.isEmpty()) return;
        if (n.isEmpty() || v.isEmpty()) {
            Log.get()
                .warn(
                    "Config {}.{} and {}.{} must both be set to send a header, so none is sent",
                    SiteConfig.CATEGORY,
                    SiteConfig.HEADER_NAME_KEY,
                    SiteConfig.CATEGORY,
                    SiteConfig.HEADER_VALUE_KEY);
            return;
        }
        if (!usableName(n)) {
            Log.get()
                .warn(
                    "Config {}.{} is not a header name this mod may send, so no header is sent",
                    SiteConfig.CATEGORY,
                    SiteConfig.HEADER_NAME_KEY);
            return;
        }
        if (!usableValue(v)) {
            Log.get()
                .warn(
                    "Config {}.{} holds a character a header cannot, so no header is sent",
                    SiteConfig.CATEGORY,
                    SiteConfig.HEADER_VALUE_KEY);
            return;
        }
        name = n;
        value = v;
    }

    /**
     * Whether {@code n} may be sent: an HTTP token (letters, digits and the token punctuation, no space, colon or
     * control character) that is not one of the headers the client sets itself, nor part of the WebSocket handshake.
     */
    public static boolean usableName(final String n) {
        if (n.isEmpty()) return false;
        for (int i = 0; i < n.length(); i++) {
            final char c = n.charAt(i);
            final boolean token = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                || c >= '0' && c <= '9'
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!token) return false;
        }
        final String l = n.toLowerCase(Locale.ROOT);
        return !OWN.contains(l) && !l.startsWith("sec-websocket-") && !l.startsWith("proxy-");
    }

    /** Whether {@code v} is plain printable ASCII (a space or tab inside is fine): no line break, no control. */
    public static boolean usableValue(final String v) {
        for (int i = 0; i < v.length(); i++) {
            final char c = v.charAt(i);
            if (c != '\t' && (c < 0x20 || c > 0x7e)) return false;
        }
        return !v.isEmpty();
    }

    /** Whether a header is configured. */
    public static boolean configured() {
        return !name.isEmpty();
    }

    /**
     * Whether a request to {@code url} gets the header: one is configured, and the address is the site's or the live
     * service's, host and port alike (so another service on the same machine does not).
     */
    public static boolean appliesTo(@Nullable final String url) {
        if (!configured()) return false;
        final String a = authority(url);
        if (a.isEmpty()) return false;
        return a.equals(authority(CommunityApi.site())) || a.equals(authority(CloudApi.live()));
    }

    /**
     * Hands the header to {@code set} (name, then value) when {@link #appliesTo} the request's address; otherwise
     * nothing happens. A connection that refuses it fails with a message that carries neither.
     */
    public static void addTo(final String url, final BiConsumer<String, String> set) {
        if (!appliesTo(url)) return;
        try {
            set.accept(name, value);
        } catch (final RuntimeException e) {
            // The JDK's and netty's messages for a bad header quote it: say only that it was refused.
            throw new IllegalStateException("the site header was not accepted by the connection");
        }
    }

    /** A redirect: the site is sending the client elsewhere, which this client never follows. */
    public static boolean redirect(final int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** Host and port of a web or socket address, the port filled in from the scheme; empty when there is none. */
    static String authority(@Nullable final String url) {
        if (url == null || url.isBlank()) return "";
        try {
            final URI u = URI.create(url.trim());
            final String host = u.getHost();
            if (host == null) return "";
            final String scheme = u.getScheme() == null ? ""
                : u.getScheme()
                    .toLowerCase(Locale.ROOT);
            final int port = u.getPort() >= 0 ? u.getPort() : scheme.equals("https") || scheme.equals("wss") ? 443 : 80;
            return host.toLowerCase(Locale.ROOT) + ":" + port;
        } catch (final IllegalArgumentException e) {
            return "";
        }
    }

    /** Says whether a header is set, never what it is. */
    public static String summary() {
        return configured() ? "a site header is set" : "no site header";
    }
}
