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

import java.util.*;

/**
 * Each agent tries to occupy a stench node neighboring Golem.
 * They broadcast which node they are claiming so no two agents pick the same one.
 *
 * Protocol (every tick):
 *  1. If on stench node, send message and stay
 *  2. If can see a stench node, pick one that is unoccupied and go there
 *  3. If no stench node visible, explore or go where the golem was last
 *
 * Claim messages: "CLAIM:<agentName>:<nodeId>"
 * Agents share claims every tick =
 */
public class HuntBehaviour extends TickerBehaviour {

    private static final String ONTOLOGY = "HUNT2";
    private static final String CLAIM    = "CLAIM:";

    private final List<String> allAgentNames;
    private final String myName;

    private final Map<String, String> claims = new HashMap<>();

    private String lastKnownStenchNode = null;

    private String myClaimedNode = null;

    public HuntBehaviour(AbstractDedaleAgent agent, List<String> allAgentNames) {
        super(agent, 600);
        this.allAgentNames = allAgentNames;
        this.myName        = agent.getLocalName();
    }

    @Override
    public void onTick() {
    	if (!((ExploreCoopAgent) this.myAgent).explorationDone) return;

        AbstractDedaleAgent me = (AbstractDedaleAgent) this.myAgent;

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

        if (target == null) {
            target = stenchNodes.get(0);
        }

        myClaimedNode = target;
        broadcastClaim(me, target);

        if (cur.getLocationId().equals(target)) {
            System.out.println("[" + myName + "] On stench node " + target + " ✓");
        } else {
            moveToward(me, target);
        }
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

        return null; // all taken
    }


    private void navigateTowardGolem(AbstractDedaleAgent me, Location cur) {
        if (lastKnownStenchNode != null) {
            System.out.println("[" + myName + "] No stench visible — moving toward last known: " + lastKnownStenchNode);
            moveToward(me, lastKnownStenchNode);
        } else {
            var obs = me.observe();
            if (obs == null || obs.isEmpty()) return;
            obs.stream()
                .filter(o -> !o.getLeft().getLocationId().equals(cur.getLocationId()))
                .findFirst()
                .ifPresent(o -> me.moveTo(o.getLeft()));
        }
    }

    private void moveToward(AbstractDedaleAgent me, String targetId) {
        Location cur = me.getCurrentPosition();
        if (cur == null || cur.getLocationId().equals(targetId)) return;

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
    
    

    /**
     * Returns all stench nodes visible from current position, excluding current node.
     */ 
    /*private List<String> findStenchNodes(AbstractDedaleAgent me) {
        List<String> result = new ArrayList<>();
        Location cur = me.getCurrentPosition();
        var observations = me.observe();
        if (observations == null) return result;

        for (var nodeObs : observations) {
            String nodeId = nodeObs.getLeft().getLocationId();
            if (cur != null && nodeId.equals(cur.getLocationId())) continue;
            for (var obs : nodeObs.getRight()) {
                if (obs.getLeft() == Observation.STENCH) {
                    result.add(nodeId);
                    break;
                }
            }
        }
        return result;
    } */


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
    
    
    private void broadcastClaim(AbstractDedaleAgent me, String nodeId) {
        String content = CLAIM + myName + ":" + nodeId;
        for (String name : allAgentNames) {
            ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
            acl.setSender(me.getAID());
            acl.setOntology(ONTOLOGY);
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
            MessageTemplate.MatchOntology(ONTOLOGY));

        ACLMessage msg;
        while ((msg = me.receive(mt)) != null) {
            String content = msg.getContent();
            if (content == null || !content.startsWith(CLAIM)) continue;
            String[] parts = content.substring(CLAIM.length()).split(":", 2);
            if (parts.length == 2) {
                claims.put(parts[0], parts[1]);
            }
        }
    }

    private void clearMyClaim(AbstractDedaleAgent me) {
        myClaimedNode = null;
        String content = CLAIM + myName + ":";
        for (String name : allAgentNames) {
            ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
            acl.setSender(me.getAID());
            acl.setOntology(ONTOLOGY);
            acl.setContent(content);
            acl.addReceiver(new AID(name, AID.ISLOCALNAME));
            me.sendMessage(acl);
        }
    }
}