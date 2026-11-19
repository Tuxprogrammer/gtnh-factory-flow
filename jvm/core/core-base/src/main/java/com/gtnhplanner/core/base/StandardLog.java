package com.gtnhplanner.core.base;

/** {@link Log#STDERR}: info and above to standard error, debug dropped. */
final class StandardLog implements Log {

    @Override
    public void debug(final String message, final Object... args) {}

    @Override
    public void info(final String message, final Object... args) {
        print("INFO", message, args);
    }

    @Override
    public void warn(final String message, final Object... args) {
        print("WARN", message, args);
    }

    @Override
    public void error(final String message, final Object... args) {
        print("ERROR", message, args);
    }

    private static void print(final String level, final String message, final Object... args) {
        System.err.println(level + " " + fill(message, args));
        if (args.length > 0 && args[args.length - 1] instanceof Throwable t && placeholders(message) < args.length) {
            t.printStackTrace();
        }
    }

    static String fill(final String message, final Object... args) {
        final StringBuilder out = new StringBuilder(message.length() + 16 * args.length);
        int next = 0;
        for (int i = 0; i < message.length(); i++) {
            final char c = message.charAt(i);
            if (c == '{' && i + 1 < message.length() && message.charAt(i + 1) == '}' && next < args.length) {
                out.append(args[next++]);
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int placeholders(final String message) {
        int n = 0;
        for (int i = message.indexOf("{}"); i >= 0; i = message.indexOf("{}", i + 2)) n++;
        return n;
    }
}
