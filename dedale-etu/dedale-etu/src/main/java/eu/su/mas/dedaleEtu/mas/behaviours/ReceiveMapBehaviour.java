package eu.su.mas.dedaleEtu.mas.behaviours;

import dataStructures.serializableGraph.SerializableSimpleGraph;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation;
import eu.su.mas.dedaleEtu.mas.knowledge.MapRepresentation.MapAttribute;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

public class ReceiveMapBehaviour extends CyclicBehaviour {

    private static final long serialVersionUID = 1L;
    private MapRepresentation myMap;

    public ReceiveMapBehaviour(Agent a, MapRepresentation myMap) {
        super(a);
        this.myMap = myMap;
    }

    @Override
    public void action() {

        MessageTemplate mt = MessageTemplate.MatchProtocol("SHARE-TOPO");
        ACLMessage msg = myAgent.receive(mt);

        if (msg != null) {
            try {
            	SerializableSimpleGraph<String, MapAttribute> receivedGraph = (SerializableSimpleGraph<String, MapAttribute>) msg.getContentObject();   

                System.out.println(myAgent.getLocalName() + " ← carte reçue de "+ msg.getSender().getLocalName());

                // Mise à jour de la carte
                myMap.mergeMap(receivedGraph);

            } catch (UnreadableException e) {
                e.printStackTrace();
            }
        } else {
            block();
        }
    }
}
