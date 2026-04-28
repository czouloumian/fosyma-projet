package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.ExploreCoopAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import dataStructures.serializableGraph.SerializableNode;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class HuntBehaviour extends TickerBehaviour {

    public enum HuntState { MEETING, PATROL, HUNTING, BLOCKING }

    private final List<String> allAgentNames;
    private final String myName;
    private final boolean isLeader;

    private HuntState state = HuntState.PATROL;

    private String golemNode = null;
    private String myTargetNode = null;
    private String patrolTarget = null;

    private Map<String, String> agentPositions = new HashMap<>();
    private Map<String, String> agentTargets = new HashMap<>();
    private Map<String, String> stenchPositions = new HashMap<>();

    private static final int FORMATION_RADIUS = 2;
    private int meetingTickCount = 0;
    private static final int MAX_MEETING_TICKS = 20;
    
    private final Map<String, String> claims = new HashMap<>();
    private String lastKnownStenchNode = null;
    private String myClaimedNode = null;
    private int patrolStuckCount = 0;
    private String lastPatrolPos = null;
    private int failedMoveCount = 0;
    private String coordinatorName;
    
    private String lastSeenGolemNode = null;
    private int golemSightCount = 0;
    private static final int BLOCK_THRESHOLD = 20;
    private static final int BLOCKED_GOLEM_STENCH_RADIUS = 1;
    private final Set<String> blockedGolems = new HashSet<>();
    int lostCount = 0;
    int lastTimeSeen = 0;
    private static final int RELAY_LIMIT = 5;
    
    // Constructeur à 3 arguments (utilisé en interne)
    public HuntBehaviour(ExploreCoopAgent agent, List<String> allAgentNames, String coordinatorName) {
        super(agent, Constants.stopTimeHunt);
        this.allAgentNames = allAgentNames;
        this.myName = agent.getLocalName();
        this.myAgent = agent;
        List<String> sorted = new ArrayList<>(allAgentNames);
        sorted.add(agent.getLocalName());
        Collections.sort(sorted);
        this.isLeader = false;
        this.coordinatorName = coordinatorName;
        lostCount = 0;
        System.out.println("[" + myName + "] HuntBehaviour initialisé (coordinator=" + coordinatorName + ")");
    }

    // Constructeur à 2 arguments pour compatibilité avec l'ancien appel
    public HuntBehaviour(ExploreCoopAgent agent, List<String> allAgentNames) {
        this(agent, allAgentNames, agent.getLocalName());
    }

    @Override
    public void onTick() {
        AbstractDedaleAgent me = (AbstractDedaleAgent) this.myAgent;
        ExploreCoopAgent coop = (ExploreCoopAgent) me;
        
        // Attendre la fin de l'exploration
        if (coop.meetingPoint == null) return;
        if (coop.myMap == null) return;
        
        // Affichage d'état
        if (state == HuntState.PATROL) 
            System.out.println("                                            " + me.getLocalName() + " EST EN PATROUILLE");
        if (state == HuntState.HUNTING) 
            System.out.println("                                               " + me.getLocalName() + " EST EN CHASSE");
        if (state == HuntState.BLOCKING) 
            System.out.println("                                           " + me.getLocalName() + " EST EN BLOQUAGE");
        
        if (state == HuntState.BLOCKING) {
            blocking(me, golemNode);
            return;
        }
        
        updateGolemPosition(me);
        
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        
        boolean adjacent = coop.myMap.getNeighbors(golemNode).contains(cur.getLocationId());
        if (golemSightCount >= BLOCK_THRESHOLD && state != HuntState.BLOCKING && adjacent) {
            state = HuntState.BLOCKING;
            addBlockedGolemZone(golemNode);
            return;
        }
        if (golemSightCount > 0) {
            pauseExecution(Constants.stopTimeHunt);
            return;
        }
        
        processMessages(me);
        
        if (state == HuntState.PATROL) {
            followerPatrol(me);
            updateGolemPosition(me);
            return;
        }
        
        // HUNTING
        receiveClaims(me);
        
        List<String> stenchNodes = findStenchNodes(me);
        
        if (stenchNodes.isEmpty()) {
            clearMyClaim(me);
            navigateTowardGolem(me, cur);
            lostCount++;
            if (lostCount > 10) {
                state = HuntState.PATROL;
                lostCount = 0;
                return;
            }
            return;
        }
        
        lastKnownStenchNode = stenchNodes.get(0);
        String target = pickTarget(me, cur, stenchNodes);
        if (target == null) target = stenchNodes.get(0);
        
        myClaimedNode = target;
        broadcastClaim(me, target);
        
        if (cur.getLocationId().equals(target)) {
            System.out.println("[" + myName + "] On stench node " + target + " ✓");
        } else {
            moveToward(me, target);
        }
    }

    // ==================== PATROL ====================
    private void followerPatrol(AbstractDedaleAgent me) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        ExploreCoopAgent coop = (ExploreCoopAgent) me;
        MapRepresentation map = coop.myMap;
        if (map == null) return;

        // Détection de stagnation
        if (cur.getLocationId().equals(lastPatrolPos)) {
            patrolStuckCount++;
        } else {
            patrolStuckCount = 0;
            failedMoveCount = 0;
            lastPatrolPos = cur.getLocationId();
        }

        boolean reallyStuck = patrolStuckCount > 5;  // bloqué trop longtemps

        // Changer de cible si nécessaire (sans broadcast)
        if (myTargetNode == null || cur.getLocationId().equals(myTargetNode) || reallyStuck) {
            patrolStuckCount = 0;
            String target = pickFarNode(map, cur.getLocationId());
            if (target != null && !target.equals(myTargetNode)) {
                myTargetNode = target;
                System.out.println("[" + myName + "] Nouvelle cible patrouille : " + myTargetNode);
            }
        }

        broadcastStatus(me);
        if (myTargetNode != null && !cur.getLocationId().equals(myTargetNode)) {
            boolean moved = moveToward(me, myTargetNode);
            if (!moved) {
                failedMoveCount++;
            } else {
                failedMoveCount = 0;
            }
        }
    }

    // ==================== DETECTION ====================
    private void updateGolemPosition(AbstractDedaleAgent me) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        ExploreCoopAgent coop = (ExploreCoopAgent) me;
        if (coop.myMap == null) return;
        
        var observations = me.observe();
        String stenchNode = null;
        boolean wumpusSeenThisTick = false;
        
        for (var nodeObs : observations) {
            for (var obs : nodeObs.getRight()) {
                if (obs.getLeft() == Observation.AGENTNAME
                        && obs.getRight() != null
                        && obs.getRight().toLowerCase().contains("wumpus")) {
                    String detected = nodeObs.getLeft().getLocationId();
                    if (blockedGolems.contains(detected)) continue;
                    wumpusSeenThisTick = true;
                    if (detected.equals(lastSeenGolemNode)) {
                        golemSightCount++;
                    } else {
                        golemSightCount = 1;
                    }
                    lastSeenGolemNode = detected;
                    
                    if (state != HuntState.BLOCKING && state != HuntState.HUNTING) {
                        state = HuntState.HUNTING;
                        golemNode = detected;
                        myTargetNode = null;
                        coop.huntStarted = true;
                        broadcastGolem(me, golemNode);
                        System.out.println("[" + myName + "] Golem VU en " + golemNode);
                    } else if (state != HuntState.BLOCKING && state == HuntState.HUNTING && !detected.equals(golemNode)) {
                        golemNode = detected;
                        myTargetNode = null;
                        broadcastGolem(me, golemNode);
                    }
                    break;
                }
                if (obs.getLeft() == Observation.STENCH) {
                    stenchNode = nodeObs.getLeft().getLocationId();
                }
            }
        }
        
        if (!wumpusSeenThisTick) golemSightCount = 0;
        
        if (stenchNode != null && !blockedGolems.contains(stenchNode)) {
            broadcastStench(me, stenchNode);
            if (state != HuntState.HUNTING && state != HuntState.BLOCKING && golemSightCount > 0) {
                state = HuntState.HUNTING;
                golemNode = stenchNode;
                myTargetNode = null;
                coop.huntStarted = true;
            } else if (state == HuntState.HUNTING && golemNode == null) {
                golemNode = stenchNode;
                myTargetNode = null;
            }
        }
    }
    
    // ==================== BLOCKING ====================
    private void blocking(AbstractDedaleAgent me, String golemNode) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        ExploreCoopAgent coop = (ExploreCoopAgent) me;
        
        var observations = me.observe();
        boolean wumpusSeenThisTick = false;
        for (var nodeObs : observations) {
            for (var obs : nodeObs.getRight()) {
                if (obs.getLeft() == Observation.AGENTNAME
                        && obs.getRight() != null
                        && obs.getRight().toLowerCase().contains("wumpus")) {
                    wumpusSeenThisTick = true;
                    break;
                }
            }
        }
        
        // Si le golem est encore présent, on met à jour la zone bloquée
        if (wumpusSeenThisTick) {
            blockedGolems.add(golemNode);
            if (coop.myMap != null) {
                Set<String> zone = new HashSet<>();
                Queue<String> queue = new LinkedList<>();
                Map<String, Integer> dist = new HashMap<>();
                queue.add(golemNode);
                dist.put(golemNode, 0);
                zone.add(golemNode);
                while (!queue.isEmpty()) {
                    String curr = queue.poll();
                    int d = dist.get(curr);
                    if (d >= BLOCKED_GOLEM_STENCH_RADIUS) continue;
                    for (String neighbor : coop.myMap.getNeighbors(curr)) {
                        if (!dist.containsKey(neighbor)) {
                            dist.put(neighbor, d + 1);
                            zone.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }
                blockedGolems.addAll(zone);
            }
            broadcastGolemBlocked(me);
        }
        pauseExecution(Constants.stopTimeHunt);
    }
    
    // ==================== MESSAGES ====================
    private void processMessages(AbstractDedaleAgent me) {
        ExploreCoopAgent coop = (ExploreCoopAgent) me;
        MessageTemplate mtGolem = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("GOLEM-POS"));
        ACLMessage msg;
        while ((msg = me.receive(mtGolem)) != null) {
            String newGolem = msg.getContent();
            if (newGolem != null && !blockedGolems.contains(newGolem)) {
                golemNode = newGolem;
                myTargetNode = null;
                state = HuntState.HUNTING;
                coop.huntStarted = true;
                System.out.println("[" + myName + "] Golem signalé en " + golemNode + " par " + msg.getSender().getLocalName());
            }
        }
        
        MessageTemplate mtStench = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("STENCH-POS"));
        while ((msg = me.receive(mtStench)) != null) {
            String[] parts = msg.getContent().split(";");
            if (parts.length < 3) continue;
            int lastTime = Integer.parseInt(parts[2]);
            if (lastTime > RELAY_LIMIT) continue;
            else lastTimeSeen = lastTime + 1;
            String stenchSource = parts[1];
            if (blockedGolems.contains(stenchSource)) continue;
            stenchPositions.put(parts[0], parts[1]);
            if (state != HuntState.HUNTING) {
                state = HuntState.HUNTING;
                coop.huntStarted = true;
                golemNode = parts[1];
                myTargetNode = null;
                System.out.println("[" + myName + "] Chasse via stench de " + parts[0] + " en " + parts[1]);
            }
        }
        
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
        
        MessageTemplate mtBlocked = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.MatchOntology("GOLEM-BLOCKED"));
        while ((msg = me.receive(mtBlocked)) != null) {
            String content = msg.getContent();
            if (content != null && !content.isEmpty()) {
                String[] golems = content.split(":");
                for (String golem : golems) {
                    addBlockedGolemZone(golem);
                    System.out.println("[" + myName + "] Golem " + golem + " est bloqué (zone ajoutée)");
                }
            }
        }
    }
    
    private void broadcastStatus(AbstractDedaleAgent me) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        String target = myTargetNode != null ? myTargetNode : cur.getLocationId();
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("AGENT-STATUS");
        acl.setContent(cur.getLocationId() + ";" + target);
        acl.setSender(me.getAID());
        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        ((AbstractDedaleAgent) me).sendMessage(acl);
    }
    
    private void broadcastGolem(AbstractDedaleAgent me, String golemPos) {
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("GOLEM-POS");
        acl.setContent(golemPos);
        acl.setSender(me.getAID());
        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        ((AbstractDedaleAgent) me).sendMessage(acl);
    }
    
    private void broadcastStench(AbstractDedaleAgent me, String stenchNode) {
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("STENCH-POS");
        acl.setContent(myName + ";" + stenchNode + ";" + String.valueOf(lastTimeSeen));
        acl.setSender(me.getAID());
        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        ((AbstractDedaleAgent) me).sendMessage(acl);
    }
    
    private void broadcastClaim(AbstractDedaleAgent me, String nodeId) {
        String golemPart = lastKnownStenchNode != null ? lastKnownStenchNode : "";
        String content = "CLAIM:" + myName + ":" + nodeId + ":" + golemPart;
        for (String name : allAgentNames) {
            ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
            acl.setSender(me.getAID());
            acl.setOntology("HUNT2");
            acl.setContent(content);
            acl.addReceiver(new AID(name, AID.ISLOCALNAME));
            me.sendMessage(acl);
        }
    }
    
    private void broadcastGolemBlocked(AbstractDedaleAgent me) {
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("GOLEM-BLOCKED");
        String content = String.join(":", blockedGolems);
        acl.setContent(content);
        acl.setSender(me.getAID());
        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        ((AbstractDedaleAgent) me).sendMessage(acl);
    }
    
    private void receiveClaims(AbstractDedaleAgent me) {
        claims.clear();
        claims.put(myName, myClaimedNode != null ? myClaimedNode : "");
        MessageTemplate mt = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("HUNT2"));
        ACLMessage msg;
        while ((msg = me.receive(mt)) != null) {
            String content = msg.getContent();
            if (content == null || !content.startsWith("CLAIM:")) continue;
            String[] parts = content.substring("CLAIM:".length()).split(":", 3);
            if (parts.length >= 2) {
                claims.put(parts[0], parts[1]);
            }
            if (parts.length >= 3 && !parts[2].isEmpty()) {
                lastKnownStenchNode = parts[2];
                System.out.println("[" + myName + "] Golem signalé en " + lastKnownStenchNode + " par " + parts[0]);
            }
        }
    }
    
    private void clearMyClaim(AbstractDedaleAgent me) {
        myClaimedNode = null;
        String content = "CLAIM:" + myName + ":";
        for (String name : allAgentNames) {
            ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
            acl.setSender(me.getAID());
            acl.setOntology("HUNT2");
            acl.setContent(content);
            acl.addReceiver(new AID(name, AID.ISLOCALNAME));
            me.sendMessage(acl);
        }
    }
    
    private void addBlockedGolemZone(String golemNode) {
        blockedGolems.add(golemNode);
    }
    
    // ==================== UTILITAIRES ====================
    private String pickFarNode(MapRepresentation map, String currentId) {
        var allNodes = map.getSerializableGraph().getAllNodes();
        if (allNodes.isEmpty()) return null;

        // Récupérer tous les nœuds accessibles avec leur distance
        List<Map.Entry<String, Integer>> reachable = new ArrayList<>();
        for (var node : allNodes) {
            String nodeId = node.getNodeId();
            if (nodeId.equals(currentId)) continue;
            List<String> path = map.getShortestPath(currentId, nodeId);
            if (path != null) {
                reachable.add(new AbstractMap.SimpleEntry<>(nodeId, path.size()));
            }
        }
        if (reachable.isEmpty()) return null;

        // Distance maximale
        int maxDist = reachable.stream().max(Map.Entry.comparingByValue()).get().getValue();
        int threshold = Math.max(2, (int)(maxDist * 0.7)); // 70% de la distance max

        // Nœuds éloignés
        List<String> farNodes = reachable.stream()
                .filter(e -> e.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (farNodes.isEmpty()) {
            farNodes = reachable.stream().map(Map.Entry::getKey).collect(Collectors.toList());
        }

        Random rand = new Random();
        return farNodes.get(rand.nextInt(farNodes.size()));
    }
    
    private String pickTarget(AbstractDedaleAgent me, Location cur, List<String> stenchNodes) {
        Set<String> takenByOthers = new HashSet<>();
        for (Map.Entry<String, String> e : claims.entrySet()) {
            if (!e.getKey().equals(myName)) takenByOthers.add(e.getValue());
        }
        ExploreCoopAgent coop = (ExploreCoopAgent) me;
        MapRepresentation map = coop.myMap;
        List<String> candidates = stenchNodes.stream()
                .filter(n -> !blockedGolems.contains(n))
                .collect(Collectors.toList());
        if (map != null) {
            candidates.sort(Comparator.comparingInt(n -> {
                List<String> path = map.getShortestPath(cur.getLocationId(), n);
                return path == null ? Integer.MAX_VALUE : path.size();
            }));
        }
        if (myClaimedNode != null && candidates.contains(myClaimedNode) && !takenByOthers.contains(myClaimedNode)) {
            return myClaimedNode;
        }
        for (String candidate : candidates) {
            if (!takenByOthers.contains(candidate)) return candidate;
        }
        return null;
    }
    
    private void navigateTowardGolem(AbstractDedaleAgent me, Location cur) {
        ExploreCoopAgent coop = (ExploreCoopAgent) me;
        if (coop.myMap == null) return;
        if (lastKnownStenchNode != null && blockedGolems.contains(lastKnownStenchNode)) {
            lastKnownStenchNode = null;
        }
        if (lastKnownStenchNode == null) {
            var obs = me.observe();
            if (obs == null || obs.isEmpty()) return;
            Set<String> occupiedByAgents = new HashSet<>();
            for (var nodeObs : obs) {
                for (var o : nodeObs.getRight()) {
                    if (o.getLeft() == Observation.AGENTNAME) {
                        occupiedByAgents.add(nodeObs.getLeft().getLocationId());
                    }
                }
            }
            List<String> freeNeighbors = new ArrayList<>();
            for (var nodeObs : obs) {
                String nodeId = nodeObs.getLeft().getLocationId();
                if (!nodeId.equals(cur.getLocationId()) && !occupiedByAgents.contains(nodeId)) {
                    freeNeighbors.add(nodeId);
                }
            }
            if (freeNeighbors.isEmpty()) return;
            String next = freeNeighbors.get(new Random().nextInt(freeNeighbors.size()));
            for (var nodeObs : obs) {
                if (nodeObs.getLeft().getLocationId().equals(next)) {
                    me.moveTo(nodeObs.getLeft());
                    return;
                }
            }
            return;
        }
        if (lastKnownStenchNode != null) {
            List<String> golemNeighbors = coop.myMap.getNeighbors(lastKnownStenchNode);
            if (golemNeighbors.contains(cur.getLocationId())) return;
        }
        MapRepresentation map = coop.myMap;
        List<String> golemNeighbors = map.getNeighbors(lastKnownStenchNode);
        Set<String> takenByOthers = new HashSet<>();
        for (Map.Entry<String, String> e : claims.entrySet()) {
            if (!e.getKey().equals(myName)) takenByOthers.add(e.getValue());
        }
        String bestTarget = null;
        int bestDist = Integer.MAX_VALUE;
        for (String neighbor : golemNeighbors) {
            if (takenByOthers.contains(neighbor)) continue;
            boolean exists = map.getSerializableGraph().getAllNodes().stream()
                    .anyMatch(n -> n.getNodeId().equals(neighbor));
            if (!exists) continue;
            List<String> path = map.getShortestPath(cur.getLocationId(), neighbor);
            int dist = (path == null) ? Integer.MAX_VALUE : path.size();
            if (dist < bestDist) {
                bestDist = dist;
                bestTarget = neighbor;
            }
        }
        if (bestTarget != null) {
            System.out.println("[" + myName + "] → voisin libre du golem : " + bestTarget);
            myClaimedNode = bestTarget;
            broadcastClaim(me, bestTarget);
            moveToward(me, bestTarget);
        } else {
            System.out.println("[" + myName + "] Tous voisins pris → rapprochement du golem");
            moveToward(me, lastKnownStenchNode);
        }
    }
    
    private List<String> findStenchNodes(AbstractDedaleAgent me) {
        List<String> result = new ArrayList<>();
        Location cur = me.getCurrentPosition();
        var observations = me.observe();
        if (observations == null) return result;
        for (var nodeObs : observations) {
            String nodeId = nodeObs.getLeft().getLocationId();
            if (blockedGolems.contains(nodeId)) continue;
            if (cur != null && nodeId.equals(cur.getLocationId())) continue;
            boolean hasStench = false;
            boolean hasWumpus = false;
            for (var obs : nodeObs.getRight()) {
                if (obs.getLeft() == Observation.STENCH) hasStench = true;
                if (obs.getLeft() == Observation.AGENTNAME && "Wumpus".equals(obs.getRight())) hasWumpus = true;
            }
            if (hasStench && !hasWumpus && !agentPositions.containsValue(nodeId)) {
                result.add(nodeId);
            }
        }
        return result;
    }
    
    private boolean moveToward(AbstractDedaleAgent me, String targetId) {
        Location cur = me.getCurrentPosition();
        if (cur == null || cur.getLocationId().equals(targetId)) return false;
        ExploreCoopAgent coop = (ExploreCoopAgent) me;
        MapRepresentation map = coop.myMap;
        if (map == null) return false;
        boolean targetExists = map.getSerializableGraph().getAllNodes().stream()
                .anyMatch(n -> n.getNodeId().equals(targetId));
        if (!targetExists) return false;
        List<String> path = map.getShortestPath(cur.getLocationId(), targetId);
        if (path == null || path.isEmpty()) return false;
        String nextNode = path.get(0);
        var obs = me.observe();
        if (obs == null) return false;
        
        if (agentPositions.containsValue(nextNode) && !nextNode.equals(cur.getLocationId())) {
    	    return false;  
    	}
        
        for (var nodeObs : obs) {
            if (nodeObs.getLeft().getLocationId().equals(nextNode)) {
                return me.moveTo(nodeObs.getLeft());
            }
        }
        return false;
    }
    
    private void pauseExecution(long duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}