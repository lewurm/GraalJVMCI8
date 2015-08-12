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

import static jdk.internal.jvmci.inittimer.InitTimer.*;

import java.util.*;

import jdk.internal.jvmci.code.*;
import jdk.internal.jvmci.common.*;
import jdk.internal.jvmci.inittimer.*;
import jdk.internal.jvmci.meta.*;
import jdk.internal.jvmci.options.*;
import jdk.internal.jvmci.runtime.*;
import jdk.internal.jvmci.service.*;

//JaCoCo Exclude

public final class HotSpotJVMCIRuntime implements HotSpotJVMCIRuntimeProvider, HotSpotProxified {

    private static final HotSpotJVMCIRuntime instance;

    static {
        try (InitTimer t0 = timer("HotSpotJVMCIRuntime.<clinit>")) {
            try (InitTimer t = timer("HotSpotJVMCIRuntime.<init>")) {
                instance = new HotSpotJVMCIRuntime();
            }

            try (InitTimer t = timer("HotSpotJVMCIRuntime.completeInitialization")) {
                // Why deferred initialization? See comment in completeInitialization().
                instance.completeInitialization();
            }
        }
    }

    /**
     * Gets the singleton {@link HotSpotJVMCIRuntime} object.
     */
    public static HotSpotJVMCIRuntime runtime() {
        assert instance != null;
        return instance;
    }

    /**
     * Do deferred initialization.
     */
    public void completeInitialization() {

        // Proxies for the VM/Compiler interfaces cannot be initialized
        // in the constructor as proxy creation causes static
        // initializers to be executed for all the types involved in the
        // proxied methods. Some of these static initializers (e.g. in
        // HotSpotMethodData) rely on the static 'instance' field being set
        // to retrieve configuration details.
        CompilerToVM toVM = this.compilerToVm;

        for (HotSpotVMEventListener vmEventListener : vmEventListeners) {
            toVM = vmEventListener.completeInitialization(this, toVM);
        }

        this.compilerToVm = toVM;
    }

    public static class Options {

        // @formatter:off
        @Option(help = "The JVMCI runtime configuration to use", type = OptionType.Expert)
        public static final OptionValue<String> JVMCIRuntime = new OptionValue<>("");

        @Option(help = "File to which logging is sent.  A %p in the name will be replaced with a string identifying the process, usually the process id.", type = OptionType.Expert)
        public static final PrintStreamOption LogFile = new PrintStreamOption();
        // @formatter:on
    }

    public static HotSpotJVMCIBackendFactory findFactory(String architecture) {
        HotSpotJVMCIBackendFactory basic = null;
        HotSpotJVMCIBackendFactory selected = null;
        HotSpotJVMCIBackendFactory nonBasic = null;
        int nonBasicCount = 0;

        for (HotSpotJVMCIBackendFactory factory : Services.load(HotSpotJVMCIBackendFactory.class)) {
            if (factory.getArchitecture().equalsIgnoreCase(architecture)) {
                if (factory.getJVMCIRuntimeName().equals(Options.JVMCIRuntime.getValue())) {
                    assert selected == null || checkFactoryOverriding(selected, factory);
                    selected = factory;
                }
                if (factory.getJVMCIRuntimeName().equals("basic")) {
                    assert basic == null || checkFactoryOverriding(basic, factory);
                    basic = factory;
                } else {
                    nonBasic = factory;
                    nonBasicCount++;
                }
            }
        }

        if (selected != null) {
            return selected;
        } else {
            if (!Options.JVMCIRuntime.getValue().equals("")) {
                // Fail fast if a non-default value for JVMCIRuntime was specified
                // and the corresponding factory is not available
                throw new JVMCIError("Specified runtime \"%s\" not available for the %s architecture", Options.JVMCIRuntime.getValue(), architecture);
            } else if (nonBasicCount == 1) {
                // If there is exactly one non-basic runtime, select this one.
                return nonBasic;
            } else {
                return basic;
            }
        }
    }

    /**
     * Checks that a factory overriding is valid. A factory B can only override/replace a factory A
     * if the B.getClass() is a subclass of A.getClass(). This models the assumption that B is
     * extends the behavior of A and has therefore understood the behavior expected of A.
     *
     * @param baseFactory
     * @param overridingFactory
     */
    private static boolean checkFactoryOverriding(HotSpotJVMCIBackendFactory baseFactory, HotSpotJVMCIBackendFactory overridingFactory) {
        return baseFactory.getClass().isAssignableFrom(overridingFactory.getClass());
    }

    /**
     * Gets the kind of a word value on the {@linkplain #getHostJVMCIBackend() host} backend.
     */
    public static Kind getHostWordKind() {
        return instance.getHostJVMCIBackend().getCodeCache().getTarget().wordKind;
    }

    protected/* final */CompilerToVM compilerToVm;

    protected final HotSpotVMConfig config;
    private final JVMCIBackend hostBackend;

    /**
     * JVMCI mirrors are stored as a {@link ClassValue} associated with the {@link Class} of the
     * type. This data structure stores both {@link HotSpotResolvedObjectType} and
     * {@link HotSpotResolvedPrimitiveType} types.
     */
    private final ClassValue<ResolvedJavaType> jvmciMirrors = new ClassValue<ResolvedJavaType>() {
        @Override
        protected ResolvedJavaType computeValue(Class<?> javaClass) {
            if (javaClass.isPrimitive()) {
                Kind kind = Kind.fromJavaClass(javaClass);
                return new HotSpotResolvedPrimitiveType(kind);
            } else {
                return new HotSpotResolvedObjectTypeImpl(javaClass);
            }
        }
    };

    private final Map<Class<? extends Architecture>, JVMCIBackend> backends = new HashMap<>();

    private final Iterable<HotSpotVMEventListener> vmEventListeners;

    private HotSpotJVMCIRuntime() {
        CompilerToVM toVM = new CompilerToVMImpl();
        compilerToVm = toVM;
        try (InitTimer t = timer("HotSpotVMConfig<init>")) {
            config = new HotSpotVMConfig(compilerToVm);
        }

        String hostArchitecture = config.getHostArchitectureName();

        HotSpotJVMCIBackendFactory factory;
        try (InitTimer t = timer("find factory:", hostArchitecture)) {
            factory = findFactory(hostArchitecture);
        }
        try (InitTimer t = timer("create JVMCI backend:", hostArchitecture)) {
            hostBackend = registerBackend(factory.createJVMCIBackend(this, null));
        }

        Iterable<HotSpotVMEventListener> listeners = Services.load(HotSpotVMEventListener.class);
        if (!listeners.iterator().hasNext()) {
            listeners = Arrays.asList(new HotSpotVMEventListener() {
            });
        }
        vmEventListeners = listeners;
    }

    private JVMCIBackend registerBackend(JVMCIBackend backend) {
        Class<? extends Architecture> arch = backend.getCodeCache().getTarget().arch.getClass();
        JVMCIBackend oldValue = backends.put(arch, backend);
        assert oldValue == null : "cannot overwrite existing backend for architecture " + arch.getSimpleName();
        return backend;
    }

    public ResolvedJavaType fromClass(Class<?> javaClass) {
        return jvmciMirrors.get(javaClass);
    }

    public HotSpotVMConfig getConfig() {
        return config;
    }

    public CompilerToVM getCompilerToVM() {
        return compilerToVm;
    }

    public JavaType lookupType(String name, HotSpotResolvedObjectType accessingType, boolean resolve) {
        Objects.requireNonNull(accessingType, "cannot resolve type without an accessing class");
        // If the name represents a primitive type we can short-circuit the lookup.
        if (name.length() == 1) {
            Kind kind = Kind.fromPrimitiveOrVoidTypeChar(name.charAt(0));
            return fromClass(kind.toJavaClass());
        }

        // Resolve non-primitive types in the VM.
        HotSpotResolvedObjectTypeImpl hsAccessingType = (HotSpotResolvedObjectTypeImpl) accessingType;
        final long metaspaceKlass = compilerToVm.lookupType(name, hsAccessingType.mirror(), resolve);

        if (metaspaceKlass == 0L) {
            assert resolve == false;
            return HotSpotUnresolvedJavaType.create(this, name);
        }
        return HotSpotResolvedObjectTypeImpl.fromMetaspaceKlass(metaspaceKlass);
    }

    public JVMCIBackend getHostJVMCIBackend() {
        return hostBackend;
    }

    public <T extends Architecture> JVMCIBackend getJVMCIBackend(Class<T> arch) {
        assert arch != Architecture.class;
        return backends.get(arch);
    }

    public Map<Class<? extends Architecture>, JVMCIBackend> getBackends() {
        return Collections.unmodifiableMap(backends);
    }

    /**
     * Called from the VM.
     */
    @SuppressWarnings({"unused"})
    private void compileMetaspaceMethod(long metaspaceMethod, int entryBCI, long jvmciEnv, int id) {
        for (HotSpotVMEventListener vmEventListener : vmEventListeners) {
            vmEventListener.compileMetaspaceMethod(metaspaceMethod, entryBCI, jvmciEnv, id);
        }
    }

    /**
     * Called from the VM.
     */
    @SuppressWarnings({"unused"})
    private void compileTheWorld() throws Throwable {
        for (HotSpotVMEventListener vmEventListener : vmEventListeners) {
            vmEventListener.notifyCompileTheWorld();
        }
    }

    /**
     * Shuts down the runtime.
     *
     * Called from the VM.
     */
    @SuppressWarnings({"unused"})
    private void shutdown() throws Exception {
        for (HotSpotVMEventListener vmEventListener : vmEventListeners) {
            vmEventListener.notifyShutdown();
        }
    }

    /**
     * Shuts down the runtime.
     *
     * Called from the VM.
     *
     * @param hotSpotCodeCacheProvider
     */
    void notifyInstall(HotSpotCodeCacheProvider hotSpotCodeCacheProvider, InstalledCode installedCode, CompilationResult compResult) {
        for (HotSpotVMEventListener vmEventListener : vmEventListeners) {
            vmEventListener.notifyInstall(hotSpotCodeCacheProvider, installedCode, compResult);
        }
    }
}
