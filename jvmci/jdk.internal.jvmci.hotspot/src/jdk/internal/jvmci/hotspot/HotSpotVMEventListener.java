/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.jvmci.hotspot;

import jdk.internal.jvmci.code.CompilationResult;
import jdk.internal.jvmci.code.InstalledCode;
import jdk.internal.jvmci.meta.JVMCIMetaAccessContext;
import jdk.internal.jvmci.meta.ResolvedJavaType;

public interface HotSpotVMEventListener {

    /**
     * Notifies this client that the VM is shutting down.
     */
    default void notifyShutdown() {
    }

    /**
     * Notify on successful install into the code cache.
     *
     * @param hotSpotCodeCacheProvider
     * @param installedCode
     * @param compResult
     */
    default void notifyInstall(HotSpotCodeCacheProvider hotSpotCodeCacheProvider, InstalledCode installedCode, CompilationResult compResult) {
    }

    /**
     * Create a custom {@link JVMCIMetaAccessContext} to be used for managing the lifetime of loaded
     * metadata. It a custom one isn't created then the default implementation will be a single
     * context with globally shared instances of {@link ResolvedJavaType} that are never released.
     *
     * @param hotSpotJVMCIRuntime
     * @return a custom context or null
     */
    default JVMCIMetaAccessContext createMetaAccessContext(HotSpotJVMCIRuntime hotSpotJVMCIRuntime) {
        return null;
    }
}
