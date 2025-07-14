package common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface CanvasClientInterface extends Remote {
    void receiveAction(CanvasAction action) throws RemoteException;
    void receiveFullHistory(List<CanvasAction> history) throws RemoteException;
    void receiveChatMessage(String sender, String message) throws RemoteException;
    void updateUserList(List<String> userList) throws RemoteException;
    void notifyJoinRequest(String candidate) throws RemoteException;
    void notifyJoinDecision(boolean approved) throws RemoteException;
    void notifyBoardClosed() throws RemoteException;
    void receiveKick() throws RemoteException;


}
