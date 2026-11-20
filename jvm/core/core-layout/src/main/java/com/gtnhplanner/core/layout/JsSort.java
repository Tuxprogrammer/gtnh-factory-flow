package com.gtnhplanner.core.layout;

import java.util.Comparator;
import java.util.List;

/**
 * JavaScript's {@code Array.prototype.sort} as V8 runs it (TimSort after Python's listsort: runs found and extended by
 * binary insertion to the minimum run length, merged with galloping), step for step, so a comparator gives the order the
 * website gets even where it is not a consistent order. The website's router and Arrange have such comparators (the
 * dock order mirrors the far card's side, which ties can make cyclic), and a different algorithm then gives a different
 * board: the JDK's List.sort agrees with V8 only below 32 elements, and the web compiler's library sort not at all.
 * Every list sort in core-layout goes through here (a double[] has one order, and Arrays.sort gives it).
 */
public final class JsSort {

    private JsSort() {}

    private static final int MIN_GALLOP = 7;

    /** Sorts the list in place, as {@code list.sort(comparator)} sorts a JavaScript array. */
    @SuppressWarnings("unchecked")
    public static <T> void sort(final List<T> list, final Comparator<? super T> comparator) {
        final int length = list.size();
        if (length < 2) return;
        final Object[] work = list.toArray();
        new Run<>((T[]) work, comparator).sort();
        for (int i = 0; i < length; i++) list.set(i, (T) work[i]);
    }

    /** One sort's state: the work array, the pending runs and the gallop threshold. */
    private static final class Run<T> {

        private final T[] a;
        private final Comparator<? super T> c;
        private final int[] runBase = new int[85], runLength = new int[85];
        private int runs;
        private int minGallop = MIN_GALLOP;

        Run(final T[] a, final Comparator<? super T> c) {
            this.a = a;
            this.c = c;
        }

        private int compare(final T x, final T y) {
            return c.compare(x, y);
        }

        void sort() {
            int remaining = a.length;
            int low = 0;
            final int minRun = minRunLength(remaining);
            while (remaining != 0) {
                int current = countAndMakeRun(low, low + remaining);
                if (current < minRun) {
                    final int forced = Math.min(minRun, remaining);
                    binaryInsertionSort(low, low + current, low + forced);
                    current = forced;
                }
                runBase[runs] = low;
                runLength[runs] = current;
                runs++;
                mergeCollapse();
                low += current;
                remaining -= current;
            }
            while (runs > 1) {
                int n = runs - 2;
                if (n > 0 && runLength[n - 1] < runLength[n + 1]) n--;
                mergeAt(n);
            }
        }

        private static int minRunLength(final int length) {
            int n = length, r = 0;
            while (n >= 64) {
                r |= n & 1;
                n >>= 1;
            }
            return n + r;
        }

        /** The run starting at {@code low}: its length, a strictly descending one reversed in place. */
        private int countAndMakeRun(final int lowArg, final int high) {
            final int low = lowArg + 1;
            if (low == high) return 1;
            int length = 2;
            final boolean descending = compare(a[low], a[low - 1]) < 0;
            T previous = a[low];
            for (int i = low + 1; i < high; i++) {
                final T current = a[i];
                final int order = compare(current, previous);
                if (descending) {
                    if (order >= 0) break;
                } else if (order < 0) break;
                previous = current;
                length++;
            }
            if (descending) {
                for (int i = lowArg, j = lowArg + length - 1; i < j; i++, j--) {
                    final T t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                }
            }
            return length;
        }

        /** Extends the sorted {@code [low, start)} to {@code [low, high)}, inserting each element after its equals. */
        private void binaryInsertionSort(final int low, final int startArg, final int high) {
            for (int start = low == startArg ? startArg + 1 : startArg; start < high; start++) {
                int left = low, right = start;
                final T pivot = a[start];
                while (left < right) {
                    final int mid = left + ((right - left) >> 1);
                    if (compare(pivot, a[mid]) < 0) right = mid;
                    else left = mid + 1;
                }
                for (int p = start; p > left; p--) a[p] = a[p - 1];
                a[left] = pivot;
            }
        }

        private boolean invariant(final int n) {
            if (n < 2) return true;
            return runLength[n - 2] > runLength[n - 1] + runLength[n];
        }

        private void mergeCollapse() {
            while (runs > 1) {
                int n = runs - 2;
                if (!invariant(n + 1) || !invariant(n)) {
                    if (runLength[n - 1] < runLength[n + 1]) n--;
                    mergeAt(n);
                } else if (runLength[n] <= runLength[n + 1]) {
                    mergeAt(n);
                } else break;
            }
        }

        private void mergeAt(final int i) {
            int baseA = runBase[i], lengthA = runLength[i];
            final int baseB = runBase[i + 1];
            int lengthB = runLength[i + 1];
            runLength[i] = lengthA + lengthB;
            if (i == runs - 3) {
                runBase[i + 1] = runBase[i + 2];
                runLength[i + 1] = runLength[i + 2];
            }
            runs--;
            final int k = gallopRight(a, a[baseB], baseA, lengthA, 0);
            baseA += k;
            lengthA -= k;
            if (lengthA == 0) return;
            lengthB = gallopLeft(a, a[baseA + lengthA - 1], baseB, lengthB, lengthB - 1);
            if (lengthB == 0) return;
            if (lengthA <= lengthB) mergeLow(baseA, lengthA, baseB, lengthB);
            else mergeHigh(baseA, lengthA, baseB, lengthB);
        }

        /** The first index k in {@code [base, base + length)} with key at or before x[k] (x[k-1] < key <= x[k]). */
        private int gallopLeft(final T[] x, final T key, final int base, final int length, final int hint) {
            int lastOfs = 0, offset = 1;
            if (compare(x[base + hint], key) < 0) {
                final int maxOfs = length - hint;
                while (offset < maxOfs) {
                    if (compare(x[base + hint + offset], key) >= 0) break;
                    lastOfs = offset;
                    offset = (offset << 1) + 1;
                    if (offset <= 0) offset = maxOfs;
                }
                if (offset > maxOfs) offset = maxOfs;
                lastOfs += hint;
                offset += hint;
            } else {
                final int maxOfs = hint + 1;
                while (offset < maxOfs) {
                    if (compare(x[base + hint - offset], key) < 0) break;
                    lastOfs = offset;
                    offset = (offset << 1) + 1;
                    if (offset <= 0) offset = maxOfs;
                }
                if (offset > maxOfs) offset = maxOfs;
                final int t = lastOfs;
                lastOfs = hint - offset;
                offset = hint - t;
            }
            lastOfs++;
            while (lastOfs < offset) {
                final int m = lastOfs + ((offset - lastOfs) >> 1);
                if (compare(x[base + m], key) < 0) lastOfs = m + 1;
                else offset = m;
            }
            return offset;
        }

        /** The first index k with key before x[k] (x[k-1] <= key < x[k]). */
        private int gallopRight(final T[] x, final T key, final int base, final int length, final int hint) {
            int lastOfs = 0, offset = 1;
            if (compare(key, x[base + hint]) < 0) {
                final int maxOfs = hint + 1;
                while (offset < maxOfs) {
                    if (compare(key, x[base + hint - offset]) >= 0) break;
                    lastOfs = offset;
                    offset = (offset << 1) + 1;
                    if (offset <= 0) offset = maxOfs;
                }
                if (offset > maxOfs) offset = maxOfs;
                final int t = lastOfs;
                lastOfs = hint - offset;
                offset = hint - t;
            } else {
                final int maxOfs = length - hint;
                while (offset < maxOfs) {
                    if (compare(key, x[base + hint + offset]) < 0) break;
                    lastOfs = offset;
                    offset = (offset << 1) + 1;
                    if (offset <= 0) offset = maxOfs;
                }
                if (offset > maxOfs) offset = maxOfs;
                lastOfs += hint;
                offset += hint;
            }
            lastOfs++;
            while (lastOfs < offset) {
                final int m = lastOfs + ((offset - lastOfs) >> 1);
                if (compare(key, x[base + m]) < 0) offset = m;
                else lastOfs = m + 1;
            }
            return offset;
        }

        private void mergeLow(final int baseA, final int lengthAArg, final int baseB, final int lengthBArg) {
            int lengthA = lengthAArg, lengthB = lengthBArg;
            final T[] temp = java.util.Arrays.copyOfRange(a, baseA, baseA + lengthA);
            int dest = baseA, cursorTemp = 0, cursorB = baseB;
            a[dest++] = a[cursorB++];
            boolean copyB = false;
            outer: {
                if (--lengthB == 0) break outer;
                if (lengthA == 1) {
                    copyB = true;
                    break outer;
                }
                int gallop = minGallop;
                while (true) {
                    int winsA = 0, winsB = 0;
                    while (true) {
                        if (compare(a[cursorB], temp[cursorTemp]) < 0) {
                            a[dest++] = a[cursorB++];
                            winsB++;
                            lengthB--;
                            winsA = 0;
                            if (lengthB == 0) break outer;
                            if (winsB >= gallop) break;
                        } else {
                            a[dest++] = temp[cursorTemp++];
                            winsA++;
                            lengthA--;
                            winsB = 0;
                            if (lengthA == 1) {
                                copyB = true;
                                break outer;
                            }
                            if (winsA >= gallop) break;
                        }
                    }
                    gallop++;
                    boolean first = true;
                    while (winsA >= MIN_GALLOP || winsB >= MIN_GALLOP || first) {
                        first = false;
                        gallop = Math.max(1, gallop - 1);
                        minGallop = gallop;
                        winsA = gallopRight(temp, a[cursorB], cursorTemp, lengthA, 0);
                        if (winsA > 0) {
                            System.arraycopy(temp, cursorTemp, a, dest, winsA);
                            dest += winsA;
                            cursorTemp += winsA;
                            lengthA -= winsA;
                            if (lengthA == 1) {
                                copyB = true;
                                break outer;
                            }
                            // Impossible with a consistent comparator; not assumed.
                            if (lengthA == 0) break outer;
                        }
                        a[dest++] = a[cursorB++];
                        if (--lengthB == 0) break outer;
                        winsB = gallopLeft(a, temp[cursorTemp], cursorB, lengthB, 0);
                        if (winsB > 0) {
                            System.arraycopy(a, cursorB, a, dest, winsB);
                            dest += winsB;
                            cursorB += winsB;
                            lengthB -= winsB;
                            if (lengthB == 0) break outer;
                        }
                        a[dest++] = temp[cursorTemp++];
                        if (--lengthA == 1) {
                            copyB = true;
                            break outer;
                        }
                    }
                    gallop++;
                    minGallop = gallop;
                }
            }
            if (copyB) {
                // The last element of run A belongs at the end of the merge.
                System.arraycopy(a, cursorB, a, dest, lengthB);
                a[dest + lengthB] = temp[cursorTemp];
            } else if (lengthA > 0) System.arraycopy(temp, cursorTemp, a, dest, lengthA);
        }

        private void mergeHigh(final int baseA, final int lengthAArg, final int baseB, final int lengthBArg) {
            int lengthA = lengthAArg, lengthB = lengthBArg;
            final T[] temp = java.util.Arrays.copyOfRange(a, baseB, baseB + lengthB);
            int dest = baseB + lengthB - 1, cursorTemp = lengthB - 1, cursorA = baseA + lengthA - 1;
            a[dest--] = a[cursorA--];
            boolean copyA = false;
            outer: {
                if (--lengthA == 0) break outer;
                if (lengthB == 1) {
                    copyA = true;
                    break outer;
                }
                int gallop = minGallop;
                while (true) {
                    int winsA = 0, winsB = 0;
                    while (true) {
                        if (compare(temp[cursorTemp], a[cursorA]) < 0) {
                            a[dest--] = a[cursorA--];
                            winsA++;
                            lengthA--;
                            winsB = 0;
                            if (lengthA == 0) break outer;
                            if (winsA >= gallop) break;
                        } else {
                            a[dest--] = temp[cursorTemp--];
                            winsB++;
                            lengthB--;
                            winsA = 0;
                            if (lengthB == 1) {
                                copyA = true;
                                break outer;
                            }
                            if (winsB >= gallop) break;
                        }
                    }
                    gallop++;
                    boolean first = true;
                    while (winsA >= MIN_GALLOP || winsB >= MIN_GALLOP || first) {
                        first = false;
                        gallop = Math.max(1, gallop - 1);
                        minGallop = gallop;
                        int k = gallopRight(a, temp[cursorTemp], baseA, lengthA, lengthA - 1);
                        winsA = lengthA - k;
                        if (winsA > 0) {
                            dest -= winsA;
                            cursorA -= winsA;
                            System.arraycopy(a, cursorA + 1, a, dest + 1, winsA);
                            lengthA -= winsA;
                            if (lengthA == 0) break outer;
                        }
                        a[dest--] = temp[cursorTemp--];
                        if (--lengthB == 1) {
                            copyA = true;
                            break outer;
                        }
                        k = gallopLeft(temp, a[cursorA], 0, lengthB, lengthB - 1);
                        winsB = lengthB - k;
                        if (winsB > 0) {
                            dest -= winsB;
                            cursorTemp -= winsB;
                            System.arraycopy(temp, cursorTemp + 1, a, dest + 1, winsB);
                            lengthB -= winsB;
                            if (lengthB == 1) {
                                copyA = true;
                                break outer;
                            }
                            // Impossible with a consistent comparator; not assumed.
                            if (lengthB == 0) break outer;
                        }
                        a[dest--] = a[cursorA--];
                        if (--lengthA == 0) break outer;
                    }
                    gallop++;
                    minGallop = gallop;
                }
            }
            if (copyA) {
                // The first element of run B belongs at the front of the merge.
                dest -= lengthA;
                cursorA -= lengthA;
                System.arraycopy(a, cursorA + 1, a, dest + 1, lengthA);
                a[dest] = temp[cursorTemp];
            } else if (lengthB > 0) System.arraycopy(temp, 0, a, dest - (lengthB - 1), lengthB);
        }
    }
}
