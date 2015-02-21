/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.test;

import org.junit.*;

import com.oracle.graal.api.replacements.*;
import com.oracle.graal.hotspot.replacements.*;
import com.oracle.graal.replacements.test.*;

/**
 * Tests HotSpot specific {@link MethodSubstitution}s.
 */
public class HotSpotMethodSubstitutionTest extends MethodSubstitutionTest {

    @Test
    public void testObjectSubstitutions() {
        TestClassA obj = new TestClassA();

        test("getClass0");
        test("objectHashCode");

        test("getClass0", "a string");
        test("objectHashCode", obj);
    }

    @SuppressWarnings("all")
    public static Class<?> getClass0(Object obj) {
        return obj.getClass();
    }

    @SuppressWarnings("all")
    public static int objectHashCode(TestClassA obj) {
        return obj.hashCode();
    }

    @Test
    public void testClassSubstitutions() {
        test("getModifiers");
        test("isInterface");
        test("isArray");
        test("isPrimitive");
        test("getSuperClass");
        test("getComponentType");

        for (Class<?> c : new Class[]{getClass(), Cloneable.class, int[].class, String[][].class}) {
            test("getModifiers", c);
            test("isInterface", c);
            test("isArray", c);
            test("isPrimitive", c);
            test("getSuperClass", c);
            test("getComponentType", c);
        }
    }

    @SuppressWarnings("all")
    public static int getModifiers(Class<?> clazz) {
        return clazz.getModifiers();
    }

    @SuppressWarnings("all")
    public static boolean isInterface(Class<?> clazz) {
        return clazz.isInterface();
    }

    @SuppressWarnings("all")
    public static boolean isArray(Class<?> clazz) {
        return clazz.isArray();
    }

    @SuppressWarnings("all")
    public static boolean isPrimitive(Class<?> clazz) {
        return clazz.isPrimitive();
    }

    @SuppressWarnings("all")
    public static Class<?> getSuperClass(Class<?> clazz) {
        return clazz.getSuperclass();
    }

    @SuppressWarnings("all")
    public static Class<?> getComponentType(Class<?> clazz) {
        return clazz.getComponentType();
    }

    @Test
    public void testThreadSubstitutions() {
        test("currentThread");
        test("threadIsInterrupted");
        test("threadInterrupted");

        Thread currentThread = Thread.currentThread();
        test("currentThread", currentThread);
        test("threadIsInterrupted", currentThread);
    }

    @SuppressWarnings("all")
    public static boolean currentThread(Thread other) {
        return Thread.currentThread() == other;
    }

    @SuppressWarnings("all")
    public static boolean threadIsInterrupted(Thread thread) {
        return thread.isInterrupted();
    }

    @SuppressWarnings("all")
    public static boolean threadInterrupted() {
        return Thread.interrupted();
    }

    @Test
    public void testSystemSubstitutions() {
        test("systemTime");
        test("systemIdentityHashCode");

        SystemSubstitutions.currentTimeMillis();
        SystemSubstitutions.nanoTime();
        for (Object o : new Object[]{this, new int[5], new String[2][], new Object()}) {
            test("systemIdentityHashCode", o);
        }
    }

    @SuppressWarnings("all")
    public static long systemTime() {
        return System.currentTimeMillis() + System.nanoTime();
    }

    @SuppressWarnings("all")
    public static int systemIdentityHashCode(Object obj) {
        return System.identityHashCode(obj);
    }

    private static class TestClassA {
    }
}
