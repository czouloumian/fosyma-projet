package eu.su.mas.dedaleEtu.mas.behaviours;

import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
//import eu.su.mas.dedale.mas.agents.dedaleDummyAgents.explo.ExploreCoopAgent;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.ExploreCoopAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.*;

import dataStructures.serializableGraph.SerializableNode;

public class HuntBehaviour extends TickerBehaviour {

    public enum HuntRole { UNDECIDED, TRAPPER, PUSHER }
    public enum HuntState { IDLE, GOING_TO_TRAP, BLOCKING }

    //private final MapRepresentation myMap;
    private final List<String> allAgentNames;
    private final String myName;

    private HuntRole role = HuntRole.UNDECIDED;
    private HuntState state = HuntState.IDLE;

    private String trapNode = null;
    private String golemNode = null;
    private String myTargetNode = null;

    public HuntBehaviour(AbstractDedaleAgent agent, List<String> allAgentNames) {
        super(agent, 600);
        this.allAgentNames = allAgentNames;
        this.myName = agent.getLocalName();
    }
    

    private Map<String, List<String>> getGraph() {
        MapRepresentation map = ((ExploreCoopAgent) this.myAgent).myMap;
        if (map == null) return new HashMap<>(); // not ready yet, return empty
        Map<String, List<String>> adj = new HashMap<>();
        for (SerializableNode<String, MapAttribute> n : map.getSerializableGraph().getAllNodes()) {
            adj.put(n.getNodeId(), new ArrayList<>(map.getSerializableGraph().getEdges(n.getNodeId())));
        }
        return adj;
    }


    @Override
    public void onTick() {
        AbstractDedaleAgent me = (AbstractDedaleAgent) this.myAgent;

        processMessages(me);

        if (state == HuntState.IDLE) {
            checkForStench(me);
            return;
        }

        if (role == HuntRole.TRAPPER) {
            doTrapper(me);
        } else if (role == HuntRole.PUSHER) {
            doPusher(me);
        }
    }


    private void checkForStench(AbstractDedaleAgent me) {
        Location pos = me.getCurrentPosition();
        if (pos == null) return;

        var observations = me.observe();
        for (var nodeObs : observations) {
            for (var obs : nodeObs.getRight()) {
                if (obs.getLeft() == Observation.STENCH) {
                    String stenchNode = nodeObs.getLeft().getLocationId();
                    String trap = HuntCoordinator.computeTrapNode(getGraph());
                    broadcastHuntStart(me, stenchNode, trap);
                    assignRoleAndTarget(stenchNode, trap);
                    return;
                }
            }
        }
    }


    private void broadcastHuntStart(AbstractDedaleAgent me, String golemNode, String trapNode) {
        ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology("HUNT");
        acl.setContent(golemNode + ";" + trapNode);

        for (String name : allAgentNames) {
            if (!name.equals(myName))
                acl.addReceiver(new AID(name, AID.ISLOCALNAME));
        }
        me.send(acl);
        System.out.println("[" + myName + "] HUNT STARTED — trap=" + trapNode + " golem~=" + golemNode);
    }

    private void processMessages(AbstractDedaleAgent me) {
        MessageTemplate mt = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("HUNT"));
        ACLMessage msg = me.receive(mt);
        if (msg == null) return;

        String content = msg.getContent();
        if (content == null) return;

        String[] parts = content.split(";");
        if (parts.length < 2) return;

        if (state == HuntState.IDLE) {
            assignRoleAndTarget(parts[0], parts[1]);
        }
    }


    private void assignRoleAndTarget(String detectedGolemNode, String trap) {
        this.golemNode = detectedGolemNode;
        this.trapNode = trap;

        List<String> trappers = HuntCoordinator.getTrappers(allAgentNames);
        this.role = trappers.contains(myName) ? HuntRole.TRAPPER : HuntRole.PUSHER;

        if (role == HuntRole.TRAPPER) {
            this.myTargetNode = trapNode;
            this.state = HuntState.GOING_TO_TRAP;
            System.out.println("[" + myName + "] Role: TRAPPER → heading to " + trapNode);
        } else {
            List<String> blockingSpots = HuntCoordinator.getBlockingPositions(
                golemNode, trapNode, getGraph());
            List<String> pushers = HuntCoordinator.getPushers(allAgentNames);
            int idx = pushers.indexOf(myName) % Math.max(1, blockingSpots.size());
            this.myTargetNode = blockingSpots.isEmpty() ? golemNode : blockingSpots.get(idx);
            this.state = HuntState.BLOCKING;
            System.out.println("[" + myName + "] Role: PUSHER → blocking at " + myTargetNode);
        }
    }


    private void doTrapper(AbstractDedaleAgent me) {
        if (myTargetNode == null) return;
        Location cur = me.getCurrentPosition();
        if (cur == null) return;

        if (cur.getLocationId().equals(myTargetNode)) {
            System.out.println("[" + myName + "] TRAPPER in position at " + myTargetNode + " ✓");
            return;
        }
        moveToward(me, myTargetNode);
    }


    private void doPusher(AbstractDedaleAgent me) {
        if (myTargetNode == null) return;
        Location cur = me.getCurrentPosition();
        if (cur == null) return;

        refreshGolemPosition(me);

        if (cur.getLocationId().equals(myTargetNode)) {
            System.out.println("[" + myName + "] PUSHER blocking at " + myTargetNode + " ✓");
            return;
        }
        moveToward(me, myTargetNode);
    }

    private void refreshGolemPosition(AbstractDedaleAgent me) {
        var observations = me.observe();
        for (var nodeObs : observations) {
            for (var obs : nodeObs.getRight()) {
                if (obs.getLeft() == Observation.STENCH) {
                    String newGolemArea = nodeObs.getLeft().getLocationId();
                    if (!newGolemArea.equals(golemNode)) {
                        golemNode = newGolemArea;
                        List<String> blockingSpots = HuntCoordinator.getBlockingPositions(
                            golemNode, trapNode, getGraph());
                        List<String> pushers = HuntCoordinator.getPushers(allAgentNames);
                        int idx = pushers.indexOf(myName) % Math.max(1, blockingSpots.size());
                        myTargetNode = blockingSpots.isEmpty() ? golemNode : blockingSpots.get(idx);
                        System.out.println("[" + myName + "] Golem moved → new block target: " + myTargetNode);
                    }
                    return;
                }
            }
        }
    }

    private void moveToward(AbstractDedaleAgent me, String targetId) {
        Location cur = me.getCurrentPosition();
        if (cur == null) return;

        MapRepresentation map = ((ExploreCoopAgent) me).myMap;
        if (map == null) return;

        List<String> path = map.getShortestPath(cur.getLocationId(), targetId);
        if (path == null || path.isEmpty()) return;
        String nextNode = path.get(0);

        var obs = me.observe();
        for (var nodeObs : obs) {
            if (nodeObs.getLeft().getLocationId().equals(nextNode)) {
                me.moveTo(nodeObs.getLeft());
                return;
            }
        }
    }
}