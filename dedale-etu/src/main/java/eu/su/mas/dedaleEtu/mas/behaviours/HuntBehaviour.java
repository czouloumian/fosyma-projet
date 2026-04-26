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
    private int meetingTickCount = 0;
    private static final int MAX_MEETING_TICKS = 20;
    
    private final Map<String, String> claims = new HashMap<>();
    private String lastKnownStenchNode = null;
    private String myClaimedNode = null;
    private int patrolStuckCount = 0;
    private String lastPatrolPos = null;

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

    // Remplacer onTick() par :
    @Override
    public void onTick() {
        AbstractDedaleAgent me = (AbstractDedaleAgent) this.myAgent;
        if (((ExploreCoopAgent) me).myMap == null) return;

        processMessages(me);
        updateGolemPosition(me);

        if (state == HuntState.MEETING) {
            goToMeetingPoint(me);
            return;
        }

        if (state == HuntState.PATROL) {
            if (isLeader) leaderPatrol(me);
            else followerPatrol(me);
            // Vérifier stench pendant la patrouille
            updateGolemPosition(me);
            return;
        }

        // HUNTING — utiliser la nouvelle logique
        receiveClaims(me);

        Location cur = me.getCurrentPosition();
        if (cur == null) return;

        List<String> stenchNodes = findStenchNodes(me);

        if (stenchNodes.isEmpty()) {
            clearMyClaim(me);
            navigateTowardGolem(me, cur);
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

    // ==================== MEETING ====================

 // Ajouter dans les champs

    private void goToMeetingPoint(AbstractDedaleAgent me) {
        String meeting = ((ExploreCoopAgent) me).meetingPoint;
        if (meeting == null) return;

        Location cur = me.getCurrentPosition();
        if (cur == null) return;

        broadcastStatus(me);
        meetingTickCount++;

        String mySpot = getMyFixedSpot(me, meeting);
        if (mySpot == null) return;

        if (cur.getLocationId().equals(mySpot)) {
            System.out.println("[" + myName + "] Au RDV spot " + mySpot);
        }

        // Passer en PATROL soit quand tout le monde est arrivé, soit après timeout
        Set<String> meetingZone = new HashSet<>(
            getOrderedSpotsAroundMeeting(meeting, allAgentNames.size() + 1));

        boolean allArrived = allAgentNames.stream()
            .filter(a -> !a.equals(myName))
            .allMatch(a -> {
                String pos = agentPositions.get(a);
                return pos != null && meetingZone.contains(pos);
            });

        if (allArrived || meetingTickCount > MAX_MEETING_TICKS) {
            System.out.println("[" + myName + "] → PATROL ("
                + (allArrived ? "tous arrivés" : "timeout") + ")");
            state = HuntState.PATROL;
            return;
        }

        // Se déplacer vers le spot uniquement si on n'y est pas encore
        if (!cur.getLocationId().equals(mySpot)) {
            moveToward(me, mySpot);
        }
    }
    
    private String getMyFixedSpot(AbstractDedaleAgent me, String meeting) {
        List<String> sorted = new ArrayList<>(allAgentNames);
        sorted.add(myName);
        Collections.sort(sorted);
        int myIndex = sorted.indexOf(myName);

        List<String> spots = getOrderedSpotsAroundMeeting(meeting, sorted.size());

        if (myIndex < spots.size()) {
            return spots.get(myIndex);
        }
        return meeting;
    }

    private List<String> getOrderedSpotsAroundMeeting(String meeting, int needed) {
        MapRepresentation map = ((ExploreCoopAgent) myAgent).myMap;
        if (map == null) return new ArrayList<>();

        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        Map<String, Integer> dist = new HashMap<>();

        queue.add(meeting);
        visited.add(meeting);
        dist.put(meeting, 0);

        while (!queue.isEmpty() && result.size() < needed) {
            int levelSize = queue.size();
            List<String> currentLevel = new ArrayList<>();
            for (int i = 0; i < levelSize; i++) {
                currentLevel.add(queue.poll());
            }

            Collections.sort(currentLevel);

            for (String cur : currentLevel) {
                result.add(cur);
                if (result.size() >= needed) break;

                List<String> neighbors = getNeighbors(cur);
                Collections.sort(neighbors);
                for (String n : neighbors) {
                    if (!visited.contains(n)) {
                        visited.add(n);
                        dist.put(n, dist.get(cur) + 1);
                        queue.add(n);
                    }
                }
            }
        }

        return result;
    }

    // ==================== PATROL ====================

    private void leaderPatrol(AbstractDedaleAgent me) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        if (map == null) return;

        if (patrolTarget == null || cur.getLocationId().equals(patrolTarget)) {
            // Choisir un nœud très éloigné ET différent du dernier
            String newTarget = null;
            int attempts = 0;
            while ((newTarget == null || newTarget.equals(patrolTarget)) && attempts < 20) {
                newTarget = pickFarNode(map, cur.getLocationId());
                attempts++;
            }
            patrolTarget = newTarget;
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

        // Détecter si bloqué
        if (cur.getLocationId().equals(lastPatrolPos)) {
            patrolStuckCount++;
        } else {
            patrolStuckCount = 0;
            lastPatrolPos = cur.getLocationId();
        }

        // Si bloqué → choisir un nœud lointain indépendamment du leader
        if (patrolStuckCount > 5 || agentPositions.get(getLeaderName()) == null) {
            if (myTargetNode == null || cur.getLocationId().equals(myTargetNode)) {
                myTargetNode = pickFarNode(map, cur.getLocationId());
                System.out.println("[" + myName + "] Follower bloqué → cible indépendante : " + myTargetNode);
            }
            broadcastStatus(me);
            if (myTargetNode != null) moveToward(me, myTargetNode);
            return;
        }

        String leaderPos = agentPositions.get(getLeaderName());
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

    /*private void chooseTarget(AbstractDedaleAgent me) {
    	if (golemNode == null) {
            System.out.println("[" + myName + "] chooseTarget: golemNode null → skip");
            return; // Ne pas choisir de cible si on ne sait pas où est le golem
        }
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
        
        if (myTargetNode != null && golemNeighbors.contains(myTargetNode)) {
            return; // cible toujours valide, pas besoin de recalculer
        }

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
            if (map.getSerializableGraph().getAllNodes().stream()
                    .noneMatch(n -> n.getNodeId().equals(candidate))) {
                continue;
            }
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
    }*/

    // ==================== DETECTION ====================

    private void updateGolemPosition(AbstractDedaleAgent me) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;

        // Ne pas démarrer la chasse avant la fin de l'exploration
        if (((ExploreCoopAgent) me).meetingPoint == null) return;

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
                // Seulement si exploration terminée
                if (((ExploreCoopAgent) me).meetingPoint != null) {
                    state = HuntState.HUNTING;
                    ((ExploreCoopAgent) me).huntStarted = true;
                }
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
            if (state != HuntState.HUNTING 
                    && ((ExploreCoopAgent) me).meetingPoint != null) {
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

    /*private void moveToward(AbstractDedaleAgent me, String targetId) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;
        if (cur.getLocationId().equals(targetId)) return;

        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        if (map == null) return;

        boolean targetExists = map.getSerializableGraph().getAllNodes().stream()
            .anyMatch(n -> n.getNodeId().equals(targetId));
        if (!targetExists) {
            myTargetNode = null;
            return;
        }

        List<String> path = map.getShortestPath(cur.getLocationId(), targetId);
        if (path == null || path.isEmpty()) return;

        var obs = me.observe();
        // Essayer chaque nœud du chemin jusqu'à trouver un qui marche
        for (String nextNode : path) {
            for (var nodeObs : obs) {
                if (nodeObs.getLeft().getLocationId().equals(nextNode)) {
                    boolean success = me.moveTo(nodeObs.getLeft());
                    if (success) return;
                    break; // ce nœud est bloqué, essayer le suivant
                }
            }
            break; // on ne prend que le premier nœud du chemin
        }
        // Si bloqué → NE PAS remettre myTargetNode à null,
        // juste attendre le prochain tick
    }*/

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
    
    
    private String pickTarget(AbstractDedaleAgent me, Location cur, List<String> stenchNodes) {
        Set<String> takenByOthers = new HashSet<>();
        for (Map.Entry<String, String> e : claims.entrySet()) {
            if (!e.getKey().equals(myName)) {
                takenByOthers.add(e.getValue());
            }
        }

        MapRepresentation map = ((ExploreCoopAgent) me).myMap;

        List<String> candidates = new ArrayList<>(stenchNodes);
        if (map != null) {
            candidates.sort(Comparator.comparingInt(n -> {
                List<String> path = map.getShortestPath(cur.getLocationId(), n);
                return path == null ? Integer.MAX_VALUE : path.size();
            }));
        }

        if (myClaimedNode != null && candidates.contains(myClaimedNode)
                && !takenByOthers.contains(myClaimedNode)) {
            return myClaimedNode;
        }

        for (String candidate : candidates) {
            if (!takenByOthers.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void navigateTowardGolem(AbstractDedaleAgent me, Location cur) {
        if (lastKnownStenchNode == null) {
            // Vraiment aucune info → mouvement aléatoire
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
                if (!nodeId.equals(cur.getLocationId()) 
                        && !occupiedByAgents.contains(nodeId)) {
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

        // On sait où est le golem → chercher un voisin libre du golem
        List<String> golemNeighbors = getNeighbors(lastKnownStenchNode);
        
        // Nœuds déjà réclamés par d'autres
        Set<String> takenByOthers = new HashSet<>();
        for (Map.Entry<String, String> e : claims.entrySet()) {
            if (!e.getKey().equals(myName)) {
                takenByOthers.add(e.getValue());
            }
        }

        // Trouver le voisin libre le plus proche
        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        String bestTarget = null;
        int bestDist = Integer.MAX_VALUE;

        for (String neighbor : golemNeighbors) {
            if (takenByOthers.contains(neighbor)) continue;
            if (map == null) continue;
            
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
            // Tous les voisins pris → se rapprocher quand même du golem
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
            if (cur != null && nodeId.equals(cur.getLocationId())) continue;

            boolean hasStench = false;
            boolean hasWumpus = false;

            for (var obs : nodeObs.getRight()) {
                if (obs.getLeft() == Observation.STENCH) hasStench = true;
                if (obs.getLeft() == Observation.AGENTNAME
                    && "Wumpus".equals(obs.getRight())) hasWumpus = true;
            }
            if (hasStench && !hasWumpus) {
                result.add(nodeId);
            }
        }
        return result;
    }

    private void moveToward(AbstractDedaleAgent me, String targetId) {
        Location cur = me.getCurrentPosition();
        if (cur == null || cur.getLocationId().equals(targetId)) return;

        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        if (map == null) return;

        boolean targetExists = map.getSerializableGraph().getAllNodes().stream()
            .anyMatch(n -> n.getNodeId().equals(targetId));
        if (!targetExists) return;

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
            // Mettre à jour la position connue du golem
            if (parts.length >= 3 && !parts[2].isEmpty()) {
                lastKnownStenchNode = parts[2];
                System.out.println("[" + myName + "] Golem signalé en " 
                    + lastKnownStenchNode + " par " + parts[0]);
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
}