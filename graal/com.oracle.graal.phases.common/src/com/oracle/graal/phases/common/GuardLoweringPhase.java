/*
 * Copyright (c) 2013, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.phases.common;

import java.util.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.schedule.*;

public class GuardLoweringPhase extends Phase {

    @Override
    protected void run(StructuredGraph graph) {
        SchedulePhase schedule = new SchedulePhase();
        schedule.apply(graph);

        for (Block block : schedule.getCFG().getBlocks()) {
            processBlock(block, schedule, graph);
        }
    }

    private static void processBlock(Block block, SchedulePhase schedule, StructuredGraph graph) {
        List<ScheduledNode> nodes = schedule.nodesFor(block);
        FixedWithNextNode lastFixed = block.getBeginNode();
        BeginNode lastFastPath = null;
        for (Node node : nodes) {
            if (lastFastPath != null && node instanceof FixedNode) {
                lastFastPath.setNext((FixedNode) node);
                lastFastPath = null;
            }
            if (node instanceof FixedWithNextNode) {
                lastFixed = (FixedWithNextNode) node;
            } else if (node instanceof GuardNode) {
                GuardNode guard = (GuardNode) node;
                BeginNode fastPath = graph.add(new BeginNode());
                BeginNode trueSuccessor;
                BeginNode falseSuccessor;
                DeoptimizeNode deopt = graph.add(new DeoptimizeNode(guard.action(), guard.reason()));
                if (guard.negated()) {
                    trueSuccessor = BeginNode.begin(deopt);
                    falseSuccessor = fastPath;
                } else {
                    trueSuccessor = fastPath;
                    falseSuccessor = BeginNode.begin(deopt);
                }
                IfNode ifNode = graph.add(new IfNode(guard.condition(), trueSuccessor, falseSuccessor, trueSuccessor == fastPath ? 1 : 0));
                guard.replaceAndDelete(fastPath);
                lastFixed.setNext(ifNode);
                lastFixed = fastPath;
                lastFastPath = fastPath;
            }
        }
    }
}
