package eu.su.mas.dedaleEtu.mas.behaviours;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import eu.su.mas.dedale.mas.AbstractDedaleAgent;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;

public class ShareMapBehaviourBest extends TickerBehaviour {

    private static final long serialVersionUID = -568863390879327961L;
    private static final int MIN_TICKS_BETWEEN_SENDS = 5;

    private MapRepresentation myMap;
    private List<String> receivers;

    // Tick du dernier envoi par agent : { "agent2" -> 3, "agent3" -> 7 }
    private Map<String, Integer> lastSentTick;

    // Dernière carte envoyée par agent : { "agent2" -> graphe, "agent3" -> graphe }
    private Map<String, SerializableSimpleGraph<String, MapAttribute>> lastSentGraph;

    // Compteur de ticks interne
    private int currentTick;

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
    	 System.out.println("[" + myAgent.getLocalName() + "] onTick receivers = " + receivers);
    	    
        currentTick++;

        System.out.println("\n========== [" + myAgent.getLocalName() 
            + "] TICK " + currentTick + " ==========");

        // Afficher l'état des dictionnaires
        printDictionaryState();

        SerializableSimpleGraph<String, MapAttribute> currentGraph =
            this.myMap.getSerializableGraph();

        for (String agentName : receivers) {

            int ticksSinceLastSend = currentTick
                - lastSentTick.getOrDefault(agentName, -MIN_TICKS_BETWEEN_SENDS);

            System.out.println("  → Agent cible : " + agentName
                + " | ticks depuis dernier envoi : " + ticksSinceLastSend
                + "/" + MIN_TICKS_BETWEEN_SENDS);

            if (ticksSinceLastSend < MIN_TICKS_BETWEEN_SENDS) {
                System.out.println("     ✗ SKIP : pas assez de ticks écoulés");
                continue;
            }

            SerializableSimpleGraph<String, MapAttribute> previousGraph =
                lastSentGraph.get(agentName);

            boolean changed = hasMapChanged(previousGraph, currentGraph);
            System.out.println("     Carte changée ? " + changed
                + " | noeuds courants : " + currentGraph.getAllNodes().size());

            if (previousGraph != null && !changed) {
                System.out.println("     ✗ SKIP : carte identique");
                continue;
            }

            sendMap(agentName, currentGraph);
            lastSentTick.put(agentName, currentTick);
            lastSentGraph.put(agentName, currentGraph);
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
