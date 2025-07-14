package common;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface CanvasServerInterface extends Remote {
    InitData registerClient(String clientName, CanvasClientInterface client) throws RemoteException;
    void broadcastAction(CanvasAction action) throws RemoteException;
    void sendChatMessage(String sender, String message) throws RemoteException;
    void undoLastForUser(String username) throws RemoteException;
    void redoLastForUser(String username) throws RemoteException;
    void loadHistory(java.util.List<CanvasAction> history) throws RemoteException;
    void unregisterClient(String clientName) throws RemoteException;
    void clearBoard() throws RemoteException;
    void requestToJoin(String clientName, CanvasClientInterface clientStub) throws RemoteException;
    void approveJoin(String clientName) throws RemoteException;
    void rejectJoin(String clientName) throws RemoteException;
    java.util.List<String> getClientList() throws RemoteException;
    void closeBoard() throws RemoteException;
    void kickUser(String username) throws RemoteException;

}
