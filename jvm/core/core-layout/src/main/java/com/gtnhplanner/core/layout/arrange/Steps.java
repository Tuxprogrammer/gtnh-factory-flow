package com.gtnhplanner.core.layout.arrange;

/** How far a search is, for a loader ({@code onProgress(done, total)} of the website's arrange). */
@FunctionalInterface
public interface Steps {

    void at(double done, double total);
}
