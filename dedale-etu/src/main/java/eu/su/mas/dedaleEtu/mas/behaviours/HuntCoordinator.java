package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.*;
import dataStructures.tuple.Couple;

public class HuntCoordinator {

    /**
     * trouve le noeud dans lequel on va pieger le golem
     */
    public static String computeTrapNode(Map<String, List<String>> graph) {
        return graph.entrySet().stream()
            .min(Comparator
                .comparingInt((Map.Entry<String, List<String>> e) -> e.getValue().size())
                .thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /**
     * Separe les agents en pushers et trappers 
     */
    public static List<String> getTrappers(List<String> agentNames) {
        List<String> sorted = new ArrayList<>(agentNames);
        Collections.sort(sorted);
        return sorted.subList(0, sorted.size() / 2);
    }

    public static List<String> getPushers(List<String> agentNames) {
        List<String> sorted = new ArrayList<>(agentNames);
        Collections.sort(sorted);
        return sorted.subList(sorted.size() / 2, sorted.size());
    }

    /**
     * Ls pushers empechent le golm de s'echapper
     */
    public static List<String> getBlockingPositions(
            String golemNode,
            String trapNode,
            Map<String, List<String>> graph) {

        List<String> golemNeighbors = graph.getOrDefault(golemNode, new ArrayList<>());
        List<String> pathToTrap = bfs(golemNode, trapNode, graph);
        String nextTowardTrap = pathToTrap.size() > 1 ? pathToTrap.get(1) : null;

        // Block all neighbors except the one toward the trap
        List<String> blocking = new ArrayList<>(golemNeighbors);
        if (nextTowardTrap != null) blocking.remove(nextTowardTrap);
        return blocking;
    }

    /**
     * BFS
     */
    public static List<String> bfs(String from, String to, Map<String, List<String>> graph) {
        if (from.equals(to)) return List.of(from);
        Map<String, String> parent = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(from);
        parent.put(from, null);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String nb : graph.getOrDefault(cur, List.of())) {
                if (!parent.containsKey(nb)) {
                    parent.put(nb, cur);
                    if (nb.equals(to)) return reconstructPath(parent, from, to);
                    queue.add(nb);
                }
            }
        }
        return List.of();
    }

    private static List<String> reconstructPath(Map<String, String> parent, String from, String to) {
        LinkedList<String> path = new LinkedList<>();
        for (String cur = to; cur != null; cur = parent.get(cur)) path.addFirst(cur);
        return path;
    }
}