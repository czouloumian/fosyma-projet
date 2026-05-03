package eu.su.mas.dedaleEtu.mas.behaviours;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;
import java.util.ArrayList;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import dataStructures.tuple.Couple;
import eu.su.mas.dedale.env.Location;
import eu.su.mas.dedale.env.Observation;
import eu.su.mas.dedale.env.gs.GsLocation;

import eu.su.mas.dedale.mas.AbstractDedaleAgent;

import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.behaviours.ShareMapBehaviourBest;
import eu.su.mas.dedaleEtu.mas.agents.dummies.explo.ExploreCoopAgent;

import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

/**
 * Exploration coopérative avec détection de blocage et choix robuste du point de rendez-vous.
 */
public class ExploCoopBehaviour extends SimpleBehaviour {

    private static final long serialVersionUID = 8567689731496787661L;

    private boolean finished = false;
    private boolean initialized = false;

    private MapRepresentation myMap;
    private List<String> list_agentNames;

    private int stuckCounter = 0;
    private String lastPosition = null;
    private String beforeLastPosition = null;

    // Suivi des nœuds fermés (visités) pour le point de rendez-vous
    private Set<String> closedNodes = new HashSet<>();

    // Variables pour la détection de blocage sur la cible courante
    private String currentTarget = null;
    private int blockedCounter = 0;
    private int lastKnownDistance = Integer.MAX_VALUE;

    public ExploCoopBehaviour(final AbstractDedaleAgent myagent, MapRepresentation myMap, List<String> agentNames) {
        super(myagent);
        this.myMap = myMap;
        this.list_agentNames = agentNames;
        System.out.println("[" + myagent.getLocalName() + "] ExploCoopBehaviour créé | list_agentNames = " + agentNames);
    }

    @Override
    public void action() {
        System.out.println("[" + myAgent.getLocalName() 
            + "] ExploCoop action | huntStarted=" + ((ExploreCoopAgent) myAgent).huntStarted
            + " | meetingPoint=" + ((ExploreCoopAgent) myAgent).meetingPoint
            + " | openNodes=" + (this.myMap != null ? this.myMap.getOpenNodes().size() : "null")
            + " | stuckCounter=" + stuckCounter
            + " | target=" + currentTarget);

        if (((ExploreCoopAgent) this.myAgent).huntStarted) {
            finished = true;
            return;
        }

        if (!initialized) {
            initialized = true;
            this.myMap = new MapRepresentation(this.myAgent.getLocalName());
            ((ExploreCoopAgent) this.myAgent).myMap = this.myMap;
            this.myAgent.addBehaviour(new ShareMapBehaviourBest(this.myAgent, 500, this.myMap, list_agentNames));
        }

        Location myPosition = ((AbstractDedaleAgent) this.myAgent).getCurrentPosition();
        if (myPosition == null) return;

        String currentPos = myPosition.getLocationId();

        if (currentPos.equals(lastPosition) || currentPos.equals(beforeLastPosition)) {
            stuckCounter++;
        } else {
            stuckCounter = 0;
        }
        beforeLastPosition = lastPosition;
        lastPosition = currentPos;

        List<Couple<Location, List<Couple<Observation, String>>>> lobs =
            ((AbstractDedaleAgent) this.myAgent).observe();

        Set<String> occupiedNodes = new HashSet<>();
        for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
            for (Couple<Observation, String> attr : obs.getRight()) {
                if (attr.getLeft() == Observation.AGENTNAME) {
                    occupiedNodes.add(obs.getLeft().getLocationId());
                }
            }
        }

        try {
            this.myAgent.doWait(1000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.myMap.addNode(currentPos, MapAttribute.closed);
        this.closedNodes.add(currentPos);  

        String immediateNextNode = null;
        Iterator<Couple<Location, List<Couple<Observation, String>>>> iter = lobs.iterator();
        while (iter.hasNext()) {
            Location accessibleNode = iter.next().getLeft();
            boolean isNewNode = this.myMap.addNewNode(accessibleNode.getLocationId());
            if (myPosition.getLocationId() != accessibleNode.getLocationId()) {
                this.myMap.addEdge(myPosition.getLocationId(), accessibleNode.getLocationId());
                if (immediateNextNode == null && isNewNode && !occupiedNodes.contains(accessibleNode.getLocationId())) {
                    immediateNextNode = accessibleNode.getLocationId();
                }
            }
        }

        boolean naturalEnd = !this.myMap.hasOpenNode();
        if (naturalEnd) {
            finished = true;
            ((ExploreCoopAgent) this.myAgent).huntStarted = true;
            ((ExploreCoopAgent) this.myAgent).explorationDone = true;
            String center = computeCentralNode();
            ((ExploreCoopAgent) this.myAgent).meetingPoint = center;
            System.out.println("[" + myAgent.getLocalName()
                + "] Fin exploration | RDV en " + center
                + " | total noeuds : " + this.myMap.getSerializableGraph().getAllNodes().size());
            return;
        }

        List<String> openNodes = myMap.getOpenNodes();
        String nextNodeId = null;

        if (immediateNextNode != null) {
            nextNodeId = immediateNextNode;
            currentTarget = null;
            blockedCounter = 0;
            lastKnownDistance = Integer.MAX_VALUE;
        } else {
            if (currentTarget == null || !openNodes.contains(currentTarget)) {
                currentTarget = selectClosestOpenNode(currentPos, openNodes);
                blockedCounter = 0;
                lastKnownDistance = Integer.MAX_VALUE;
            }

            if (currentTarget != null) {
                List<String> pathToTarget = myMap.getShortestPath(currentPos, currentTarget);
                int currentDistance = (pathToTarget != null) ? pathToTarget.size() : Integer.MAX_VALUE;

                if (currentDistance >= lastKnownDistance) {
                    blockedCounter++;
                } else {
                    blockedCounter = 0;
                }
                lastKnownDistance = currentDistance;

                if (blockedCounter >= 3) {
                    System.out.println("[" + myAgent.getLocalName() + "] Bloqué " + blockedCounter +
                                       " ticks vers " + currentTarget + " → changement de cible");
                    List<String> otherOpenNodes = new ArrayList<>(openNodes);
                    otherOpenNodes.remove(currentTarget);
                    if (!otherOpenNodes.isEmpty()) {
                        currentTarget = selectClosestOpenNode(currentPos, otherOpenNodes);
                    } else {
                        currentTarget = null;
                    }
                    blockedCounter = 0;
                    lastKnownDistance = Integer.MAX_VALUE;
                    pathToTarget = (currentTarget != null) ? myMap.getShortestPath(currentPos, currentTarget) : null;
                }

                if (currentTarget != null && pathToTarget != null && !pathToTarget.isEmpty()) {
                    nextNodeId = pathToTarget.get(0);
                }
            }
        }

        if (nextNodeId != null && occupiedNodes.contains(nextNodeId)) {
            List<String> neighbors = myMap.getNeighbors(currentPos);
            List<String> freeNeighbors = new ArrayList<>();
            for (String nb : neighbors) {
                if (!occupiedNodes.contains(nb) && !nb.equals(currentPos)) {
                    freeNeighbors.add(nb);
                }
            }
            if (!freeNeighbors.isEmpty()) {
                if (currentTarget != null) {
                    freeNeighbors.sort(Comparator.comparingInt(
                        nb -> myMap.getShortestPath(nb, currentTarget).size()
                    ));
                }
                nextNodeId = freeNeighbors.get(0);
            } else {
                nextNodeId = null;
            }
        }
        if (nextNodeId == null) {
            for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
                String neighbor = obs.getLeft().getLocationId();
                if (!neighbor.equals(currentPos) && !occupiedNodes.contains(neighbor)) {
                    nextNodeId = neighbor;
                    break;
                }
            }
        }

        if (nextNodeId != null && !nextNodeId.equals(lastPosition)) {
            stuckCounter = 0;
        }

        boolean success = false;
        if (nextNodeId != null) {
            success = ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(nextNodeId));
        }

        if (!success) {
            for (Couple<Location, List<Couple<Observation, String>>> obs : lobs) {
                String neighbor = obs.getLeft().getLocationId();
                if (!neighbor.equals(currentPos)) {
                    ((AbstractDedaleAgent) this.myAgent).moveTo(new GsLocation(neighbor));
                    break;
                }
            }
        }

        MessageTemplate msgTemplate = MessageTemplate.and(
                MessageTemplate.MatchProtocol("SHARE-TOPO"),
                MessageTemplate.MatchPerformative(ACLMessage.INFORM));
        ACLMessage msgReceived = this.myAgent.receive(msgTemplate);
        if (msgReceived != null) {
            SerializableSimpleGraph<String, MapAttribute> sgreceived = null;
            try {
                sgreceived = (SerializableSimpleGraph<String, MapAttribute>) msgReceived.getContentObject();
                System.out.println("[" + myAgent.getLocalName() + "] carte reçue de "
                        + msgReceived.getSender().getLocalName()
                        + " | noeuds reçus : " + sgreceived.getAllNodes().size());
            } catch (UnreadableException e) {
                e.printStackTrace();
            }
            this.myMap.mergeMap(sgreceived);
           
            ACLMessage ack = new ACLMessage(ACLMessage.CONFIRM);
            ack.setProtocol("SHARE-TOPO-ACK");
            ack.addReceiver(msgReceived.getSender());
            this.myAgent.send(ack);
        }
    }
    private String selectClosestOpenNode(String currentPos, List<String> openNodes) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String open : openNodes) {
            List<String> path = myMap.getShortestPath(currentPos, open);
            if (path != null && path.size() < bestDist) {
                bestDist = path.size();
                best = open;
            }
        }
        return best;
    }

    private String computeCentralNode() {
        String currentPos = ((AbstractDedaleAgent) myAgent).getCurrentPosition().getLocationId();
        List<String> accessibleClosed = new ArrayList<>();
        for (String node : closedNodes) {
            List<String> path = myMap.getShortestPath(currentPos, node);
            if (path != null) {
                accessibleClosed.add(node);
            }
        }
        if (!accessibleClosed.isEmpty()) {
            accessibleClosed.sort(Comparator.naturalOrder()); 
            return accessibleClosed.get(0);
        }
        return currentPos;
    }

    @Override
    public boolean done() {
        return finished;
    }
}