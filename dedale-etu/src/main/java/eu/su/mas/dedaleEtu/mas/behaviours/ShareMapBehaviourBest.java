package eu.su.mas.dedaleEtu.mas.behaviours;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

public class ShareMapBehaviourBest extends TickerBehaviour {

    private static final long serialVersionUID = -568863390879327961L;
    private static final int MIN_TICKS_BETWEEN_SENDS = 0;

    private MapRepresentation myMap;
    private List<String> receivers;

    // Tick du dernier envoi par agent : { "agent2" -> 3, "agent3" -> 7 }
    private Map<String, Integer> lastSentTick;
    
    private Map<String, Integer> pendingAckSentTick = new HashMap<>();
    private static final int ACK_TIMEOUT = 3;

    // Dernière carte envoyée par agent : { "agent2" -> graphe, "agent3" -> graphe }
    private Map<String, SerializableSimpleGraph<String, MapAttribute>> lastSentGraph;

    // Compteur de ticks interne
    private int currentTick;
    
    private boolean explorationDone = false;
    
    private Set<String> pendingAck = new HashSet<>();
    
    private Map<String, SerializableSimpleGraph<String, MapAttribute>> pendingGraph = new HashMap<>();

    public ShareMapBehaviourBest(Agent a, long period, MapRepresentation mymap,
                              List<String> receivers) {
        super(a, period);
        this.myMap        = mymap;
        this.receivers    = receivers;
        this.lastSentTick  = new HashMap<>();
        this.lastSentGraph = new HashMap<>();
        this.currentTick   = 0;
        System.out.println("[" + a.getLocalName() + "] ShareMapBehaviourBest créé"
                + " | receivers reçus = " + receivers);
    }

    @Override
    protected void onTick() {
        checkAcknowledgements();
        
        if (((eu.su.mas.dedaleEtu.mas.agents.dummies.explo.ExploreCoopAgent) myAgent).huntStarted) {
            System.out.println("[" + myAgent.getLocalName() + "] Hunt démarré → arrêt ShareMap");
            stop();
            return;
        }

        if (!myMap.hasOpenNode()) {
            if (pendingAck.isEmpty()) {
                System.out.println("[" + myAgent.getLocalName() + "] Exploration terminée + ACK OK, arrêt");
                stop();
                return;
            }
            boolean allTimedOut = pendingAck.stream().allMatch(agent ->
                currentTick - pendingAckSentTick.getOrDefault(agent, 0) > ACK_TIMEOUT
            );
            if (allTimedOut) {
                System.out.println("[" + myAgent.getLocalName() + "] Exploration terminée, ACK perdus → arrêt forcé");
                stop();
                return;
            }
            // Exploration terminée mais ACK encore en attente → on n'envoie plus rien
            return;
        }

        currentTick++;

        System.out.println("\n========== [" + myAgent.getLocalName()
            + "] TICK " + currentTick + " ==========");

        printDictionaryState();

        SerializableSimpleGraph<String, MapAttribute> currentGraph = this.myMap.getSerializableGraph();

        for (String agentName : receivers) {

            if (pendingAck.contains(agentName)) {
                System.out.println("  → " + agentName + " : en attente d'ACK, skip");
                continue;
            }

            int ticksSinceLastSend = currentTick
                - lastSentTick.getOrDefault(agentName, -MIN_TICKS_BETWEEN_SENDS);

            System.out.println("  → Agent cible : " + agentName
                + " | ticks depuis dernier envoi : " + ticksSinceLastSend
                + "/" + MIN_TICKS_BETWEEN_SENDS);

            if (ticksSinceLastSend < MIN_TICKS_BETWEEN_SENDS) {
                System.out.println("     ✗ SKIP : pas assez de ticks écoulés");
                continue;
            }

            SerializableSimpleGraph<String, MapAttribute> previousGraph = lastSentGraph.get(agentName);

            boolean changed = hasMapChanged(previousGraph, currentGraph);
            System.out.println("     Carte changée ? " + changed
                + " | noeuds courants : " + currentGraph.getAllNodes().size());

            if (previousGraph != null && !changed) {
                System.out.println("     ✗ SKIP : carte identique");
                continue;
            }

            sendMap(agentName, currentGraph);
            pendingGraph.put(agentName, currentGraph);
            pendingAck.add(agentName);
            pendingAckSentTick.put(agentName, currentTick);
        }
    }
    
    private void checkAcknowledgements() {
        MessageTemplate mt = MessageTemplate.and(
        MessageTemplate.MatchProtocol("SHARE-TOPO-ACK"),
        MessageTemplate.MatchPerformative(ACLMessage.CONFIRM));
        
        ACLMessage ack;
        while ((ack = myAgent.receive(mt)) != null) {
            String sender = ack.getSender().getLocalName();
            pendingAck.remove(sender);
            lastSentTick.put(sender, currentTick);
            lastSentGraph.put(sender, pendingGraph.get(sender)); 
            pendingGraph.remove(sender);
            System.out.println("[" + myAgent.getLocalName() + "] ACK reçu de " + sender);
        }
        Set<String> timedOut = new HashSet<>();
        for (String agent : pendingAck) {
            int sentTick = pendingAckSentTick.getOrDefault(agent, 0);
            if (currentTick - sentTick > ACK_TIMEOUT) {
                timedOut.add(agent);
                System.out.println("[" + myAgent.getLocalName() + "] ACK timeout pour " + agent + ", on réessaiera");
            }
        }
        for (String agent : timedOut) {
            pendingAck.remove(agent);
            pendingAckSentTick.remove(agent);
            lastSentGraph.put(agent, pendingGraph.get(agent));
            lastSentTick.put(agent, currentTick);
            pendingGraph.remove(agent);
            //lastSentTick.put(agent, currentTick - MIN_TICKS_BETWEEN_SENDS);
        }
    }

    /**
     * Affiche l'état complet des deux dictionnaires
     */
    private void printDictionaryState() {
        System.out.println("  [lastSentTick] " + lastSentTick.toString());

        System.out.print("  [lastSentGraph] { ");
        for (Map.Entry<String, SerializableSimpleGraph<String, MapAttribute>> entry 
             : lastSentGraph.entrySet()) {
            int nbNodes = entry.getValue() != null 
                ? entry.getValue().getAllNodes().size() : 0;
            System.out.print(entry.getKey() + " : " + nbNodes + " noeuds | ");
        }
        System.out.println("}");
    }
    /**
     * Envoie la carte à un agent donné.
     */
    private void sendMap(String agentName,
                          SerializableSimpleGraph<String, MapAttribute> graph) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setProtocol("SHARE-TOPO");
        msg.setSender(this.myAgent.getAID());
        msg.addReceiver(new AID(agentName, AID.ISLOCALNAME));

        try {
            msg.setContentObject(graph);
        } catch (IOException e) {
            e.printStackTrace();
        }

        ((AbstractDedaleAgent) this.myAgent).sendMessage(msg);

        System.out.println(myAgent.getLocalName()
            + " → carte envoyée à " + agentName
            + " (tick " + currentTick + ")");
    }

    /**
     * Compare deux graphes pour savoir si la carte a évolué.
     * On compare simplement le nombre de noeuds et d'arêtes.
     * (une comparaison plus fine est possible mais coûteuse)
     */
    private boolean hasMapChanged(
        SerializableSimpleGraph<String, MapAttribute> previous,
        SerializableSimpleGraph<String, MapAttribute> current) {
    	
    	if (previous!=null) {
	        // Nombre de noeuds différent → carte a changé
	        if (previous.getAllNodes().size() != current.getAllNodes().size()) {
	            return true;
	        }
	
	        // Nombre d'arêtes différent → carte a changé
	        int prevEdges = previous.getAllNodes().stream()
	            .mapToInt(n -> previous.getEdges(n.getNodeId()).size())
	            .sum();
	
	        int currEdges = current.getAllNodes().stream()
	            .mapToInt(n -> current.getEdges(n.getNodeId()).size())
	            .sum();
	
	        return prevEdges != currEdges;
    	}
    	return true;
    }
}
