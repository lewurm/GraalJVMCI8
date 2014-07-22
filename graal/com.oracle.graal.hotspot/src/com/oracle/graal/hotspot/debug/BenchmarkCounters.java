/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.debug;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import sun.misc.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.bridge.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.replacements.*;
import com.oracle.graal.nodes.HeapAccess.BarrierType;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.debug.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.options.*;
import com.oracle.graal.replacements.nodes.*;

import edu.umd.cs.findbugs.annotations.*;

/**
 * This class contains infrastructure to maintain counters based on {@link DynamicCounterNode}s. The
 * infrastructure is enabled by specifying either the GenericDynamicCounters or
 * BenchmarkDynamicCounters option.
 * <p>
 *
 * The counters are kept in a special area allocated for each native JavaThread object, and the
 * number of counters is configured using {@code -XX:GraalCounterSize=value}.
 * {@code -XX:+/-GraalCountersExcludeCompiler} configures whether to exclude compiler threads
 * (defaults to true).
 *
 * The subsystems that use the logging need to have their own options to turn on the counters, and
 * insert DynamicCounterNodes when they're enabled.
 *
 * Counters will be displayed as a rate (per second) if their group name starts with "~", otherwise
 * they will be displayed as a total number.
 *
 * <h1>Example</h1> In order to create statistics about allocations within the DaCapo pmd benchmark
 * the following steps are necessary:
 * <ul>
 * <li>Set {@code -XX:GraalCounterSize=value}. The actual required value depends on the granularity
 * of the profiling, 10000 should be enough for most cases.</li>
 * <li>Also: {@code -XX:+/-GraalCountersExcludeCompiler} specifies whether the numbers generated by
 * compiler threads should be excluded (default: true).</li>
 * <li>Start the DaCapo pmd benchmark with
 * {@code "-G:BenchmarkDynamicCounters=err, starting ====, PASSED in "} and
 * {@code -G:+ProfileAllocations}.</li>
 * <li>The numbers will only include allocation from compiled code!</li>
 * <li>The counters can be further configured by modifying the
 * {@link NewObjectSnippets#PROFILE_MODE} field.</li>
 * </ul>
 */
public class BenchmarkCounters {

    static class Options {

        //@formatter:off
        @Option(help = "Turn on the benchmark counters, and displays the results on VM shutdown")
        private static final OptionValue<Boolean> GenericDynamicCounters = new OptionValue<>(false);
        @Option(help = "Turn on the benchmark counters, and displays the results every n milliseconds")
        private static final OptionValue<Integer> TimedDynamicCounters = new OptionValue<>(-1);

        @Option(help = "Turn on the benchmark counters, and listen for specific patterns on System.out/System.err:%n" +
                       "Format: (err|out),start pattern,end pattern (~ matches multiple digits)%n" +
                       "Examples:%n" +
                       "  dacapo = 'err, starting =====, PASSED in'%n" +
                       "  specjvm2008 = 'out,Iteration ~ (~s) begins:,Iteration ~ (~s) ends:'")
        private static final OptionValue<String> BenchmarkDynamicCounters = new OptionValue<>(null);
        //@formatter:on
    }

    private static final boolean DUMP_STATIC = false;

    public static boolean enabled = false;

    public static final ConcurrentHashMap<String, Integer> indexes = new ConcurrentHashMap<>();
    public static final ArrayList<String> groups = new ArrayList<>();
    public static long[] delta;
    public static final ArrayList<AtomicLong> staticCounters = new ArrayList<>();

    @SuppressFBWarnings(value = "AT_OPERATION_SEQUENCE_ON_CONCURRENT_ABSTRACTION", justification = "concurrent abstraction calls are in synchronized block")
    private static int getIndex(DynamicCounterNode counter) {
        if (!enabled) {
            throw new GraalInternalError("counter nodes shouldn't exist when counters are not enabled: " + counter.getGroup() + ", " + counter.getName());
        }
        String name;
        String group = counter.getGroup();
        if (counter.isWithContext()) {
            StructuredGraph graph = counter.graph();
            name = counter.getName() + " @ " + graph.graphId() + ":" + (graph.method() == null ? "" : graph.method().format("%h.%n"));
            if (graph.name != null) {
                name += " (" + graph.name + ")";
            }
            name += "#" + group;

        } else {
            name = counter.getName() + "#" + group;
        }
        Integer index = indexes.get(name);
        if (index == null) {
            synchronized (BenchmarkCounters.class) {
                index = indexes.get(name);
                if (index == null) {
                    index = indexes.size();
                    indexes.put(name, index);
                    groups.add(group);
                    staticCounters.add(new AtomicLong());
                }
            }
        }
        assert groups.get(index).equals(group) : "mismatching groups: " + groups.get(index) + " vs. " + group;
        if (counter.getIncrement().isConstant()) {
            staticCounters.get(index).addAndGet(counter.getIncrement().asConstant().asLong());
        }
        return index;
    }

    private static synchronized void dump(PrintStream out, double seconds, long[] counters, int maxRows) {
        if (!groups.isEmpty()) {
            out.println("====== dynamic counters (" + staticCounters.size() + " in total) ======");
            for (String group : new TreeSet<>(groups)) {
                if (group != null) {
                    if (DUMP_STATIC) {
                        dumpCounters(out, seconds, counters, true, group, maxRows);
                    }
                    dumpCounters(out, seconds, counters, false, group, maxRows);
                }
            }
            out.println("============================");

            clear(counters);
        }
    }

    private static synchronized void clear(long[] counters) {
        delta = counters;
    }

    private static synchronized void dumpCounters(PrintStream out, double seconds, long[] counters, boolean staticCounter, String group, int maxRows) {
        TreeMap<Long, String> sorted = new TreeMap<>();

        // collect the numbers
        long[] array;
        if (staticCounter) {
            array = new long[indexes.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = staticCounters.get(i).get();
            }
        } else {
            array = counters.clone();
            for (int i = 0; i < array.length; i++) {
                array[i] -= delta[i];
            }
        }
        // sort the counters by putting them into a sorted map
        long sum = 0;
        for (Map.Entry<String, Integer> entry : indexes.entrySet()) {
            int index = entry.getValue();
            if (groups.get(index).equals(group)) {
                sum += array[index];
                sorted.put(array[index] * array.length + index, entry.getKey().substring(0, entry.getKey().length() - group.length() - 1));
            }
        }

        if (sum > 0) {
            long cutoff = sorted.size() < 10 ? 1 : Math.max(1, sum / 100);
            int cnt = sorted.size();

            // remove everything below cutoff and keep at most maxRows
            Iterator<Map.Entry<Long, String>> iter = sorted.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<Long, String> entry = iter.next();
                long counter = entry.getKey() / array.length;
                if (counter < cutoff || cnt > maxRows) {
                    iter.remove();
                }
                cnt--;
            }

            if (staticCounter) {
                out.println("=========== " + group + " (static counters):");
                for (Map.Entry<Long, String> entry : sorted.entrySet()) {
                    long counter = entry.getKey() / array.length;
                    out.format(Locale.US, "%,19d %3d%%  %s\n", counter, percentage(counter, sum), entry.getValue());
                }
                out.format(Locale.US, "%,19d total\n", sum);
            } else {
                if (group.startsWith("~")) {
                    out.println("=========== " + group + " (dynamic counters), time = " + seconds + " s:");
                    for (Map.Entry<Long, String> entry : sorted.entrySet()) {
                        long counter = entry.getKey() / array.length;
                        out.format(Locale.US, "%,19d/s %3d%%  %s\n", (long) (counter / seconds), percentage(counter, sum), entry.getValue());
                    }
                    out.format(Locale.US, "%,19d/s total\n", (long) (sum / seconds));
                } else {
                    out.println("=========== " + group + " (dynamic counters):");
                    for (Map.Entry<Long, String> entry : sorted.entrySet()) {
                        long counter = entry.getKey() / array.length;
                        out.format(Locale.US, "%,19d %3d%%  %s\n", counter, percentage(counter, sum), entry.getValue());
                    }
                    out.format(Locale.US, "%,19d total\n", sum);
                }
            }
        }
    }

    private static long percentage(long counter, long sum) {
        return (counter * 200 + 1) / sum / 2;
    }

    private abstract static class CallbackOutputStream extends OutputStream {

        protected final PrintStream delegate;
        private final byte[][] patterns;
        private final int[] positions;

        public CallbackOutputStream(PrintStream delegate, String... patterns) {
            this.delegate = delegate;
            this.positions = new int[patterns.length];
            this.patterns = new byte[patterns.length][];
            for (int i = 0; i < patterns.length; i++) {
                this.patterns[i] = patterns[i].getBytes();
            }
        }

        protected abstract void patternFound(int index);

        @Override
        public void write(int b) throws IOException {
            try {
                delegate.write(b);
                for (int i = 0; i < patterns.length; i++) {
                    int j = positions[i];
                    byte[] cs = patterns[i];
                    byte patternChar = cs[j];
                    if (patternChar == '~' && Character.isDigit(b)) {
                        // nothing to do...
                    } else {
                        if (patternChar == '~') {
                            patternChar = cs[++positions[i]];
                        }
                        if (b == patternChar) {
                            positions[i]++;
                        } else {
                            positions[i] = 0;
                        }
                    }
                    if (positions[i] == patterns[i].length) {
                        positions[i] = 0;
                        patternFound(i);
                    }
                }
            } catch (RuntimeException e) {
                e.printStackTrace(delegate);
                throw e;
            }
        }
    }

    public static void initialize(final CompilerToVM compilerToVM) {
        final class BenchmarkCountersOutputStream extends CallbackOutputStream {

            private long startTime;
            private boolean running;
            private boolean waitingForEnd;

            private BenchmarkCountersOutputStream(PrintStream delegate, String start, String end) {
                super(delegate, new String[]{"\n", end, start});
            }

            @Override
            protected void patternFound(int index) {
                switch (index) {
                    case 2:
                        startTime = System.nanoTime();
                        BenchmarkCounters.clear(compilerToVM.collectCounters());
                        running = true;
                        break;
                    case 1:
                        if (running) {
                            waitingForEnd = true;
                        }
                        break;
                    case 0:
                        if (waitingForEnd) {
                            waitingForEnd = false;
                            running = false;
                            BenchmarkCounters.dump(delegate, (System.nanoTime() - startTime) / 1000000000d, compilerToVM.collectCounters(), 100);
                        }
                        break;
                }
            }
        }

        if (Options.BenchmarkDynamicCounters.getValue() != null) {
            String[] arguments = Options.BenchmarkDynamicCounters.getValue().split(",");
            if (arguments.length == 0 || (arguments.length % 3) != 0) {
                throw new GraalInternalError("invalid arguments to BenchmarkDynamicCounters: (err|out),start,end,(err|out),start,end,... (~ matches multiple digits)");
            }
            for (int i = 0; i < arguments.length; i += 3) {
                if (arguments[i].equals("err")) {
                    System.setErr(new PrintStream(new BenchmarkCountersOutputStream(System.err, arguments[i + 1], arguments[i + 2])));
                } else if (arguments[i].equals("out")) {
                    System.setOut(new PrintStream(new BenchmarkCountersOutputStream(System.out, arguments[i + 1], arguments[i + 2])));
                } else {
                    throw new GraalInternalError("invalid arguments to BenchmarkDynamicCounters: err|out");
                }
            }
            enabled = true;
        }
        if (Options.GenericDynamicCounters.getValue()) {
            enabled = true;
        }
        if (Options.TimedDynamicCounters.getValue() > 0) {
            Thread thread = new Thread() {
                long lastTime = System.nanoTime();
                PrintStream out = TTY.cachedOut;

                @Override
                public void run() {
                    while (true) {
                        try {
                            Thread.sleep(Options.TimedDynamicCounters.getValue());
                        } catch (InterruptedException e) {
                        }
                        long time = System.nanoTime();
                        dump(out, (time - lastTime) / 1000000000d, compilerToVM.collectCounters(), 10);
                        lastTime = time;
                    }
                }
            };
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
            enabled = true;
        }
        if (enabled) {
            clear(compilerToVM.collectCounters());
        }
    }

    public static void shutdown(CompilerToVM compilerToVM, long compilerStartTime) {
        if (Options.GenericDynamicCounters.getValue()) {
            dump(TTY.cachedOut, (System.nanoTime() - compilerStartTime) / 1000000000d, compilerToVM.collectCounters(), 100);
        }
    }

    public static void lower(DynamicCounterNode counter, HotSpotRegistersProvider registers, HotSpotVMConfig config, Kind wordKind) {
        StructuredGraph graph = counter.graph();

        ReadRegisterNode thread = graph.add(new ReadRegisterNode(registers.getThreadRegister(), wordKind, true, false));

        int index = BenchmarkCounters.getIndex(counter);
        if (index >= config.graalCountersSize) {
            throw new GraalInternalError("too many counters, reduce number of counters or increase -XX:GraalCounterSize=... (current value: " + config.graalCountersSize + ")");
        }
        ConstantLocationNode arrayLocation = ConstantLocationNode.create(LocationIdentity.ANY_LOCATION, wordKind, config.graalCountersThreadOffset, graph);
        ReadNode readArray = graph.add(new ReadNode(thread, arrayLocation, StampFactory.forKind(wordKind), BarrierType.NONE));
        ConstantLocationNode location = ConstantLocationNode.create(LocationIdentity.ANY_LOCATION, Kind.Long, Unsafe.ARRAY_LONG_INDEX_SCALE * index, graph);
        ReadNode read = graph.add(new ReadNode(readArray, location, StampFactory.forKind(Kind.Long), BarrierType.NONE));
        IntegerAddNode add = graph.unique(new IntegerAddNode(read, counter.getIncrement()));
        WriteNode write = graph.add(new WriteNode(readArray, add, location, BarrierType.NONE));

        graph.addBeforeFixed(counter, thread);
        graph.addBeforeFixed(counter, readArray);
        graph.addBeforeFixed(counter, read);
        graph.addBeforeFixed(counter, write);
        graph.removeFixed(counter);
    }
}
