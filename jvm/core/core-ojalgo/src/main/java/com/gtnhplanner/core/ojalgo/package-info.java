/**
 * ojAlgo 57.4.0 as the core uses it: this module's jar is ojAlgo's own, with its java.lang.Math and StrictMath calls of
 * the functions core-base's ExactMath has sent to ExactMath and its BigDecimal.valueOf(double) sent to
 * NumberText.javaDecimal (RewriteMath.java and build.gradle.kts in the module directory), so ojAlgo computes the same
 * bits on the JVM (any JDK, any processor) and in the browser build.
 */
package com.gtnhplanner.core.ojalgo;
