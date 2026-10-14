package com.rs.lib.hook;

import java.math.BigInteger;
import java.util.Comparator;

/**
 * Orders version strings the way people read them: {@code 1.10 > 1.9}, {@code 1.2 == 1.2.0},
 * a pre-release ({@code 2.0-beta}) before its release, build metadata ({@code +abc}) ignored.
 */
public final class SemverComparator implements Comparator<String> {

    public static final SemverComparator INSTANCE = new SemverComparator();

    @Override
    public int compare(String a, String b) {
        return compareVersions(a, b);
    }

    public static int compareVersions(String a, String b) {
        String left = stripBuild(a == null ? "" : a.trim());
        String right = stripBuild(b == null ? "" : b.trim());
        int dashL = left.indexOf('-');
        int dashR = right.indexOf('-');
        String coreL = dashL < 0 ? left : left.substring(0, dashL);
        String coreR = dashR < 0 ? right : right.substring(0, dashR);
        String preL = dashL < 0 ? "" : left.substring(dashL + 1);
        String preR = dashR < 0 ? "" : right.substring(dashR + 1);

        int core = compareCore(coreL, coreR);
        if (core != 0) {
            return core;
        }
        if (preL.isEmpty() || preR.isEmpty()) {
            // A release is newer than any pre-release of the same core.
            return preL.isEmpty() ? (preR.isEmpty() ? 0 : 1) : -1;
        }
        return comparePre(preL, preR);
    }

    private static String stripBuild(String v) {
        int plus = v.indexOf('+');
        return plus < 0 ? v : v.substring(0, plus);
    }

    private static int compareCore(String a, String b) {
        String[] pa = a.split("\\.", -1);
        String[] pb = b.split("\\.", -1);
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            BigInteger x = leadingNumber(i < pa.length ? pa[i] : "");
            BigInteger y = leadingNumber(i < pb.length ? pb[i] : "");
            int c = x.compareTo(y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static BigInteger leadingNumber(String segment) {
        int end = 0;
        while (end < segment.length() && Character.isDigit(segment.charAt(end))) {
            end++;
        }
        return end == 0 ? BigInteger.ZERO : new BigInteger(segment.substring(0, end));
    }

    private static int comparePre(String a, String b) {
        String[] pa = a.split("\\.", -1);
        String[] pb = b.split("\\.", -1);
        int n = Math.min(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            boolean numA = isNumber(pa[i]);
            boolean numB = isNumber(pb[i]);
            int c;
            if (numA && numB) {
                c = new BigInteger(pa[i]).compareTo(new BigInteger(pb[i]));
            } else if (numA != numB) {
                c = numA ? -1 : 1;
            } else {
                c = pa[i].compareTo(pb[i]);
            }
            if (c != 0) {
                return Integer.signum(c);
            }
        }
        return Integer.compare(pa.length, pb.length);
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
