package io.doindev.codegraph.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Iterative (explicit-stack) Tarjan strongly-connected-components over a string adjacency map.
 * Returns only components of size &gt; 1 — the cycles. Iterative because module/file graphs of
 * large monorepos would blow the call stack recursively.
 */
public final class Tarjan {

    private Tarjan() {
    }

    public static List<List<String>> cycles(Map<String, Set<String>> adjacency) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowLink = new HashMap<>();
        Set<String> onStack = new java.util.HashSet<>();
        ArrayDeque<String> stack = new ArrayDeque<>();
        List<List<String>> components = new ArrayList<>();
        int[] counter = {0};

        record Frame(String node, java.util.Iterator<String> neighbors) {}

        for (String start : adjacency.keySet()) {
            if (index.containsKey(start)) {
                continue;
            }
            ArrayDeque<Frame> work = new ArrayDeque<>();
            index.put(start, counter[0]);
            lowLink.put(start, counter[0]);
            counter[0]++;
            stack.push(start);
            onStack.add(start);
            work.push(new Frame(start, neighbors(adjacency, start)));

            while (!work.isEmpty()) {
                Frame frame = work.peek();
                if (frame.neighbors().hasNext()) {
                    String next = frame.neighbors().next();
                    if (!index.containsKey(next)) {
                        index.put(next, counter[0]);
                        lowLink.put(next, counter[0]);
                        counter[0]++;
                        stack.push(next);
                        onStack.add(next);
                        work.push(new Frame(next, neighbors(adjacency, next)));
                    } else if (onStack.contains(next)) {
                        lowLink.merge(frame.node(), index.get(next), Math::min);
                    }
                } else {
                    work.pop();
                    if (!work.isEmpty()) {
                        lowLink.merge(work.peek().node(), lowLink.get(frame.node()), Math::min);
                    }
                    if (lowLink.get(frame.node()).equals(index.get(frame.node()))) {
                        List<String> component = new ArrayList<>();
                        String member;
                        do {
                            member = stack.pop();
                            onStack.remove(member);
                            component.add(member);
                        } while (!member.equals(frame.node()));
                        if (component.size() > 1) {
                            component.sort(String::compareTo);
                            components.add(component);
                        }
                    }
                }
            }
        }
        return components;
    }

    private static java.util.Iterator<String> neighbors(Map<String, Set<String>> adjacency, String node) {
        return adjacency.getOrDefault(node, Set.of()).iterator();
    }
}
