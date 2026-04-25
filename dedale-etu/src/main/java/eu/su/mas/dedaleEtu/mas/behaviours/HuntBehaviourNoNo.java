package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.ExploreCoopAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.*;

import dataStructures.serializableGraph.SerializableNode;

/**
 * Full hunt behaviour with 5 phases:
 *   SEARCH    → explore until stench detected, relay location to all
 *   CONVERGE  → move toward stench area, broadcast READY when arrived
 *   AGREE     → all compute same trap node independently (lowest degree reachable from Golem, lowest degree then closest)
 *   SURROUND  → each agent claims the Golem-neighbor closest to itself, pushes Golem toward trap
 *   ESCAPE    → if stench lost for 3 ticks, broadcast ESCAPED and reset to SEARCH
 */
public class HuntBehaviourNoNo extends TickerBehaviour {

    // ── Message ontology tags ─────────────────────────────────────────────────
    private static final String ONTOLOGY   = "HUNT";
    private static final String MSG_GOLEM   = "GOLEM:";
    private static final String MSG_READY   = "READY";
    private static final String MSG_ESCAPED = "ESCAPED";

    // ── Phase enum ────────────────────────────────────────────────────────────
    public enum Phase { SEARCH, CONVERGE, AGREE, SURROUND, ESCAPE }

    // ── Agent identity ────────────────────────────────────────────────────────
    private final List<String> allAgentNames; // all OTHER agents
    private final String myName;
    private final int totalAgents;            // including self

    // ── State ─────────────────────────────────────────────────────────────────
    private Phase phase = Phase.SEARCH;
    private String golemNode    = null;  // last known stench node near Golem
    private String trapNode     = null;  // agreed trap destination
    private String myTargetNode = null;  // node I'm heading to
    private boolean relayed     = false; // have I already relayed the GOLEM message?
    private final Set<String> readyAgents = new HashSet<>(); // who sent READY
    private int noStenchTicks   = 0;     // consecutive ticks with no stench

    // ── Constructor ───────────────────────────────────────────────────────────
    public HuntBehaviourNoNo(AbstractDedaleAgent agent, List<String> allAgentNames) {
        super(agent, 600);
        this.allAgentNames = allAgentNames;
        this.myName        = agent.getLocalName();
        this.totalAgents   = allAgentNames.size() + 1; // others + self
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MAIN TICK
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void onTick() {
        AbstractDedaleAgent me = (AbstractDedaleAgent) this.myAgent;
        processMessages(me);

        switch (phase) {
            case SEARCH:   doSearch(me);   break;
            case CONVERGE: doConverge(me); break;
            case AGREE:    doAgree(me);    break;
            case SURROUND: doSurround(me); break;
            case ESCAPE:   doEscape(me);   break;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PHASE 1 — SEARCH
    // ═════════════════════════════════════════════════════════════════════════

    private void doSearch(AbstractDedaleAgent me) {
        String stench = findStenchNode(me);
        if (stench != null) {
            System.out.println("[" + myName + "] SEARCH: stench detected at " + stench + " → broadcasting");
            golemNode = stench;
            broadcast(me, MSG_GOLEM + stench);
            relayed = true;
            transitionTo(Phase.CONVERGE);
        }
        // If no stench, ExploCoopBehaviour handles movement
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PHASE 2 — CONVERGE
    // ═════════════════════════════════════════════════════════════════════════

    private void doConverge(AbstractDedaleAgent me) {
        String stench = findStenchNode(me);

        if (stench != null) {
            // I can see stench — I'm close enough. Update golem position and signal ready.
            if (!stench.equals(golemNode)) {
                golemNode = stench;
                broadcast(me, MSG_GOLEM + stench);
            }
            System.out.println("[" + myName + "] CONVERGE: in range, sending READY");
            readyAgents.add(myName);
            broadcast(me, MSG_READY);
            transitionTo(Phase.AGREE);
        } else {
            // Move toward last known golem area
            if (golemNode != null) moveToward(me, golemNode);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PHASE 3 — AGREE
    // ═════════════════════════════════════════════════════════════════════════

    private void doAgree(AbstractDedaleAgent me) {
        // Keep moving toward golem area while waiting
        if (golemNode != null) moveToward(me, golemNode);

        if (readyAgents.size() >= totalAgents) {
            // All agents are in range — compute trap node independently
            trapNode = computeTrapNode(golemNode, getGraph());
            if (trapNode == null) {
                System.out.println("[" + myName + "] AGREE: no suitable trap node found, staying in AGREE");
                return;
            }
            System.out.println("[" + myName + "] AGREE: all ready (" + readyAgents.size() + "/" + totalAgents + ") → trap=" + trapNode);
            myTargetNode = computeMyPosition(me, golemNode);
            transitionTo(Phase.SURROUND);
        } else {
            System.out.println("[" + myName + "] AGREE: waiting for agents (" + readyAgents.size() + "/" + totalAgents + ")");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PHASE 4 — SURROUND
    // ═════════════════════════════════════════════════════════════════════════

    private void doSurround(AbstractDedaleAgent me) {
        String stench = findStenchNode(me);

        if (stench == null) {
            noStenchTicks++;
            System.out.println("[" + myName + "] SURROUND: no stench (" + noStenchTicks + "/3) — holding at " + myTargetNode);
            if (noStenchTicks >= 3) {
                System.out.println("[" + myName + "] SURROUND: Golem escaped! Broadcasting ESCAPED");
                broadcast(me, MSG_ESCAPED);
                reset();
                return;
            }
            // Hold position while waiting
            return;
        }

        noStenchTicks = 0;

        // Update golem position if it moved
        if (!stench.equals(golemNode)) {
            golemNode = stench;
            myTargetNode = computeMyPosition(me, golemNode);
            System.out.println("[" + myName + "] SURROUND: Golem moved → new target: " + myTargetNode);
        }

        Location cur = me.getCurrentPosition();
        if (cur != null && cur.getLocationId().equals(myTargetNode)) {
            System.out.println("[" + myName + "] SURROUND: in position at " + myTargetNode + " ✓");
            return;
        }

        moveToward(me, myTargetNode);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PHASE 5 — ESCAPE (transient — just reset)
    // ═════════════════════════════════════════════════════════════════════════

    private void doEscape(AbstractDedaleAgent me) {
        // Already reset in the SURROUND escape detection or on message receipt
        transitionTo(Phase.SEARCH);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MESSAGE HANDLING
    // ═════════════════════════════════════════════════════════════════════════

    private void processMessages(AbstractDedaleAgent me) {
        MessageTemplate mt = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology(ONTOLOGY));

        ACLMessage msg;
        while ((msg = me.receive(mt)) != null) {
            String content = msg.getContent();
            String sender  = msg.getSender().getLocalName();
            if (content == null) continue;

            if (content.startsWith(MSG_GOLEM)) {
                String node = content.substring(MSG_GOLEM.length()).trim();
                onGolemMessage(me, node, sender);

            } else if (content.equals(MSG_READY)) {
                readyAgents.add(sender);
                System.out.println("[" + myName + "] Received READY from " + sender
                    + " (" + readyAgents.size() + "/" + totalAgents + ")");

            } else if (content.equals(MSG_ESCAPED)) {
                System.out.println("[" + myName + "] Received ESCAPED from " + sender + " → resetting");
                reset();
            }
        }
    }

    private void onGolemMessage(AbstractDedaleAgent me, String node, String sender) {
        System.out.println("[" + myName + "] Received GOLEM:" + node + " from " + sender);

        // Relay once if not yet relayed (for out-of-range agents)
        if (!relayed) {
            relayed = true;
            broadcast(me, MSG_GOLEM + node);
            System.out.println("[" + myName + "] Relayed GOLEM:" + node);
        }

        golemNode = node;

        // If we were searching, start converging
        if (phase == Phase.SEARCH) {
            transitionTo(Phase.CONVERGE);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TRAP NODE COMPUTATION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Find the best trap node: reachable from golemNode, degree <= totalAgents,
     * sorted by lowest degree first, then closest to golemNode (BFS distance).
     */
    private String computeTrapNode(String fromNode, Map<String, List<String>> graph) {
        if (fromNode == null || graph.isEmpty()) return null;

        // BFS from golemNode to find all reachable nodes with their distances
        Map<String, Integer> dist = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(fromNode);
        dist.put(fromNode, 0);

        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String nb : graph.getOrDefault(cur, Collections.emptyList())) {
                if (!dist.containsKey(nb)) {
                    dist.put(nb, dist.get(cur) + 1);
                    queue.add(nb);
                }
            }
        }

        // Among reachable nodes with degree <= totalAgents, pick lowest degree then closest
        return dist.keySet().stream()
            .filter(n -> graph.getOrDefault(n, Collections.emptyList()).size() <= totalAgents)
            .min(Comparator
                .comparingInt((String n) -> graph.getOrDefault(n, Collections.emptyList()).size())
                .thenComparingInt(n -> dist.getOrDefault(n, Integer.MAX_VALUE))
                .thenComparing(n -> n)) // tie-break by name
            .orElse(null);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // POSITION ASSIGNMENT — closest free Golem-neighbor to self
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Each agent picks the Golem neighbor closest to itself.
     * Ties broken by agent name to avoid collisions.
     */
    private String computeMyPosition(AbstractDedaleAgent me, String golemArea) {
        Map<String, List<String>> graph = getGraph();
        List<String> neighbors = new ArrayList<>(graph.getOrDefault(golemArea, Collections.emptyList()));
        if (neighbors.isEmpty()) return golemArea;

        Location cur = me.getCurrentPosition();
        if (cur == null) return neighbors.get(0);

        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        if (map == null) return neighbors.get(0);

        // Sort neighbors by my BFS distance to them, then by name for tie-break
        neighbors.sort(Comparator
            .comparingInt((String n) -> {
                List<String> path = map.getShortestPath(cur.getLocationId(), n);
                return path == null ? Integer.MAX_VALUE : path.size();
            })
            .thenComparing(n -> n));

        // Build sorted agent list for collision avoidance
        List<String> allSorted = new ArrayList<>(allAgentNames);
        if (!allSorted.contains(myName)) allSorted.add(myName);
        Collections.sort(allSorted);
        int myRank = allSorted.indexOf(myName);

        // Each agent picks neighbor at offset myRank (mod size) from the sorted neighbor list
        // This ensures no two agents pick the same node deterministically
        return neighbors.get(myRank % neighbors.size());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private String findStenchNode(AbstractDedaleAgent me) {
        var observations = me.observe();
        if (observations == null) return null;
        for (var nodeObs : observations) {
            for (var obs : nodeObs.getRight()) {
                if (obs.getLeft() == Observation.STENCH) {
                    return nodeObs.getLeft().getLocationId();
                }
            }
        }
        return null;
    }

    private void moveToward(AbstractDedaleAgent me, String targetId) {
        if (targetId == null) return;
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        if (cur.getLocationId().equals(targetId)) return;

        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        if (map == null) return;

        List<String> path = map.getShortestPath(cur.getLocationId(), targetId);
        if (path == null || path.isEmpty()) return;
        String nextNode = path.get(0);

        var obs = me.observe();
        if (obs == null) return;
        for (var nodeObs : obs) {
            if (nodeObs.getLeft().getLocationId().equals(nextNode)) {
                me.moveTo(nodeObs.getLeft());
                return;
            }
        }
    }

    private void broadcast(AbstractDedaleAgent me, String content) {
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology(ONTOLOGY);
        acl.setContent(content);
        for (String name : allAgentNames) {
            acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        me.send(acl);
    }

    private Map<String, List<String>> getGraph() {
        MapRepresentation map = ((ExploreCoopAgent) this.myAgent).myMap;
        if (map == null) return new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (SerializableNode<String, MapAttribute> n : map.getSerializableGraph().getAllNodes()) {
            adj.put(n.getNodeId(), new ArrayList<>(map.getSerializableGraph().getEdges(n.getNodeId())));
        }
        return adj;
    }

    private void transitionTo(Phase next) {
        System.out.println("[" + myName + "] " + phase + " → " + next);
        phase = next;
    }

    public void reset() {
        phase        = Phase.SEARCH;
        golemNode    = null;
        trapNode     = null;
        myTargetNode = null;
        relayed      = false;
        noStenchTicks = 0;
        readyAgents.clear();
        System.out.println("[" + myName + "] RESET → SEARCH");
    }
}