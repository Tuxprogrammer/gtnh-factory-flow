/*
 * The functions marked "fdlibm" below are Java translations of Sun's freely distributable math library, fdlibm 5.3
 * (netlib.org/fdlibm), made from its C sources, whose notice reads:
 *
 * ====================================================
 * Copyright (C) 1993, 2004 by Sun Microsystems, Inc. All rights reserved.
 *
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 *
 * The rest of this file is this project's own (MIT licence).
 */
package com.gtnhplanner.core.base;

/**
 * The core's mathematics: every function here gives the same bits as the JVM's {@link StrictMath} for every argument,
 * on the JVM and on both web targets (WebAssembly GC and JavaScript), because it is plain arithmetic the core compiles
 * itself. {@code StrictMath} is specified as fdlibm's algorithms; each function below is fdlibm's, translated from the C
 * source named beside it, never from another Java translation. Proof: {@code ExactMathTest} against {@code StrictMath}
 * on the JVM, and the web conformance run against the same vectors on both targets.
 *
 * <p>Why the core does not call {@code Math}: the JVM replaces {@code Math.sin}, {@code cos}, {@code tan}, {@code log},
 * {@code log10}, {@code exp}, {@code pow} (and on newer JDKs {@code tanh}, {@code cbrt}) with processor-specific code that
 * can differ from {@code StrictMath} in the last place, so a player's answer would depend on their JDK and processor;
 * and the web compiler's {@code Math} is the browser's (different algorithms) or its own shortcuts ({@code cbrt} as
 * {@code pow(x, 1/3)}, {@code rint} and {@code round} that move halves the wrong way, {@code tanh} that overflows to
 * NaN). The purity gate (rule {@code math-exact}) sends every core call of those functions here.
 *
 * <p>Functions that are exact everywhere already ({@code abs}, {@code floor}, {@code ceil}, {@code sqrt}, {@code min},
 * {@code max}, {@code signum}, {@code copySign}) stay on {@code Math}. This class itself uses only those and the four
 * operations, and writes no unary minus where the operand can be zero (the WebAssembly target turns {@code -x} into
 * {@code 0 - x}, which loses the sign of a zero) and no {@code %} on a double.
 *
 * <p>Sources: sin, cos, tan: s_sin.c, s_cos.c, s_tan.c with k_sin.c, k_cos.c, k_tan.c, e_rem_pio2.c, k_rem_pio2.c;
 * asin e_asin.c; acos e_acos.c; atan s_atan.c; atan2 e_atan2.c; exp e_exp.c; log e_log.c; log10 e_log10.c; pow e_pow.c;
 * cbrt s_cbrt.c; sinh e_sinh.c; cosh e_cosh.c; tanh s_tanh.c; expm1 s_expm1.c; log1p s_log1p.c; hypot e_hypot.c;
 * scalbn s_scalbn.c (internal). Own: rint, round, toRadians, toDegrees, ulp (exact by definition, written from the Java
 * specification).
 */
public final class ExactMath {

    private ExactMath() {}

    // ---- words of a double, as fdlibm's __HI and __LO

    private static int hi(final double x) {
        return (int) (Double.doubleToRawLongBits(x) >>> 32);
    }

    private static int lo(final double x) {
        return (int) Double.doubleToRawLongBits(x);
    }

    private static double withHi(final double x, final int hi) {
        return Double.longBitsToDouble((long) hi << 32 | Double.doubleToRawLongBits(x) & 0xFFFF_FFFFL);
    }

    private static double withLo(final double x, final int lo) {
        return Double.longBitsToDouble(Double.doubleToRawLongBits(x) & 0xFFFF_FFFF_0000_0000L | lo & 0xFFFF_FFFFL);
    }

    private static double words(final int hi, final int lo) {
        return Double.longBitsToDouble((long) hi << 32 | lo & 0xFFFF_FFFFL);
    }

    /** The low word cleared: fdlibm's {@code __LO(x) = 0}. */
    private static double chop(final double x) {
        return Double.longBitsToDouble(Double.doubleToRawLongBits(x) & 0xFFFF_FFFF_0000_0000L);
    }

    /** {@code -x} with the sign of a zero kept on every target. */
    private static double neg(final double x) {
        return -1.0 * x;
    }

    private static final double TWO54 = 1.80143985094819840000e+16, TWOM54 = 5.55111512312578270212e-17,
            HUGE = 1.0e+300, TINY = 1.0e-300;

    /** fdlibm s_scalbn.c: x times 2 to the n, rounded once. */
    private static double scalbn(double x, final int n) {
        int hx = hi(x);
        final int lx = lo(x);
        int k = (hx & 0x7ff00000) >> 20;
        if (k == 0) {
            if ((lx | hx & 0x7fffffff) == 0) return x;
            x *= TWO54;
            hx = hi(x);
            k = ((hx & 0x7ff00000) >> 20) - 54;
            if (n < -50000) return TINY * x;
        }
        if (k == 0x7ff) return x + x;
        k = k + n;
        if (k > 0x7fe) return HUGE * Math.copySign(HUGE, x);
        if (k > 0) return withHi(x, hx & 0x800fffff | k << 20);
        if (k <= -54) {
            if (n > 50000) return HUGE * Math.copySign(HUGE, x);
            return TINY * Math.copySign(TINY, x);
        }
        k += 54;
        return withHi(x, hx & 0x800fffff | k << 20) * TWOM54;
    }

    // ---- exp, log, log10, pow

    private static final double O_THRESHOLD = 7.09782712893383973096e+02, U_THRESHOLD = -7.45133219101941108420e+02,
            TWOM1000 = 9.33263618503218878990e-302, INVLN2 = 1.44269504088896338700e+00,
            LN2_HI = 6.93147180369123816490e-01, LN2_LO = 1.90821492927058770002e-10,
            P1 = 1.66666666666666019037e-01, P2 = -2.77777777770155933842e-03, P3 = 6.61375632143793436117e-05,
            P4 = -1.65339022054652515390e-06, P5 = 4.13813679705723846039e-08;

    /** e to the x (fdlibm e_exp.c). */
    public static double exp(double x) {
        double hi = 0, lo = 0;
        int k = 0;
        int hx = hi(x);
        final int xsb = hx >>> 31;
        hx &= 0x7fffffff;
        if (hx >= 0x40862E42) {
            if (hx >= 0x7ff00000) {
                if ((hx & 0xfffff | lo(x)) != 0) return x + x;
                return xsb == 0 ? x : 0.0;
            }
            if (x > O_THRESHOLD) return HUGE * HUGE;
            if (x < U_THRESHOLD) return TWOM1000 * TWOM1000;
        }
        if (hx > 0x3fd62e42) {
            if (hx < 0x3FF0A2B2) {
                if (xsb == 0) {
                    hi = x - LN2_HI;
                    lo = LN2_LO;
                    k = 1;
                } else {
                    hi = x + LN2_HI;
                    lo = -LN2_LO;
                    k = -1;
                }
            } else {
                k = (int) (INVLN2 * x + (xsb == 0 ? 0.5 : -0.5));
                final double t = k;
                hi = x - t * LN2_HI;
                lo = t * LN2_LO;
            }
            x = hi - lo;
        } else if (hx < 0x3e300000) {
            return 1.0 + x;
        }
        final double t = x * x;
        final double c = x - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
        if (k == 0) return 1.0 - ((x * c) / (c - 2.0) - x);
        final double y = 1.0 - ((lo - (x * c) / (2.0 - c)) - hi);
        if (k >= -1021) return withHi(y, hi(y) + (k << 20));
        return withHi(y, hi(y) + ((k + 1000) << 20)) * TWOM1000;
    }

    private static final double LG1 = 6.666666666666735130e-01, LG2 = 3.999999999940941908e-01,
            LG3 = 2.857142874366239149e-01, LG4 = 2.222219843214978396e-01, LG5 = 1.818357216161805012e-01,
            LG6 = 1.531383769920937332e-01, LG7 = 1.479819860511658591e-01;

    /** The natural logarithm (fdlibm e_log.c). */
    public static double log(double x) {
        int hx = hi(x);
        final int lx = lo(x);
        int k = 0;
        if (hx < 0x00100000) {
            if ((hx & 0x7fffffff | lx) == 0) return Double.NEGATIVE_INFINITY;
            if (hx < 0) return Double.NaN;
            k -= 54;
            x *= TWO54;
            hx = hi(x);
        }
        if (hx >= 0x7ff00000) return x + x;
        k += (hx >> 20) - 1023;
        hx &= 0x000fffff;
        int i = (hx + 0x95f64) & 0x100000;
        x = withHi(x, hx | i ^ 0x3ff00000);
        k += i >> 20;
        final double f = x - 1.0;
        if ((0x000fffff & (2 + hx)) < 3) {
            if (f == 0) {
                if (k == 0) return 0;
                final double dk = k;
                return dk * LN2_HI + dk * LN2_LO;
            }
            final double r = f * f * (0.5 - 0.33333333333333333 * f);
            if (k == 0) return f - r;
            final double dk = k;
            return dk * LN2_HI - ((r - dk * LN2_LO) - f);
        }
        final double s = f / (2.0 + f);
        final double dk = k;
        final double z = s * s;
        i = hx - 0x6147a;
        final double w = z * z;
        final int j = 0x6b851 - hx;
        final double t1 = w * (LG2 + w * (LG4 + w * LG6));
        final double t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)));
        i |= j;
        final double r = t2 + t1;
        if (i > 0) {
            final double hfsq = 0.5 * f * f;
            if (k == 0) return f - (hfsq - s * (hfsq + r));
            return dk * LN2_HI - ((hfsq - (s * (hfsq + r) + dk * LN2_LO)) - f);
        }
        if (k == 0) return f - s * (f - r);
        return dk * LN2_HI - ((s * (f - r) - dk * LN2_LO) - f);
    }

    private static final double IVLN10 = 4.34294481903251816668e-01, LOG10_2HI = 3.01029995663611771306e-01,
            LOG10_2LO = 3.69423907715893078616e-13;

    /** The base-10 logarithm (fdlibm e_log10.c): exact on 10 to the n. */
    public static double log10(double x) {
        int hx = hi(x);
        final int lx = lo(x);
        int k = 0;
        if (hx < 0x00100000) {
            if ((hx & 0x7fffffff | lx) == 0) return Double.NEGATIVE_INFINITY;
            if (hx < 0) return Double.NaN;
            k -= 54;
            x *= TWO54;
            hx = hi(x);
        }
        if (hx >= 0x7ff00000) return x + x;
        k += (hx >> 20) - 1023;
        final int i = k >>> 31;
        hx = hx & 0x000fffff | (0x3ff - i) << 20;
        final double y = k + i;
        x = withHi(x, hx);
        final double z = y * LOG10_2LO + IVLN10 * log(x);
        return z + y * LOG10_2HI;
    }

    private static final double DP_H1 = 5.84962487220764160156e-01, DP_L1 = 1.35003920212974897128e-08,
            TWO53 = 9007199254740992.0, L1 = 5.99999999999994648725e-01, L2 = 4.28571428578550184252e-01,
            L3 = 3.33333329818377432918e-01, L4 = 2.72728123808534006489e-01, L5 = 2.30660745775561754067e-01,
            L6 = 2.06975017800338417784e-01, LG2_ = 6.93147180559945286227e-01, LG2_H = 6.93147182464599609375e-01,
            LG2_L = -1.90465429995776804525e-09, OVT = 8.0085662595372944372e-17, CP = 9.61796693925975554329e-01,
            CP_H = 9.61796700954437255859e-01, CP_L = -7.02846165095275826516e-09, IVLN2 = 1.44269504088896338700e+00,
            IVLN2_H = 1.44269502162933349609e+00, IVLN2_L = 1.92596299112661746887e-08;

    /** x to the y (fdlibm e_pow.c). */
    public static double pow(final double x, final double y) {
        double z, ax, zH, zL, pH, pL, t1, t2, r, s, t, u, v, w;
        int i, j, k, yisint, n;
        final int hx = hi(x), lx = lo(x), hy = hi(y), ly = lo(y);
        int ix = hx & 0x7fffffff;
        final int iy = hy & 0x7fffffff;

        if ((iy | ly) == 0) return 1.0;
        if (ix > 0x7ff00000 || ix == 0x7ff00000 && lx != 0 || iy > 0x7ff00000 || iy == 0x7ff00000 && ly != 0) {
            return x + y;
        }
        // Is y an odd (1) or even (2) whole number, when x < 0.
        yisint = 0;
        if (hx < 0) {
            if (iy >= 0x43400000) {
                yisint = 2;
            } else if (iy >= 0x3ff00000) {
                k = (iy >> 20) - 0x3ff;
                if (k > 20) {
                    j = ly >>> (52 - k);
                    if (j << (52 - k) == ly) yisint = 2 - (j & 1);
                } else if (ly == 0) {
                    j = iy >> (20 - k);
                    if (j << (20 - k) == iy) yisint = 2 - (j & 1);
                }
            }
        }
        if (ly == 0) {
            if (iy == 0x7ff00000) {
                if ((ix - 0x3ff00000 | lx) == 0) return y - y;
                if (ix >= 0x3ff00000) return hy >= 0 ? y : 0.0;
                return hy < 0 ? neg(y) : 0.0;
            }
            if (iy == 0x3ff00000) return hy < 0 ? 1.0 / x : x;
            if (hy == 0x40000000) return x * x;
            if (hy == 0x3fe00000 && hx >= 0) return Math.sqrt(x);
        }

        ax = Math.abs(x);
        if (lx == 0 && (ix == 0x7ff00000 || ix == 0 || ix == 0x3ff00000)) {
            z = ax;
            if (hy < 0) z = 1.0 / z;
            if (hx < 0) {
                if ((ix - 0x3ff00000 | yisint) == 0) {
                    z = (z - z) / (z - z);
                } else if (yisint == 1) {
                    z = neg(z);
                }
            }
            return z;
        }

        n = (hx >> 31) + 1;
        if ((n | yisint) == 0) return (x - x) / (x - x);
        s = 1.0;
        if ((n | yisint - 1) == 0) s = -1.0;

        if (iy > 0x41e00000) {
            if (iy > 0x43f00000) {
                if (ix <= 0x3fefffff) return hy < 0 ? HUGE * HUGE : TINY * TINY;
                if (ix >= 0x3ff00000) return hy > 0 ? HUGE * HUGE : TINY * TINY;
            }
            if (ix < 0x3fefffff) return hy < 0 ? s * HUGE * HUGE : s * TINY * TINY;
            if (ix > 0x3ff00000) return hy > 0 ? s * HUGE * HUGE : s * TINY * TINY;
            t = ax - 1.0;
            w = (t * t) * (0.5 - t * (0.3333333333333333333333 - t * 0.25));
            u = IVLN2_H * t;
            v = t * IVLN2_L - w * IVLN2;
            t1 = chop(u + v);
            t2 = v - (t1 - u);
        } else {
            double ss, s2, sH, sL, tH, tL;
            n = 0;
            if (ix < 0x00100000) {
                ax *= TWO53;
                n -= 53;
                ix = hi(ax);
            }
            n += (ix >> 20) - 0x3ff;
            j = ix & 0x000fffff;
            ix = j | 0x3ff00000;
            if (j <= 0x3988E) {
                k = 0;
            } else if (j < 0xBB67A) {
                k = 1;
            } else {
                k = 0;
                n += 1;
                ix -= 0x00100000;
            }
            ax = withHi(ax, ix);
            final double bp = k == 0 ? 1.0 : 1.5;
            u = ax - bp;
            v = 1.0 / (ax + bp);
            ss = u * v;
            sH = chop(ss);
            tH = words(((ix >> 1) | 0x20000000) + 0x00080000 + (k << 18), 0);
            tL = ax - (tH - bp);
            sL = v * ((u - sH * tH) - sH * tL);
            s2 = ss * ss;
            r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6)))));
            r += sL * (sH + ss);
            s2 = sH * sH;
            tH = chop(3.0 + s2 + r);
            tL = r - ((tH - 3.0) - s2);
            u = sH * tH;
            v = sL * tH + tL * ss;
            pH = chop(u + v);
            pL = v - (pH - u);
            zH = CP_H * pH;
            zL = CP_L * pH + pL * CP + (k == 0 ? 0.0 : DP_L1);
            t = n;
            final double dpH = k == 0 ? 0.0 : DP_H1;
            t1 = chop(((zH + zL) + dpH) + t);
            t2 = zL - (((t1 - t) - dpH) - zH);
        }

        final double y1 = chop(y);
        pL = (y - y1) * t1 + y * t2;
        pH = y1 * t1;
        z = pL + pH;
        j = hi(z);
        i = lo(z);
        if (j >= 0x40900000) {
            if ((j - 0x40900000 | i) != 0) return s * HUGE * HUGE;
            if (pL + OVT > z - pH) return s * HUGE * HUGE;
        } else if ((j & 0x7fffffff) >= 0x4090cc00) {
            if ((j - 0xc090cc00 | i) != 0) return s * TINY * TINY;
            if (pL <= z - pH) return s * TINY * TINY;
        }
        i = j & 0x7fffffff;
        k = (i >> 20) - 0x3ff;
        n = 0;
        if (i > 0x3fe00000) {
            n = j + (0x00100000 >> (k + 1));
            k = ((n & 0x7fffffff) >> 20) - 0x3ff;
            t = words(n & ~(0x000fffff >> k), 0);
            n = ((n & 0x000fffff) | 0x00100000) >> (20 - k);
            if (j < 0) n = -n;
            pH -= t;
        }
        t = chop(pL + pH);
        u = t * LG2_H;
        v = (pL - (t - pH)) * LG2_ + t * LG2_L;
        z = u + v;
        w = v - (z - u);
        t = z * z;
        t1 = z - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
        r = (z * t1) / (t1 - 2.0) - (w + z * w);
        z = 1.0 - (r - z);
        j = hi(z);
        j += n << 20;
        if ((j >> 20) <= 0) {
            z = scalbn(z, n);
        } else {
            z = withHi(z, hi(z) + (n << 20));
        }
        return s * z;
    }

    // ---- sin, cos, tan

    private static final double S1 = -1.66666666666666324348e-01, S2 = 8.33333333332248946124e-03,
            S3 = -1.98412698298579493134e-04, S4 = 2.75573137070700676789e-06, S5 = -2.50507602534068634195e-08,
            S6 = 1.58969099521155010221e-10;

    /** fdlibm k_sin.c: sin(x + y) on [-pi/4, pi/4]; iy is 0 when y is 0. */
    private static double kernelSin(final double x, final double y, final int iy) {
        final int ix = hi(x) & 0x7fffffff;
        if (ix < 0x3e400000 && (int) x == 0) return x;
        final double z = x * x;
        final double v = z * x;
        final double r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
        if (iy == 0) return x + v * (S1 + z * r);
        return x - ((z * (0.5 * y - v * r) - y) - v * S1);
    }

    private static final double C1 = 4.16666666666666019037e-02, C2 = -1.38888888888741095749e-03,
            C3 = 2.48015872894767294178e-05, C4 = -2.75573143513906633035e-07, C5 = 2.08757232129817482790e-09,
            C6 = -1.13596475577881948265e-11;

    /** fdlibm k_cos.c: cos(x + y) on [-pi/4, pi/4]. */
    private static double kernelCos(final double x, final double y) {
        final int ix = hi(x) & 0x7fffffff;
        if (ix < 0x3e400000 && (int) x == 0) return 1.0;
        final double z = x * x;
        final double r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
        if (ix < 0x3FD33333) return 1.0 - (0.5 * z - (z * r - x * y));
        final double qx = ix > 0x3fe90000 ? 0.28125 : words(ix - 0x00200000, 0);
        final double hz = 0.5 * z - qx;
        final double a = 1.0 - qx;
        return a - (hz - (z * r - x * y));
    }

    private static final double[] T = {
        3.33333333333334091986e-01, 1.33333333333201242699e-01, 5.39682539762260521377e-02,
        2.18694882948595424599e-02, 8.86323982359930005737e-03, 3.59207910759131235356e-03,
        1.45620945432529025516e-03, 5.88041240820264096874e-04, 2.46463134818469906812e-04,
        7.81794442939557092300e-05, 7.14072491382608190305e-05, -1.85586374855275456654e-05,
        2.59073051863633712884e-05,
    };
    private static final double PIO4 = 7.85398163397448278999e-01, PIO4LO = 3.06161699786838301793e-17;

    /** fdlibm k_tan.c: tan(x + y) on [-pi/4, pi/4] when iy is 1, -1/tan when iy is -1. */
    private static double kernelTan(double x, double y, final int iy) {
        double z, r, v, w, s;
        final int hx = hi(x);
        final int ix = hx & 0x7fffffff;
        if (ix < 0x3e300000 && (int) x == 0) {
            if ((ix | lo(x) | iy + 1) == 0) return 1.0 / Math.abs(x);
            if (iy == 1) return x;
            w = x + y;
            z = chop(w);
            v = y - (z - x);
            final double a = -1.0 / w;
            final double t = chop(a);
            s = 1.0 + t * z;
            return t + a * (s + t * v);
        }
        if (ix >= 0x3FE59428) {
            if (hx < 0) {
                x = neg(x);
                y = neg(y);
            }
            z = PIO4 - x;
            w = PIO4LO - y;
            x = z + w;
            y = 0.0;
        }
        z = x * x;
        w = z * z;
        r = T[1] + w * (T[3] + w * (T[5] + w * (T[7] + w * (T[9] + w * T[11]))));
        v = z * (T[2] + w * (T[4] + w * (T[6] + w * (T[8] + w * (T[10] + w * T[12])))));
        s = z * x;
        r = y + z * (s * (r + v) + y);
        r += T[0] * s;
        w = x + r;
        if (ix >= 0x3FE59428) {
            v = iy;
            return (1 - ((hx >> 30) & 2)) * (v - 2.0 * (x - (w * w / (w + v) - r)));
        }
        if (iy == 1) return w;
        z = chop(w);
        v = r - (z - x);
        final double a = -1.0 / w;
        final double t = chop(a);
        s = 1.0 + t * z;
        return t + a * (s + t * v);
    }

    /** sin (fdlibm s_sin.c). */
    public static double sin(final double x) {
        final int ix = hi(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) return kernelSin(x, 0.0, 0);
        if (ix >= 0x7ff00000) return x - x;
        final double[] y = new double[2];
        final int n = remPio2(x, y);
        switch (n & 3) {
            case 0:
                return kernelSin(y[0], y[1], 1);
            case 1:
                return kernelCos(y[0], y[1]);
            case 2:
                return neg(kernelSin(y[0], y[1], 1));
            default:
                return neg(kernelCos(y[0], y[1]));
        }
    }

    /** cos (fdlibm s_cos.c). */
    public static double cos(final double x) {
        final int ix = hi(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) return kernelCos(x, 0.0);
        if (ix >= 0x7ff00000) return x - x;
        final double[] y = new double[2];
        final int n = remPio2(x, y);
        switch (n & 3) {
            case 0:
                return kernelCos(y[0], y[1]);
            case 1:
                return neg(kernelSin(y[0], y[1], 1));
            case 2:
                return neg(kernelCos(y[0], y[1]));
            default:
                return kernelSin(y[0], y[1], 1);
        }
    }

    /** tan (fdlibm s_tan.c). */
    public static double tan(final double x) {
        final int ix = hi(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) return kernelTan(x, 0.0, 1);
        if (ix >= 0x7ff00000) return x - x;
        final double[] y = new double[2];
        final int n = remPio2(x, y);
        return kernelTan(y[0], y[1], 1 - ((n & 1) << 1));
    }

    private static final int[] TWO_OVER_PI = {
        0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62, 0x95993C, 0x439041, 0xFE5163, 0xABDEBB, 0xC561B7,
        0x246E3A, 0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129, 0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C,
        0x7026B4, 0x5F7E41, 0x3991D6, 0x398353, 0x39F49C, 0x845F8B, 0xBDF928, 0x3B1FF8, 0x97FFDE, 0x05980F, 0xEF2F11,
        0x8B5A0A, 0x6D1F6D, 0x367ECF, 0x27CB09, 0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5, 0xF17B3D, 0x0739F7,
        0x8A5292, 0xEA6BFB, 0x5FB11F, 0x8D5D08, 0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3, 0x91615E,
        0xE61B08, 0x659985, 0x5F14A0, 0x68408D, 0xFFD880, 0x4D7327, 0x310606, 0x1556CA, 0x73A8C9, 0x60E27B, 0xC08C6B,
    };
    private static final int[] NPIO2_HW = {
        0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, 0x4025FDBB, 0x402921FB, 0x402C463A,
        0x402F6A7A, 0x4031475C, 0x4032D97C, 0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
        0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C, 0x4043A28C, 0x40446B9C, 0x404534AC,
        0x4045FDBB, 0x4046C6CB, 0x40478FDB, 0x404858EB, 0x404921FB,
    };
    private static final double TWO24 = 1.67772160000000000000e+07, INVPIO2 = 6.36619772367581382433e-01,
            PIO2_1 = 1.57079632673412561417e+00, PIO2_1T = 6.07710050650619224932e-11,
            PIO2_2 = 6.07710050630396597660e-11, PIO2_2T = 2.02226624879595063154e-21,
            PIO2_3 = 2.02226624871116645580e-21, PIO2_3T = 8.47842766036889956997e-32;

    /** fdlibm e_rem_pio2.c: x minus n pi/2 as y[0] + y[1]; answers n. */
    private static int remPio2(final double x, final double[] y) {
        double z, w, t, r, fn;
        int i, j, n;
        final int hx = hi(x);
        final int ix = hx & 0x7fffffff;
        if (ix <= 0x3fe921fb) {
            y[0] = x;
            y[1] = 0;
            return 0;
        }
        if (ix < 0x4002d97c) {
            if (hx > 0) {
                z = x - PIO2_1;
                if (ix != 0x3ff921fb) {
                    y[0] = z - PIO2_1T;
                    y[1] = (z - y[0]) - PIO2_1T;
                } else {
                    z -= PIO2_2;
                    y[0] = z - PIO2_2T;
                    y[1] = (z - y[0]) - PIO2_2T;
                }
                return 1;
            }
            z = x + PIO2_1;
            if (ix != 0x3ff921fb) {
                y[0] = z + PIO2_1T;
                y[1] = (z - y[0]) + PIO2_1T;
            } else {
                z += PIO2_2;
                y[0] = z + PIO2_2T;
                y[1] = (z - y[0]) + PIO2_2T;
            }
            return -1;
        }
        if (ix <= 0x413921fb) {
            t = Math.abs(x);
            n = (int) (t * INVPIO2 + 0.5);
            fn = n;
            r = t - fn * PIO2_1;
            w = fn * PIO2_1T;
            if (n < 32 && ix != NPIO2_HW[n - 1]) {
                y[0] = r - w;
            } else {
                j = ix >> 20;
                y[0] = r - w;
                i = j - ((hi(y[0]) >> 20) & 0x7ff);
                if (i > 16) {
                    t = r;
                    w = fn * PIO2_2;
                    r = t - w;
                    w = fn * PIO2_2T - ((t - r) - w);
                    y[0] = r - w;
                    i = j - ((hi(y[0]) >> 20) & 0x7ff);
                    if (i > 49) {
                        t = r;
                        w = fn * PIO2_3;
                        r = t - w;
                        w = fn * PIO2_3T - ((t - r) - w);
                        y[0] = r - w;
                    }
                }
            }
            y[1] = (r - y[0]) - w;
            if (hx < 0) {
                y[0] = neg(y[0]);
                y[1] = neg(y[1]);
                return -n;
            }
            return n;
        }
        if (ix >= 0x7ff00000) {
            y[0] = y[1] = x - x;
            return 0;
        }
        final double[] tx = new double[3];
        final int e0 = (ix >> 20) - 1046;
        z = words(ix - (e0 << 20), lo(x));
        for (i = 0; i < 2; i++) {
            tx[i] = (int) z;
            z = (z - tx[i]) * TWO24;
        }
        tx[2] = z;
        int nx = 3;
        while (tx[nx - 1] == 0) nx--;
        n = kernelRemPio2(tx, y, e0, nx);
        if (hx < 0) {
            y[0] = neg(y[0]);
            y[1] = neg(y[1]);
            return -n;
        }
        return n;
    }

    private static final double[] PIO2 = {
        1.57079625129699707031e+00, 7.54978941586159635335e-08, 5.39030252995776476554e-15,
        3.28200341580791294123e-22, 1.27065575308067607349e-29, 1.22933308981111328932e-36,
        2.73370053816464559624e-44, 2.16741683877804819444e-51,
    };
    private static final double TWON24 = 5.96046447753906250000e-08;

    /**
     * fdlibm k_rem_pio2.c (Payne and Hanek) for the double-precision case fdlibm's rem_pio2 asks for (prec 2, so jk 4):
     * x[0..nx-1] are the 24-bit pieces of the argument scaled by 2 to the -e0; answers n mod 8 and y[0] + y[1].
     */
    private static int kernelRemPio2(final double[] x, final double[] y, final int e0, final int nx) {
        int jz, jx, jv, jp, jk, carry, n, i, j, k, m, q0, ih;
        double z, fw;
        final int[] iq = new int[20];
        final double[] f = new double[20], fq = new double[20], q = new double[20];
        jk = 4;
        jp = jk;
        jx = nx - 1;
        jv = (e0 - 3) / 24;
        if (jv < 0) jv = 0;
        q0 = e0 - 24 * (jv + 1);
        j = jv - jx;
        m = jx + jk;
        for (i = 0; i <= m; i++, j++) f[i] = j < 0 ? 0.0 : (double) TWO_OVER_PI[j];
        for (i = 0; i <= jk; i++) {
            for (j = 0, fw = 0.0; j <= jx; j++) fw += x[j] * f[jx + i - j];
            q[i] = fw;
        }
        jz = jk;
        while (true) {
            for (i = 0, j = jz, z = q[jz]; j > 0; i++, j--) {
                fw = (int) (TWON24 * z);
                iq[i] = (int) (z - TWO24 * fw);
                z = q[j - 1] + fw;
            }
            z = scalbn(z, q0);
            z -= 8.0 * Math.floor(z * 0.125);
            n = (int) z;
            z -= n;
            ih = 0;
            if (q0 > 0) {
                i = iq[jz - 1] >> (24 - q0);
                n += i;
                iq[jz - 1] -= i << (24 - q0);
                ih = iq[jz - 1] >> (23 - q0);
            } else if (q0 == 0) {
                ih = iq[jz - 1] >> 23;
            } else if (z >= 0.5) {
                ih = 2;
            }
            if (ih > 0) {
                n += 1;
                carry = 0;
                for (i = 0; i < jz; i++) {
                    j = iq[i];
                    if (carry == 0) {
                        if (j != 0) {
                            carry = 1;
                            iq[i] = 0x1000000 - j;
                        }
                    } else {
                        iq[i] = 0xffffff - j;
                    }
                }
                if (q0 > 0) {
                    switch (q0) {
                        case 1:
                            iq[jz - 1] &= 0x7fffff;
                            break;
                        case 2:
                            iq[jz - 1] &= 0x3fffff;
                            break;
                        default:
                            break;
                    }
                }
                if (ih == 2) {
                    z = 1.0 - z;
                    if (carry != 0) z -= scalbn(1.0, q0);
                }
            }
            if (z == 0) {
                j = 0;
                for (i = jz - 1; i >= jk; i--) j |= iq[i];
                if (j == 0) {
                    for (k = 1; iq[jk - k] == 0; k++) {
                        // count the terms needed
                    }
                    for (i = jz + 1; i <= jz + k; i++) {
                        f[jx + i] = TWO_OVER_PI[jv + i];
                        for (j = 0, fw = 0.0; j <= jx; j++) fw += x[j] * f[jx + i - j];
                        q[i] = fw;
                    }
                    jz += k;
                    continue;
                }
            }
            break;
        }
        if (z == 0.0) {
            jz -= 1;
            q0 -= 24;
            while (iq[jz] == 0) {
                jz--;
                q0 -= 24;
            }
        } else {
            z = scalbn(z, -q0);
            if (z >= TWO24) {
                fw = (int) (TWON24 * z);
                iq[jz] = (int) (z - TWO24 * fw);
                jz += 1;
                q0 += 24;
                iq[jz] = (int) fw;
            } else {
                iq[jz] = (int) z;
            }
        }
        fw = scalbn(1.0, q0);
        for (i = jz; i >= 0; i--) {
            q[i] = fw * iq[i];
            fw *= TWON24;
        }
        for (i = jz; i >= 0; i--) {
            for (fw = 0.0, k = 0; k <= jp && k <= jz - i; k++) fw += PIO2[k] * q[i + k];
            fq[jz - i] = fw;
        }
        fw = 0.0;
        for (i = jz; i >= 0; i--) fw += fq[i];
        y[0] = ih == 0 ? fw : neg(fw);
        fw = fq[0] - fw;
        for (i = 1; i <= jz; i++) fw += fq[i];
        y[1] = ih == 0 ? fw : neg(fw);
        return n & 7;
    }

    // ---- asin, acos, atan, atan2

    private static final double PIO2_HI = 1.57079632679489655800e+00, PIO2_LO = 6.12323399573676603587e-17,
            PIO4_HI = 7.85398163397448278999e-01, PS0 = 1.66666666666666657415e-01, PS1 = -3.25565818622400915405e-01,
            PS2 = 2.01212532134862925881e-01, PS3 = -4.00555345006794114027e-02, PS4 = 7.91534994289814532176e-04,
            PS5 = 3.47933107596021167570e-05, QS1 = -2.40339491173441421878e+00, QS2 = 2.02094576023350569471e+00,
            QS3 = -6.88283971605453293030e-01, QS4 = 7.70381505559019352791e-02, PI = 3.14159265358979311600e+00;

    /** arc sine (fdlibm e_asin.c). */
    public static double asin(final double x) {
        double t, w, p, q, s;
        final int hx = hi(x);
        final int ix = hx & 0x7fffffff;
        if (ix >= 0x3ff00000) {
            if ((ix - 0x3ff00000 | lo(x)) == 0) return x * PIO2_HI + x * PIO2_LO;
            return (x - x) / (x - x);
        } else if (ix < 0x3fe00000) {
            if (ix < 0x3e400000) return x;
            t = x * x;
            p = t * (PS0 + t * (PS1 + t * (PS2 + t * (PS3 + t * (PS4 + t * PS5)))));
            q = 1.0 + t * (QS1 + t * (QS2 + t * (QS3 + t * QS4)));
            w = p / q;
            return x + x * w;
        }
        w = 1.0 - Math.abs(x);
        t = w * 0.5;
        p = t * (PS0 + t * (PS1 + t * (PS2 + t * (PS3 + t * (PS4 + t * PS5)))));
        q = 1.0 + t * (QS1 + t * (QS2 + t * (QS3 + t * QS4)));
        s = Math.sqrt(t);
        if (ix >= 0x3FEF3333) {
            w = p / q;
            t = PIO2_HI - (2.0 * (s + s * w) - PIO2_LO);
        } else {
            w = chop(s);
            final double c = (t - w * w) / (s + w);
            final double r = p / q;
            p = 2.0 * s * r - (PIO2_LO - 2.0 * c);
            q = PIO4_HI - 2.0 * w;
            t = PIO4_HI - (p - q);
        }
        return hx > 0 ? t : neg(t);
    }

    /** arc cosine (fdlibm e_acos.c). */
    public static double acos(final double x) {
        double z, p, q, r, w, s;
        final int hx = hi(x);
        final int ix = hx & 0x7fffffff;
        if (ix >= 0x3ff00000) {
            if ((ix - 0x3ff00000 | lo(x)) == 0) return hx > 0 ? 0.0 : PI + 2.0 * PIO2_LO;
            return (x - x) / (x - x);
        }
        if (ix < 0x3fe00000) {
            if (ix <= 0x3c600000) return PIO2_HI + PIO2_LO;
            z = x * x;
            p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
            q = 1.0 + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
            r = p / q;
            return PIO2_HI - (x - (PIO2_LO - x * r));
        } else if (hx < 0) {
            z = (1.0 + x) * 0.5;
            p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
            q = 1.0 + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
            s = Math.sqrt(z);
            r = p / q;
            w = r * s - PIO2_LO;
            return PI - 2.0 * (s + w);
        }
        z = (1.0 - x) * 0.5;
        s = Math.sqrt(z);
        final double df = chop(s);
        final double c = (z - df * df) / (s + df);
        p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
        q = 1.0 + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
        r = p / q;
        w = r * s + c;
        return 2.0 * (df + w);
    }

    private static final double[] ATANHI = {
        4.63647609000806093515e-01, 7.85398163397448278999e-01, 9.82793723247329054082e-01, 1.57079632679489655800e+00,
    };
    private static final double[] ATANLO = {
        2.26987774529616870924e-17, 3.06161699786838301793e-17, 1.39033110312309984516e-17, 6.12323399573676603587e-17,
    };
    private static final double[] AT = {
        3.33333333333329318027e-01, -1.99999999998764832476e-01, 1.42857142725034663711e-01,
        -1.11111104054623557880e-01, 9.09088713343650656196e-02, -7.69187620504482999495e-02,
        6.66107313738753120669e-02, -5.83357013379057348645e-02, 4.97687799461593236017e-02,
        -3.65315727442169155270e-02, 1.62858201153657823623e-02,
    };

    /** arc tangent (fdlibm s_atan.c). */
    public static double atan(double x) {
        final int id;
        final int hx = hi(x);
        final int ix = hx & 0x7fffffff;
        if (ix >= 0x44100000) {
            if (ix > 0x7ff00000 || ix == 0x7ff00000 && lo(x) != 0) return x + x;
            return hx > 0 ? ATANHI[3] + ATANLO[3] : -ATANHI[3] - ATANLO[3];
        }
        if (ix < 0x3fdc0000) {
            if (ix < 0x3e200000) return x;
            id = -1;
        } else {
            x = Math.abs(x);
            if (ix < 0x3ff30000) {
                if (ix < 0x3fe60000) {
                    id = 0;
                    x = (2.0 * x - 1.0) / (2.0 + x);
                } else {
                    id = 1;
                    x = (x - 1.0) / (x + 1.0);
                }
            } else if (ix < 0x40038000) {
                id = 2;
                x = (x - 1.5) / (1.0 + 1.5 * x);
            } else {
                id = 3;
                x = -1.0 / x;
            }
        }
        final double z = x * x;
        final double w = z * z;
        final double s1 = z * (AT[0] + w * (AT[2] + w * (AT[4] + w * (AT[6] + w * (AT[8] + w * AT[10])))));
        final double s2 = w * (AT[1] + w * (AT[3] + w * (AT[5] + w * (AT[7] + w * AT[9]))));
        if (id < 0) return x - x * (s1 + s2);
        final double r = ATANHI[id] - ((x * (s1 + s2) - ATANLO[id]) - x);
        return hx < 0 ? neg(r) : r;
    }

    private static final double PI_O_4 = 7.8539816339744827900E-01, PI_O_2 = 1.5707963267948965580E+00,
            PI_LO = 1.2246467991473531772E-16;

    /** The angle of the point (x, y) (fdlibm e_atan2.c). */
    public static double atan2(final double y, final double x) {
        final int hx = hi(x), lx = lo(x), hy = hi(y), ly = lo(y);
        final int ix = hx & 0x7fffffff, iy = hy & 0x7fffffff;
        if ((ix | (lx | -lx) >>> 31) > 0x7ff00000 || (iy | (ly | -ly) >>> 31) > 0x7ff00000) return x + y;
        if ((hx - 0x3ff00000 | lx) == 0) return atan(y);
        final int m = (hy >>> 31) | ((hx >> 30) & 2);
        if ((iy | ly) == 0) {
            switch (m) {
                case 0:
                case 1:
                    return y;
                case 2:
                    return PI + TINY;
                default:
                    return -PI - TINY;
            }
        }
        if ((ix | lx) == 0) return hy < 0 ? -PI_O_2 - TINY : PI_O_2 + TINY;
        if (ix == 0x7ff00000) {
            if (iy == 0x7ff00000) {
                switch (m) {
                    case 0:
                        return PI_O_4 + TINY;
                    case 1:
                        return -PI_O_4 - TINY;
                    case 2:
                        return 3.0 * PI_O_4 + TINY;
                    default:
                        return -3.0 * PI_O_4 - TINY;
                }
            }
            switch (m) {
                case 0:
                    return 0.0;
                case 1:
                    return -0.0;
                case 2:
                    return PI + TINY;
                default:
                    return -PI - TINY;
            }
        }
        if (iy == 0x7ff00000) return hy < 0 ? -PI_O_2 - TINY : PI_O_2 + TINY;
        final int k = (iy - ix) >> 20;
        final double z;
        if (k > 60) {
            z = PI_O_2 + 0.5 * PI_LO;
        } else if (hx < 0 && k < -60) {
            z = 0.0;
        } else {
            z = atan(Math.abs(y / x));
        }
        switch (m) {
            case 0:
                return z;
            case 1:
                return withHi(z, hi(z) ^ 0x80000000);
            case 2:
                return PI - (z - PI_LO);
            default:
                return (z - PI_LO) - PI;
        }
    }

    // ---- cbrt, hypot

    private static final int B1 = 715094163, B2 = 696219795;
    private static final double CB_C = 5.42857142857142815906e-01, CB_D = -7.05306122448979611050e-01,
            CB_E = 1.41428571428571436819e+00, CB_F = 1.60714285714285720630e+00, CB_G = 3.57142857142857150787e-01;

    /** The cube root (fdlibm s_cbrt.c): exact on a perfect cube. */
    public static double cbrt(double x) {
        int hx = hi(x);
        final int sign = hx & 0x80000000;
        hx ^= sign;
        if (hx >= 0x7ff00000) return x + x;
        if ((hx | lo(x)) == 0) return x;
        x = withHi(x, hx);
        double t;
        if (hx < 0x00100000) {
            t = words(0x43500000, 0) * x;
            t = withHi(t, hi(t) / 3 + B2);
        } else {
            t = words(hx / 3 + B1, 0);
        }
        double r = t * t / x;
        double s = CB_C + r * t;
        t *= CB_G + CB_F / (s + CB_E + CB_D / s);
        t = words(hi(t) + 1, 0);
        s = t * t;
        r = x / s;
        final double w = t + t;
        r = (r - t) / (w + r);
        t = t + t * r;
        return withHi(t, hi(t) | sign);
    }

    /** sqrt(x*x + y*y) without overflow or underflow on the way (fdlibm e_hypot.c). */
    public static double hypot(final double x, final double y) {
        double a, b, t1, t2, w;
        int j, k, ha, hb;
        ha = hi(x) & 0x7fffffff;
        hb = hi(y) & 0x7fffffff;
        if (hb > ha) {
            a = y;
            b = x;
            j = ha;
            ha = hb;
            hb = j;
        } else {
            a = x;
            b = y;
        }
        a = withHi(a, ha);
        b = withHi(b, hb);
        if (ha - hb > 0x3c00000) return a + b;
        k = 0;
        if (ha > 0x5f300000) {
            if (ha >= 0x7ff00000) {
                w = a + b;
                if ((ha & 0xfffff | lo(a)) == 0) w = a;
                if ((hb ^ 0x7ff00000 | lo(b)) == 0) w = b;
                return w;
            }
            ha -= 0x25800000;
            hb -= 0x25800000;
            k += 600;
            a = withHi(a, ha);
            b = withHi(b, hb);
        }
        if (hb < 0x20b00000) {
            if (hb <= 0x000fffff) {
                if ((hb | lo(b)) == 0) return a;
                t1 = words(0x7fd00000, 0);
                b *= t1;
                a *= t1;
                k -= 1022;
            } else {
                ha += 0x25800000;
                hb += 0x25800000;
                k -= 600;
                a = withHi(a, ha);
                b = withHi(b, hb);
            }
        }
        w = a - b;
        if (w > b) {
            t1 = words(ha, 0);
            t2 = a - t1;
            w = Math.sqrt(t1 * t1 - (b * neg(b) - t2 * (a + t1)));
        } else {
            a = a + a;
            final double y1 = words(hb, 0);
            final double y2 = b - y1;
            t1 = words(ha + 0x00100000, 0);
            t2 = a - t1;
            w = Math.sqrt(t1 * y1 - (w * neg(w) - (t1 * y2 + t2 * b)));
        }
        if (k != 0) return words(0x3ff00000 + (k << 20), 0) * w;
        return w;
    }

    // ---- expm1, log1p, sinh, cosh, tanh

    private static final double Q1 = -3.33333333333331316428e-02, Q2 = 1.58730158725481460165e-03,
            Q3 = -7.93650757867487942473e-05, Q4 = 4.00821782732936239552e-06, Q5 = -2.01099218183624371326e-07;

    /** e to the x, minus 1, accurate near 0 (fdlibm s_expm1.c). */
    public static double expm1(double x) {
        double y, hi, lo, c = 0, t, e, hxs, hfx, r1;
        int k;
        int hx = hi(x);
        final int xsb = hx & 0x80000000;
        hx &= 0x7fffffff;
        if (hx >= 0x4043687A) {
            if (hx >= 0x40862E42) {
                if (hx >= 0x7ff00000) {
                    if ((hx & 0xfffff | lo(x)) != 0) return x + x;
                    return xsb == 0 ? x : -1.0;
                }
                if (x > O_THRESHOLD) return HUGE * HUGE;
            }
            if (xsb != 0 && x + TINY < 0.0) return TINY - 1.0;
        }
        if (hx > 0x3fd62e42) {
            if (hx < 0x3FF0A2B2) {
                if (xsb == 0) {
                    hi = x - LN2_HI;
                    lo = LN2_LO;
                    k = 1;
                } else {
                    hi = x + LN2_HI;
                    lo = -LN2_LO;
                    k = -1;
                }
            } else {
                k = (int) (INVLN2 * x + (xsb == 0 ? 0.5 : -0.5));
                t = k;
                hi = x - t * LN2_HI;
                lo = t * LN2_LO;
            }
            x = hi - lo;
            c = (hi - x) - lo;
        } else if (hx < 0x3c900000) {
            t = HUGE + x;
            return x - (t - (HUGE + x));
        } else {
            k = 0;
        }
        hfx = 0.5 * x;
        hxs = x * hfx;
        r1 = 1.0 + hxs * (Q1 + hxs * (Q2 + hxs * (Q3 + hxs * (Q4 + hxs * Q5))));
        t = 3.0 - r1 * hfx;
        e = hxs * ((r1 - t) / (6.0 - x * t));
        if (k == 0) return x - (x * e - hxs);
        e = x * (e - c) - c;
        e -= hxs;
        if (k == -1) return 0.5 * (x - e) - 0.5;
        if (k == 1) {
            if (x < -0.25) return -2.0 * (e - (x + 0.5));
            return 1.0 + 2.0 * (x - e);
        }
        if (k <= -2 || k > 56) {
            y = 1.0 - (e - x);
            y = withHi(y, hi(y) + (k << 20));
            return y - 1.0;
        }
        if (k < 20) {
            t = words(0x3ff00000 - (0x200000 >> k), 0);
            y = t - (e - x);
            y = withHi(y, hi(y) + (k << 20));
        } else {
            t = words((0x3ff - k) << 20, 0);
            y = x - (e + t);
            y += 1.0;
            y = withHi(y, hi(y) + (k << 20));
        }
        return y;
    }

    private static final double LP1 = 6.666666666666735130e-01, LP2 = 3.999999999940941908e-01,
            LP3 = 2.857142874366239149e-01, LP4 = 2.222219843214978396e-01, LP5 = 1.818357216161805012e-01,
            LP6 = 1.531383769920937332e-01, LP7 = 1.479819860511658591e-01;

    /** The natural logarithm of 1 + x, accurate near 0 (fdlibm s_log1p.c). */
    public static double log1p(final double x) {
        double hfsq, f = 0, c = 0, s, z, r, u;
        int k, hu = 0;
        final int hx = hi(x);
        final int ax = hx & 0x7fffffff;
        k = 1;
        if (hx < 0x3FDA827A) {
            if (ax >= 0x3ff00000) {
                if (x == -1.0) return Double.NEGATIVE_INFINITY;
                return (x - x) / (x - x);
            }
            if (ax < 0x3e200000) {
                if (TWO54 + x > 0 && ax < 0x3c900000) return x;
                return x - x * x * 0.5;
            }
            if (hx > 0 || hx <= 0xbfd2bec3) {
                k = 0;
                f = x;
                hu = 1;
            }
        }
        if (hx >= 0x7ff00000) return x + x;
        if (k != 0) {
            if (hx < 0x43400000) {
                u = 1.0 + x;
                hu = hi(u);
                k = (hu >> 20) - 1023;
                c = k > 0 ? 1.0 - (u - x) : x - (u - 1.0);
                c /= u;
            } else {
                u = x;
                hu = hi(u);
                k = (hu >> 20) - 1023;
                c = 0;
            }
            hu &= 0x000fffff;
            if (hu < 0x6a09e) {
                u = withHi(u, hu | 0x3ff00000);
            } else {
                k += 1;
                u = withHi(u, hu | 0x3fe00000);
                hu = (0x00100000 - hu) >> 2;
            }
            f = u - 1.0;
        }
        hfsq = 0.5 * f * f;
        if (hu == 0) {
            if (f == 0) {
                if (k == 0) return 0;
                c += k * LN2_LO;
                return k * LN2_HI + c;
            }
            r = hfsq * (1.0 - 0.66666666666666666 * f);
            if (k == 0) return f - r;
            return k * LN2_HI - ((r - (k * LN2_LO + c)) - f);
        }
        s = f / (2.0 + f);
        z = s * s;
        r = z * (LP1 + z * (LP2 + z * (LP3 + z * (LP4 + z * (LP5 + z * (LP6 + z * LP7))))));
        if (k == 0) return f - (hfsq - s * (hfsq + r));
        return k * LN2_HI - ((hfsq - (s * (hfsq + r) + (k * LN2_LO + c))) - f);
    }

    private static final double SHUGE = 1.0e307;

    /** Hyperbolic sine (fdlibm e_sinh.c). */
    public static double sinh(final double x) {
        final int jx = hi(x);
        final int ix = jx & 0x7fffffff;
        if (ix >= 0x7ff00000) return x + x;
        final double h = jx < 0 ? -0.5 : 0.5;
        if (ix < 0x40360000) {
            if (ix < 0x3e300000) return x;
            final double t = expm1(Math.abs(x));
            if (ix < 0x3ff00000) return h * (2.0 * t - t * t / (t + 1.0));
            return h * (t + t / (t + 1.0));
        }
        if (ix < 0x40862E42) return h * exp(Math.abs(x));
        final int lx = lo(x);
        if (ix < 0x408633CE || ix == 0x408633ce && Integer.compareUnsigned(lx, 0x8fb9f87d) <= 0) {
            final double w = exp(0.5 * Math.abs(x));
            final double t = h * w;
            return t * w;
        }
        return x * SHUGE;
    }

    /** Hyperbolic cosine (fdlibm e_cosh.c). */
    public static double cosh(final double x) {
        final int ix = hi(x) & 0x7fffffff;
        if (ix >= 0x7ff00000) return x * x;
        if (ix < 0x3fd62e43) {
            final double t = expm1(Math.abs(x));
            final double w = 1.0 + t;
            if (ix < 0x3c800000) return w;
            return 1.0 + (t * t) / (w + w);
        }
        if (ix < 0x40360000) {
            final double t = exp(Math.abs(x));
            return 0.5 * t + 0.5 / t;
        }
        if (ix < 0x40862E42) return 0.5 * exp(Math.abs(x));
        final int lx = lo(x);
        if (ix < 0x408633CE || ix == 0x408633ce && Integer.compareUnsigned(lx, 0x8fb9f87d) <= 0) {
            final double w = exp(0.5 * Math.abs(x));
            final double t = 0.5 * w;
            return t * w;
        }
        return HUGE * HUGE;
    }

    /** Hyperbolic tangent (fdlibm s_tanh.c): 1 for every large argument. */
    public static double tanh(final double x) {
        final int jx = hi(x);
        final int ix = jx & 0x7fffffff;
        if (ix >= 0x7ff00000) return jx >= 0 ? 1.0 / x + 1.0 : 1.0 / x - 1.0;
        final double z;
        if (ix < 0x40360000) {
            if (ix < 0x3c800000) return x * (1.0 + x);
            if (ix >= 0x3ff00000) {
                final double t = expm1(2.0 * Math.abs(x));
                z = 1.0 - 2.0 / (t + 2.0);
            } else {
                final double t = expm1(-2.0 * Math.abs(x));
                z = neg(t) / (t + 2.0);
            }
        } else {
            z = 1.0 - TINY;
        }
        return jx >= 0 ? z : neg(z);
    }

    // ---- exact by definition (own)

    private static final double TWO52 = 4503599627370496.0;

    /**
     * {@link Math#rint}: the whole number nearest x, a half to the even one; x itself when it is already whole, infinite
     * or NaN; a zero keeps its sign.
     */
    public static double rint(final double x) {
        if (!(Math.abs(x) < TWO52)) return x; // whole already, or NaN or infinite
        final double floor = Math.floor(x);
        final double rest = x - floor; // exact below 2^52
        final double r;
        if (rest < 0.5) {
            r = floor;
        } else if (rest > 0.5) {
            r = floor + 1.0;
        } else {
            r = (((long) floor) & 1L) == 0 ? floor : floor + 1.0;
        }
        return r == 0 ? Math.copySign(0.0, x) : r;
    }

    private static final double LONG_EDGE = 9.223372036854775808E18; // 2^63

    /**
     * {@link Math#round(double)}: the whole number nearest x, a half towards positive infinity (the mathematical floor
     * of x + 1/2, without the rounding of that sum); NaN is 0, and values past the range of a long are its ends.
     */
    public static long round(final double x) {
        if (x != x) return 0L;
        if (!(Math.abs(x) < TWO52)) return toLong(x);
        final double floor = Math.floor(x);
        return (long) floor + (x - floor >= 0.5 ? 1L : 0L);
    }

    /** Java's {@code (long) x}: NaN is 0, out of range is the nearest end (the JavaScript target wraps instead). */
    private static long toLong(final double x) {
        if (x != x) return 0L;
        if (x >= LONG_EDGE) return Long.MAX_VALUE;
        if (x <= -LONG_EDGE) return Long.MIN_VALUE;
        return (long) x;
    }

    private static final double DEGREES_TO_RADIANS = 0.017453292519943295, RADIANS_TO_DEGREES = 57.29577951308232;

    /** {@link StrictMath#toRadians}: one multiplication by pi/180 rounded to a double. */
    public static double toRadians(final double degrees) {
        return degrees * DEGREES_TO_RADIANS;
    }

    /** {@link StrictMath#toDegrees}: one multiplication by 180/pi rounded to a double. */
    public static double toDegrees(final double radians) {
        return radians * RADIANS_TO_DEGREES;
    }

    /** {@link Math#ulp(double)}: the gap from |x| to the next double away from zero. */
    public static double ulp(final double x) {
        final int exponent = (int) ((Double.doubleToRawLongBits(x) >>> 52) & 0x7ff);
        if (exponent == 0x7ff) return Math.abs(x); // NaN, or +infinity for either infinity
        if (exponent <= 52) return Double.longBitsToDouble(1L << Math.max(0, exponent - 1));
        return Double.longBitsToDouble((long) (exponent - 52) << 52);
    }
}
