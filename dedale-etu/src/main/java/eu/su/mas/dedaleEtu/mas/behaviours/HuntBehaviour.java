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
import dataStructures.serializableGraph.SerializableNode;

import java.util.*;

public class HuntBehaviour extends TickerBehaviour {

    public enum HuntState { MEETING, PATROL, HUNTING }

    private final List<String> allAgentNames;
    private final String myName;
    private final boolean isLeader;

    private HuntState state = HuntState.MEETING;

    private String golemNode = null;
    private String myTargetNode = null;
    private String patrolTarget = null;

    private Map<String, String> agentPositions = new HashMap<>();
    private Map<String, String> agentTargets = new HashMap<>();
    private Map<String, String> stenchPositions = new HashMap<>();

    private static final int FORMATION_RADIUS = 2;

    public HuntBehaviour(AbstractDedaleAgent agent, List<String> allAgentNames) {
        super(agent, 600);
        this.allAgentNames = allAgentNames;
        this.myName = agent.getLocalName();

        List<String> sorted = new ArrayList<>(allAgentNames);
        sorted.add(agent.getLocalName());
        Collections.sort(sorted);
        this.isLeader = sorted.get(0).equals(myName);

        System.out.println("[" + myName + "] isLeader=" + isLeader);
    }

    @Override
    public void onTick() {
        AbstractDedaleAgent me = (AbstractDedaleAgent) this.myAgent;

        processMessages(me);
        updateGolemPosition(me);

        if (state == HuntState.MEETING) {
            goToMeetingPoint(me);
            return;
        }

        if (state == HuntState.PATROL) {
            if (isLeader) leaderPatrol(me);
            else followerPatrol(me);
            return;
        }

        // HUNTING
        chooseTarget(me);
        broadcastStatus(me);
        if (myTargetNode != null) moveToward(me, myTargetNode);
    }

    // ==================== MEETING ====================

    private void goToMeetingPoint(AbstractDedaleAgent me) {
        String meeting = ((ExploreCoopAgent) me).meetingPoint;
        if (meeting == null) return;

        Location cur = me.getCurrentPosition();
        if (cur == null) return;

        broadcastStatus(me);

        // Chercher un nœud libre dans la zone du meeting
        String mySpot = findSpotNear(meeting, cur.getLocationId());

        if (cur.getLocationId().equals(mySpot)) {
            System.out.println("[" + myName + "] Au RDV (" + mySpot + ") | agents : "
                + agentPositions.keySet());

            // Vérifier si tous sont arrivés dans la zone du meeting
            Set<String> meetingZone = new HashSet<>(getNeighbors(meeting));
            meetingZone.add(meeting);

            boolean allArrived = allAgentNames.stream()
                .filter(a -> !a.equals(myName))
                .allMatch(a -> {
                    String pos = agentPositions.get(a);
                    return pos != null && meetingZone.contains(pos);
                });

            if (allArrived) {
                System.out.println("[" + myName + "] Tout le monde au RDV → PATROL");
                state = HuntState.PATROL;
            }
            return;
        }

        moveToward(me, mySpot);
    }

    private String findSpotNear(String meeting, String myPos) {
        // Si le meeting est libre ou que je suis déjà dessus
        Set<String> occupied = new HashSet<>(agentPositions.values());
        if (!occupied.contains(meeting)) return meeting;

        // Sinon prendre un voisin libre
        MapRepresentation map = ((ExploreCoopAgent) myAgent).myMap;
        if (map == null) return meeting;

        List<String> neighbors = getNeighbors(meeting);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String n : neighbors) {
            if (!occupied.contains(n)) {
                List<String> path = map.getShortestPath(myPos, n);
                int dist = (path == null) ? Integer.MAX_VALUE : path.size();
                if (dist < bestDist) {
                    bestDist = dist;
                    best = n;
                }
            }
        }
        return best != null ? best : meeting;
    }

    // ==================== PATROL ====================

    private void leaderPatrol(AbstractDedaleAgent me) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        if (map == null) return;

        if (patrolTarget == null || cur.getLocationId().equals(patrolTarget)) {
            patrolTarget = pickFarNode(map, cur.getLocationId());
            System.out.println("[" + myName + "] Leader patrouille → " + patrolTarget);
        }

        broadcastLeaderStatus(me, cur.getLocationId(), patrolTarget);
        moveToward(me, patrolTarget);
    }

    private void followerPatrol(AbstractDedaleAgent me) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        if (map == null) return;

        String leaderPos = agentPositions.get(getLeaderName());
        if (leaderPos == null) return;

        String target = pickFormationNode(map, cur.getLocationId(), leaderPos);
        if (target != null && !target.equals(myTargetNode)) {
            myTargetNode = target;
            System.out.println("[" + myName + "] Follower formation → " + myTargetNode);
        }

        broadcastStatus(me);
        if (myTargetNode != null) moveToward(me, myTargetNode);
    }

    private String getLeaderName() {
        List<String> sorted = new ArrayList<>(allAgentNames);
        sorted.add(myName);
        Collections.sort(sorted);
        return sorted.get(0);
    }

    private String pickFormationNode(MapRepresentation map, String myPos, String leaderPos) {
        List<String> candidates = getNodesWithinRadius(map, leaderPos, FORMATION_RADIUS);

        Set<String> taken = new HashSet<>(agentTargets.values());
        taken.addAll(agentPositions.values());
        taken.remove(myTargetNode);
        taken.remove(leaderPos);
        candidates.removeAll(taken);
        candidates.remove(leaderPos);

        if (candidates.isEmpty()) return myTargetNode;

        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : candidates) {
            List<String> path = map.getShortestPath(myPos, c);
            int dist = (path == null) ? Integer.MAX_VALUE : path.size();
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    private List<String> getNodesWithinRadius(MapRepresentation map, String center, int radius) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        Map<String, Integer> dist = new HashMap<>();

        queue.add(center);
        visited.add(center);
        dist.put(center, 0);

        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int d = dist.get(cur);
            if (d > 0) result.add(cur);
            if (d >= radius) continue;
            for (String neighbor : getNeighbors(cur)) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    dist.put(neighbor, d + 1);
                    queue.add(neighbor);
                }
            }
        }
        return result;
    }

    // ==================== HUNTING ====================

    private void chooseTarget(AbstractDedaleAgent me) {
        if (golemNode == null) return;
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        if (map == null) return;

        if (cur.getLocationId().equals(golemNode)) {
            myTargetNode = golemNode;
            return;
        }

        List<String> golemNeighbors = getNeighbors(golemNode);
        golemNeighbors.add(golemNode);

        Set<String> taken = new HashSet<>(agentTargets.values());
        taken.addAll(agentPositions.values());

        for (String agent : agentTargets.keySet()) {
            if (agent.compareTo(myName) > 0) {
                taken.remove(agentTargets.get(agent));
            }
        }
        taken.remove(myTargetNode);

        List<String> available = new ArrayList<>();
        for (String n : golemNeighbors) {
            if (!taken.contains(n)) available.add(n);
        }

        if (available.isEmpty()) {
            if (myTargetNode == null || !golemNeighbors.contains(myTargetNode))
                myTargetNode = golemNode;
            return;
        }

        String bestTarget = null;
        int bestDist = Integer.MAX_VALUE;
        for (String candidate : available) {
            List<String> path = map.getShortestPath(cur.getLocationId(), candidate);
            int dist = (path == null) ? Integer.MAX_VALUE : path.size();
            if (dist < bestDist) {
                bestDist = dist;
                bestTarget = candidate;
            }
        }
        if (bestTarget != null) {
            myTargetNode = bestTarget;
            System.out.println("[" + myName + "] Hunt cible : " + myTargetNode);
        }
    }

    // ==================== DETECTION ====================

    private void updateGolemPosition(AbstractDedaleAgent me) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;

        var observations = me.observe();
        String stenchNode = null;

        for (var nodeObs : observations) {
            for (var obs : nodeObs.getRight()) {
                if (obs.getLeft() == Observation.AGENTNAME
                        && obs.getRight() != null
                        && obs.getRight().toLowerCase().contains("wumpus")) {
                    String detected = nodeObs.getLeft().getLocationId();
                    if (!detected.equals(golemNode)) {
                        golemNode = detected;
                        myTargetNode = null;
                        broadcastGolem(me, golemNode);
                        System.out.println("[" + myName + "] Golem VU en " + golemNode);
                    }
                    state = HuntState.HUNTING;
                    ((ExploreCoopAgent) me).huntStarted = true;
                    return;
                }
                if (obs.getLeft() == Observation.STENCH) {
                    stenchNode = nodeObs.getLeft().getLocationId();
                }
            }
        }

        if (stenchNode != null) {
            broadcastStench(me, stenchNode);
            if (state != HuntState.HUNTING) {
                System.out.println("[" + myName + "] Stench → HUNTING");
                state = HuntState.HUNTING;
                golemNode = stenchNode;
                myTargetNode = null;
                ((ExploreCoopAgent) me).huntStarted = true;
            }
        }
    }

    // ==================== MESSAGES ====================

    private void processMessages(AbstractDedaleAgent me) {
        // Golem vu directement
        MessageTemplate mtGolem = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("GOLEM-POS"));
        ACLMessage msg;
        while ((msg = me.receive(mtGolem)) != null) {
            String newGolem = msg.getContent();
            if (newGolem != null && !newGolem.equals(golemNode)) {
                golemNode = newGolem;
                myTargetNode = null;
                state = HuntState.HUNTING;
                ((ExploreCoopAgent) me).huntStarted = true;
                System.out.println("[" + myName + "] Golem signalé en " + golemNode
                    + " par " + msg.getSender().getLocalName());
            }
        }

        // Stench
        MessageTemplate mtStench = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("STENCH-POS"));
        while ((msg = me.receive(mtStench)) != null) {
            String[] parts = msg.getContent().split(";");
            if (parts.length < 2) continue;
            stenchPositions.put(parts[0], parts[1]);
            if (state != HuntState.HUNTING) {
                state = HuntState.HUNTING;
                ((ExploreCoopAgent) me).huntStarted = true;
                golemNode = parts[1];
                myTargetNode = null;
                System.out.println("[" + myName + "] Chasse via stench de "
                    + parts[0] + " en " + parts[1]);
            }
        }

        // Status agents
        MessageTemplate mtStatus = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("AGENT-STATUS"));
        while ((msg = me.receive(mtStatus)) != null) {
            String content = msg.getContent();
            if (content == null) continue;
            String[] parts = content.split(";");
            if (parts.length < 2) continue;
            agentPositions.put(msg.getSender().getLocalName(), parts[0]);
            agentTargets.put(msg.getSender().getLocalName(), parts[1]);
        }

        // Status leader
        MessageTemplate mtLeader = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("LEADER-STATUS"));
        while ((msg = me.receive(mtLeader)) != null) {
            String content = msg.getContent();
            if (content == null) continue;
            String[] parts = content.split(";");
            if (parts.length < 2) continue;
            agentPositions.put(msg.getSender().getLocalName(), parts[0]);
        }
    }

    private void broadcastLeaderStatus(AbstractDedaleAgent me, String pos, String target) {
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("LEADER-STATUS");
        acl.setContent(pos + ";" + target);
        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        me.send(acl);
    }

    private void broadcastStatus(AbstractDedaleAgent me) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        String target = myTargetNode != null ? myTargetNode : cur.getLocationId();
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("AGENT-STATUS");
        acl.setContent(cur.getLocationId() + ";" + target);
        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        me.send(acl);
    }

    private void broadcastGolem(AbstractDedaleAgent me, String golemPos) {
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("GOLEM-POS");
        acl.setContent(golemPos);
        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        me.send(acl);
    }

    private void broadcastStench(AbstractDedaleAgent me, String stenchNode) {
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("STENCH-POS");
        acl.setContent(myName + ";" + stenchNode);
        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        me.send(acl);
    }

    // ==================== MOUVEMENT ====================

    private void moveToward(AbstractDedaleAgent me, String targetId) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        if (cur.getLocationId().equals(targetId)) return;

        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        if (map == null) return;

        List<String> path = map.getShortestPath(cur.getLocationId(), targetId);
        if (path == null || path.isEmpty()) return;

        String nextNode = path.get(0);
        var obs = me.observe();
        for (var nodeObs : obs) {
            if (nodeObs.getLeft().getLocationId().equals(nextNode)) {
                boolean success = me.moveTo(nodeObs.getLeft());
                if (!success) myTargetNode = null;
                return;
            }
        }
    }

    // ==================== UTILITAIRES ====================

    private String pickFarNode(MapRepresentation map, String currentId) {
        var allNodes = map.getSerializableGraph().getAllNodes();
        if (allNodes.isEmpty()) return null;

        List<String> nodeIds = new ArrayList<>();
        for (var n : allNodes) {
            if (!n.getNodeId().equals(currentId)) nodeIds.add(n.getNodeId());
        }
        if (nodeIds.isEmpty()) return null;

        String bestNode = null;
        int bestDist = 0;
        Random rand = new Random();
        int samples = Math.min(10, nodeIds.size());
        for (int i = 0; i < samples; i++) {
            String candidate = nodeIds.get(rand.nextInt(nodeIds.size()));
            List<String> path = map.getShortestPath(currentId, candidate);
            int dist = (path == null) ? 0 : path.size();
            if (dist > bestDist) {
                bestDist = dist;
                bestNode = candidate;
            }
        }
        return bestNode;
    }

    private List<String> getNeighbors(String nodeId) {
        MapRepresentation map = ((ExploreCoopAgent) this.myAgent).myMap;
        if (map == null) return new ArrayList<>();
        for (SerializableNode<String, MapAttribute> n : 
                map.getSerializableGraph().getAllNodes()) {
            if (n.getNodeId().equals(nodeId)) {
                return new ArrayList<>(map.getSerializableGraph().getEdges(nodeId));
            }
        }
        return new ArrayList<>();
    }
}