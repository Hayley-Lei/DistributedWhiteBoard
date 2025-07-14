package common;

import common.CanvasAction;

import java.io.Serializable;
import java.util.List;

public class InitData implements Serializable {
    private static final long serialVersionUID = 1L;
    public List<CanvasAction> history;
    public List<String> userList;
    public InitData(List<CanvasAction> history, List<String> userList) {
        this.history = history;
        this.userList = userList;
    }
}