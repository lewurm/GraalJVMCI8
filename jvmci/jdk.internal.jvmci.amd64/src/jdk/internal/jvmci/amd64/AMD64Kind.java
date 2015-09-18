/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.internal.jvmci.amd64;

import jdk.internal.jvmci.meta.PlatformKind;

public enum AMD64Kind implements PlatformKind {

    // scalar
    BYTE(1),
    WORD(2),
    DWORD(4),
    QWORD(8),
    SINGLE(4),
    DOUBLE(8),

    // SSE2
    V32_BYTE(4, BYTE),
    V32_WORD(4, WORD),
    V64_BYTE(8, BYTE),
    V64_WORD(8, WORD),
    V64_DWORD(8, DWORD),
    V128_BYTE(16, BYTE),
    V128_WORD(16, WORD),
    V128_DWORD(16, DWORD),
    V128_QWORD(16, QWORD),
    V128_SINGLE(16, SINGLE),
    V128_DOUBLE(16, DOUBLE),

    // AVX
    V256_BYTE(32, BYTE),
    V256_WORD(32, WORD),
    V256_DWORD(32, DWORD),
    V256_QWORD(32, QWORD),
    V256_SINGLE(32, SINGLE),
    V256_DOUBLE(32, DOUBLE);

    private final int size;
    private final int vectorLength;

    private final AMD64Kind scalar;
    private final EnumKey<AMD64Kind> key = new EnumKey<>(this);

    private AMD64Kind(int size) {
        this.size = size;
        this.scalar = this;
        this.vectorLength = 1;
    }

    private AMD64Kind(int size, AMD64Kind scalar) {
        this.size = size;
        this.scalar = scalar;

        assert size % scalar.size == 0;
        this.vectorLength = size / scalar.size;
    }

    public AMD64Kind getScalar() {
        return scalar;
    }

    public int getSizeInBytes() {
        return size;
    }

    public int getVectorLength() {
        return vectorLength;
    }

    public Key getKey() {
        return key;
    }

    public boolean isInteger() {
        switch (this) {
            case BYTE:
            case WORD:
            case DWORD:
            case QWORD:
                return true;
            default:
                return false;
        }
    }

    public boolean isXMM() {
        return !isInteger();
    }

    public char getTypeChar() {
        switch (this) {
            case BYTE:
                return 'b';
            case WORD:
                return 'w';
            case DWORD:
                return 'd';
            case QWORD:
                return 'q';
            case SINGLE:
                return 'S';
            case DOUBLE:
                return 'D';
            case V32_BYTE:
            case V32_WORD:
            case V64_BYTE:
            case V64_WORD:
            case V64_DWORD:
                return 'v';
            case V128_BYTE:
            case V128_WORD:
            case V128_DWORD:
            case V128_QWORD:
            case V128_SINGLE:
            case V128_DOUBLE:
                return 'x';
            case V256_BYTE:
            case V256_WORD:
            case V256_DWORD:
            case V256_QWORD:
            case V256_SINGLE:
            case V256_DOUBLE:
                return 'y';
            default:
                return '-';
        }
    }
}
