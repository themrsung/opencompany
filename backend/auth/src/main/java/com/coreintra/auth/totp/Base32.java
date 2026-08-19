package com.coreintra.auth.totp;

import com.coreintra.compat.Texts;

/**
 * RFC 4648 base32, the encoding authenticator apps expect for a TOTP secret.
 *
 * <p>Written out rather than pulled in — it is forty lines, and the alternative
 * is another dependency in the authentication path.
 *
 * <p>Encoding omits padding, because the {@code otpauth://} URI convention does
 * and some apps reject the {@code =} characters. Decoding accepts padding,
 * lowercase, and spaces, because users retype secrets by hand off a printout
 * and every one of those variations turns up.
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] DECODE_TABLE = new int[128];

    static {
        for (int i = 0; i < DECODE_TABLE.length; i++) {
            DECODE_TABLE[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length(); i++) {
            DECODE_TABLE[ALPHABET.charAt(i)] = i;
            DECODE_TABLE[Character.toLowerCase(ALPHABET.charAt(i))] = i;
        }
    }

    private Base32() {
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    /**
     * @throws IllegalArgumentException on a character outside the alphabet,
     *         naming it — a user retyping a secret needs to know which one
     */
    public static byte[] decode(String encoded) {
        if (Texts.isBlank(encoded)) {
            return new byte[0];
        }
        String cleaned = encoded.replace("=", "").replace(" ", "").replace("-", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            int value = c < DECODE_TABLE.length ? DECODE_TABLE[c] : -1;
            if (value < 0) {
                throw new IllegalArgumentException(
                        "'" + c + "' is not a base32 character (A-Z, 2-7)");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    /** Groups into fours, the way a secret is printed for manual entry. */
    public static String formatForDisplay(String encoded) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < encoded.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(' ');
            }
            out.append(encoded.charAt(i));
        }
        return out.toString();
    }
}
