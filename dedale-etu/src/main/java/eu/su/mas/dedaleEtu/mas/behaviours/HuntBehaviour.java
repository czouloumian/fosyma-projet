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
 *  1. Observe: am I on a stench node? Good — broadcast my claim and stay.
 *  2. Can I see stench nodes? Pick the unclaimed one closest to me and move there.
 *  3. No stench visible? Move toward last known golem area, or patrol randomly.
 *
 * Claim messages: "CLAIM:<agentName>:<nodeId>"
 * Agents share claims every tick so the assignment stays live.
 */
public class HuntBehaviour extends TickerBehaviour {

    private static final String ONTOLOGY = "HUNT2";
    private static final String CLAIM    = "CLAIM:";

    private final List<String> allAgentNames;
    private final String myName;

    // Claims received this tick: agentName → nodeId they are heading to / on
    private final Map<String, String> claims = new HashMap<>();

    // Last known stench area (to navigate toward when Golem out of sight)
    private String lastKnownStenchNode = null;

    // My current claimed node
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

        // Step 1: collect all claims from other agents sent this tick
        receiveClaims(me);

        // Step 2: observe current surroundings
        Location cur = me.getCurrentPosition();
        if (cur == null) return;

        List<String> stenchNodes = findStenchNodes(me);

        if (stenchNodes.isEmpty()) {
            // No stench visible — move toward last known area or patrol
            clearMyClaim(me);
            navigateTowardGolem(me, cur);
            return;
        }

        // We can see stench — update last known
        lastKnownStenchNode = stenchNodes.get(0);

        // Step 3: pick my target — unclaimed stench node closest to me
        String target = pickTarget(me, cur, stenchNodes);

        if (target == null) {
            // All stench nodes claimed by others — just stay put or move to closest
            target = stenchNodes.get(0);
        }

        // Step 4: broadcast my claim
        myClaimedNode = target;
        broadcastClaim(me, target);

        // Step 5: move toward target (or stay if already there)
        if (cur.getLocationId().equals(target)) {
            System.out.println("[" + myName + "] On stench node " + target + " ✓");
        } else {
            moveToward(me, target);
        }
    }

    // ── Claim selection ───────────────────────────────────────────────────────

    private String pickTarget(AbstractDedaleAgent me, Location cur, List<String> stenchNodes) {
        // Remove nodes claimed by others
        Set<String> takenByOthers = new HashSet<>();
        for (Map.Entry<String, String> e : claims.entrySet()) {
            if (!e.getKey().equals(myName)) {
                takenByOthers.add(e.getValue());
            }
        }

        MapRepresentation map = ((ExploreCoopAgent) me).myMap;

        // Sort stench nodes by distance from current position
        List<String> candidates = new ArrayList<>(stenchNodes);
        if (map != null) {
            candidates.sort(Comparator.comparingInt(n -> {
                List<String> path = map.getShortestPath(cur.getLocationId(), n);
                return path == null ? Integer.MAX_VALUE : path.size();
            }));
        }

        // Pick closest unclaimed node; if my current claim is still free, prefer it (stability)
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

    // ── Navigation ────────────────────────────────────────────────────────────

    private void navigateTowardGolem(AbstractDedaleAgent me, Location cur) {
        if (lastKnownStenchNode != null) {
            System.out.println("[" + myName + "] No stench visible — moving toward last known: " + lastKnownStenchNode);
            moveToward(me, lastKnownStenchNode);
        } else {
            // Truly lost — move to a random neighbor
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

    // ── Stench detection ──────────────────────────────────────────────────────

    /**
     * Returns all stench nodes visible from current position, excluding current node.
     */
    private List<String> findStenchNodes(AbstractDedaleAgent me) {
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
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    private void broadcastClaim(AbstractDedaleAgent me, String nodeId) {
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology(ONTOLOGY);
        acl.setContent(CLAIM + myName + ":" + nodeId);
        for (String name : allAgentNames) {
            acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        me.send(acl);
    }

    private void receiveClaims(AbstractDedaleAgent me) {
        // Clear old claims each tick — only keep what's fresh
        claims.clear();
        claims.put(myName, myClaimedNode != null ? myClaimedNode : "");

        MessageTemplate mt = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology(ONTOLOGY));

        ACLMessage msg;
        while ((msg = me.receive(mt)) != null) {
            String content = msg.getContent();
            if (content == null || !content.startsWith(CLAIM)) continue;
            // Format: CLAIM:<agentName>:<nodeId>
            String[] parts = content.substring(CLAIM.length()).split(":", 2);
            if (parts.length == 2) {
                claims.put(parts[0], parts[1]);
            }
        }
    }

    private void clearMyClaim(AbstractDedaleAgent me) {
        myClaimedNode = null;
        // Broadcast empty claim so others know we're not holding a node
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology(ONTOLOGY);
        acl.setContent(CLAIM + myName + ":");
        for (String name : allAgentNames) {
            acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        me.send(acl);
    }
}