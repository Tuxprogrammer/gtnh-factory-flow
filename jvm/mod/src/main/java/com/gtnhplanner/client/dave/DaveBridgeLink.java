package com.gtnhplanner.client.dave;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.gtnhplanner.GtnhPlanner;

/**
 * DaveBridge's client API ({@code com.tuxprogrammer.davebridge.api.DaveBridgeClientApi}), reached by reflection so the
 * planner neither compiles against nor needs DaveBridge: without it (or with one too old to have the API)
 * {@link #available()} is false and Ask Dave is not shown. The API's own refusals come through as they are
 * ({@link IllegalStateException}, {@link IllegalArgumentException}); anything else that goes wrong in the call is an
 * {@link IllegalStateException}, so the panel always has something to say.
 */
public final class DaveBridgeLink implements DaveLink {

    static final String API = "com.tuxprogrammer.davebridge.api.DaveBridgeClientApi";

    @Nullable
    private static DaveBridgeLink found;
    private static boolean looked;

    private final Method available, ask, cancel, listen, unlisten;

    private DaveBridgeLink(final Class<?> api) throws NoSuchMethodException {
        available = api.getMethod("available");
        ask = api.getMethod("ask", String.class, String.class, String.class, String.class);
        cancel = api.getMethod("cancel", String.class);
        listen = api.getMethod("listen", String.class, Consumer.class);
        unlisten = api.getMethod("unlisten", String.class, Consumer.class);
    }

    /** The bridge when DaveBridge with the client API is installed, else null. Looked up once. */
    @Nullable
    public static DaveBridgeLink find() {
        if (looked) return found;
        looked = true;
        try {
            found = new DaveBridgeLink(Class.forName(API, false, DaveBridgeLink.class.getClassLoader()));
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
            found = null;
        }
        return found;
    }

    @Override
    public boolean available() {
        try {
            return Boolean.TRUE.equals(available.invoke(null));
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public String ask(final String text, final String contextJson, final String attachmentJson) {
        final Object id = call(ask, SURFACE, text, contextJson, attachmentJson);
        if (!(id instanceof final String s) || s.isEmpty()) throw new IllegalStateException("no request id");
        return s;
    }

    @Override
    public void cancel(final String requestId) {
        call(cancel, requestId);
    }

    @Override
    public void listen(final Consumer<String> events) {
        call(listen, SURFACE, events);
    }

    @Override
    public void unlisten(final Consumer<String> events) {
        call(unlisten, SURFACE, events);
    }

    @Nullable
    private static Object call(final Method m, final Object... args) {
        try {
            return m.invoke(null, args);
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof final IllegalArgumentException bad) throw bad;
            if (cause instanceof final IllegalStateException off) throw off;
            GtnhPlanner.LOG.warn("DaveBridge {} failed", m.getName(), cause);
            throw new IllegalStateException(String.valueOf(cause));
        } catch (final ReflectiveOperationException | LinkageError e) {
            GtnhPlanner.LOG.warn("DaveBridge {} could not be called", m.getName(), e);
            throw new IllegalStateException(String.valueOf(e));
        }
    }
}
