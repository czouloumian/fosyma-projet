package eu.su.mas.dedaleEtu.mas.behaviours;

import java.io.Serializable;
import java.util.List;

public class HuntMessage implements Serializable {
    public enum Type { GOLEM_SPOTTED, HUNT_START }

    public Type type;
    public String golemNode;
    public String trapNode;
    public List<String> allAgentNames;

    public HuntMessage(Type type, String golemNode, String trapNode, List<String> allAgentNames) {
        this.type = type;
        this.golemNode = golemNode;
        this.trapNode = trapNode;
        this.allAgentNames = allAgentNames;
    }
}