package com.gtnhplanner.client.dave;

import java.util.function.Consumer;

/**
 * The way to Dave: the server's assistant, reached through the DaveBridge mod's surface asks (its protocol, section
 * 2.7). An interface so the panel can be tested and the dev game can stand one in; {@link DaveBridgeLink} is the real
 * one. Every method runs on the client thread, and events come back on it.
 */
public interface DaveLink {

    /** The surface name this planner asks under. */
    String SURFACE = "gtnhplanner";

    /** The longest question the bridge takes when the server does not say (it announces 2000 on ours). */
    int MAX_TEXT = 2000;

    /** True when the server takes surface asks now. Ask Dave is shown only then. */
    boolean available();

    /**
     * Sends a question; returns its request id.
     *
     * @throws IllegalStateException    when the bridge is not available
     * @throws IllegalArgumentException when the bridge refuses the question (too long, bad JSON, too big)
     */
    String ask(String text, String contextJson, String attachmentJson);

    /** Stops waiting for {@code requestId}; nothing more arrives for it. */
    void cancel(String requestId);

    /** Registers the listener for this surface's events, one JSON object a call. */
    void listen(Consumer<String> events);

    void unlisten(Consumer<String> events);
}
