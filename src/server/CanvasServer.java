package server;

import common.*;

import javax.swing.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.RemoteException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;

/**
 * RMI server implementation that manages client registration,
 * drawing actions, chat messages, and board lifecycle.
 */
public class CanvasServer extends UnicastRemoteObject implements CanvasServerInterface {
    // Manager username (first connected client)
    private String adminName = null;
    // Pending join requests waiting for manager approval
    private final Map<String, CanvasClientInterface> pending = new ConcurrentHashMap<>();

    // Active client stubs keyed by username
    private final Map<String, CanvasClientInterface> clients = new ConcurrentHashMap<>();
    // Recorded drawing actions in order
    private final List<CanvasAction> history = new ArrayList<>();
    // Stack of removed strokes for redo support
    private final Map<String, Stack<List<CanvasAction>>> userUndoStacks = new ConcurrentHashMap<>();
    private final Map<String, Stack<List<CanvasAction>>> userRedoStacks = new ConcurrentHashMap<>();

    /**
     * Export the remote object.
     */
    protected CanvasServer() throws RemoteException {
        super();
    }

    /**
     * Register a new client or manager, then broadcast updated user list.
     */
    @Override
    public synchronized InitData registerClient(String name, CanvasClientInterface client) {
        if (adminName == null) {
            adminName = name;
        }
        clients.put(name, client);
        broadcastUserList();
        return new InitData(new ArrayList<>(history), new ArrayList<>(clients.keySet()));
    }

    /**
     * Unregister a client, then broadcast updated user list.
     */
    @Override
    public synchronized void unregisterClient(String name) throws RemoteException {
        clients.remove(name);
        System.out.println("Client '" + name + "' has disconnected.");
        broadcastUserList();
    }

    /**
     * Send the current list of online users to all clients.
     */
    private void broadcastUserList() {
        List<String> users = new ArrayList<>(clients.keySet());
        for (CanvasClientInterface c : clients.values()) {
            try {
                c.updateUserList(users);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Record a new drawing action and broadcast it to all clients.
     */
    @Override
    public synchronized void broadcastAction(CanvasAction action) throws RemoteException {
        history.add(action);
        userRedoStacks.remove(action.owner); // clear redo history on new action
        for (CanvasClientInterface c : clients.values()) {
            c.receiveAction(action);
        }
    }

    /**
     * Broadcast a chat message from one client to all others.
     */
    @Override
    public synchronized void sendChatMessage(String sender, String message) throws RemoteException {
        for (CanvasClientInterface c : clients.values()) {
            c.receiveChatMessage(sender, message);
        }
    }

    /** Undo only the given user’s last stroke */
    @Override
    public synchronized void undoLastForUser(String username) throws RemoteException {
        // find that user’s last strokeId
        Long lastSid = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            CanvasAction a = history.get(i);
            if (username.equals(a.owner)) {
                lastSid = a.strokeId; break;
            }
        }
        if (lastSid == null) return;
        // remove that stroke’s actions
        List<CanvasAction> removed = new ArrayList<>();
        Iterator<CanvasAction> it = history.iterator();
        while (it.hasNext()) {
            CanvasAction a = it.next();
            if (a.strokeId == lastSid && username.equals(a.owner)) {
                removed.add(a);
                it.remove();
            }
        }
        Stack<List<CanvasAction>> redoStack =
                userRedoStacks.computeIfAbsent(username, k -> new Stack<>());
        redoStack.push(removed);

        // broadcast full history
        for (CanvasClientInterface c : clients.values()) {
            c.receiveFullHistory(new ArrayList<>(history));
        }
    }

    /** Redo only the given user’s last undone stroke */
    @Override
    public synchronized void redoLastForUser(String username) throws RemoteException {
        Stack<List<CanvasAction>> rStack =
                userRedoStacks.computeIfAbsent(username, k-> new Stack<>());
        if (rStack.isEmpty()) return;
        List<CanvasAction> toRestore = rStack.pop();
        history.addAll(toRestore);
        // push back onto undo stack
//        userUndoStacks.computeIfAbsent(username, k-> new Stack<>()).push(toRestore);
        // broadcast only these new actions
        for (CanvasAction a : toRestore) {
            for (CanvasClientInterface c : clients.values()) {
                c.receiveAction(a);
            }
        }
    }
    /**
     * Replace the entire history with the provided list and broadcast it.
     */
    @Override
    public synchronized void loadHistory(List<CanvasAction> newHistory) throws RemoteException {
        history.clear();
        history.addAll(newHistory);
        userUndoStacks.clear();
        userRedoStacks.clear();
        for (CanvasClientInterface c : clients.values()) {
            c.receiveFullHistory(new ArrayList<>(history));
        }
    }

    /**
     * Clear the board history and instruct all clients to clear their view.
     */
    @Override
    public synchronized void clearBoard() throws RemoteException {
        history.clear();
        userUndoStacks.clear();
        userRedoStacks.clear();
        for (CanvasClientInterface c : clients.values()) {
            c.receiveFullHistory(new ArrayList<>(history));
        }
    }

    /**
     * Handle a join request by queueing it and notifying the manager.
     */
    @Override
    public synchronized void requestToJoin(String clientName, CanvasClientInterface clientStub)
            throws RemoteException {
        if (clients.containsKey(clientName) || pending.containsKey(clientName)) {
//            System.out.println("Name already in client pending list, try later");
            String input = JOptionPane.showInputDialog("Name already in client pending list, try later");
            clientStub.notifyJoinDecision(false);
            return;
        }
        pending.put(clientName, clientStub);
        CanvasClientInterface adminStub = clients.get(adminName);
        if (adminStub != null) {
            adminStub.notifyJoinRequest(clientName);
        }
    }

    /**
     * Manager approves a pending join; register user and send history.
     */
    @Override
    public synchronized void approveJoin(String clientName) throws RemoteException {
        CanvasClientInterface stub = pending.remove(clientName);
        if (stub != null) {
            clients.put(clientName, stub);
            broadcastUserList();
            stub.receiveFullHistory(new ArrayList<>(history));
            stub.notifyJoinDecision(true);
        }
    }

    /**
     * Manager rejects a pending join request.
     */
    @Override
    public synchronized void rejectJoin(String clientName) throws RemoteException {
        CanvasClientInterface stub = pending.remove(clientName);
        if (stub != null) {
            stub.notifyJoinDecision(false);
        }
    }

    /**
     * Return the list of currently registered users.
     */
    @Override
    public synchronized List<String> getClientList() throws RemoteException {
        return new ArrayList<>(clients.keySet());
    }

    /**
     * Notify all clients that the board is closing,
     * then shut down the server after a short delay.
     */
    @Override
    public synchronized void closeBoard() throws RemoteException {
        for (CanvasClientInterface c : clients.values()) {
            try {
                c.notifyBoardClosed();
            } catch (RemoteException ignored) {
            }
        }
        System.out.println("CanvasServer: board closed, scheduling shutdown");

        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            System.exit(0);
        }).start();
    }

    /**
     * Kick a user: notify them and remove from registration.
     */
    @Override
    public synchronized void kickUser(String username) throws RemoteException {
        CanvasClientInterface client = clients.get(username);
        if (client != null) {
            try { client.receiveKick(); } catch (RemoteException ignored) {}
            unregisterClient(username);
        }
    }

    /**
     * Launch RMI registry (or use existing) and bind the server stub.
     * Usage: java -jar server.jar <port>
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java -jar server.jar <port>");
            System.exit(1);
        }
        int port = Integer.parseInt(args[0]);

        // fail immediately if registry/port is taken
        try {
            LocateRegistry.createRegistry(port);
            System.out.println("RMI registry created on port " + port);
        } catch (RemoteException e) {
            System.err.println("Port " + port + " is already in use.");
            System.exit(1);
        }

        try {
            CanvasServer server = new CanvasServer();
            Registry reg = LocateRegistry.getRegistry(port);
            reg.rebind("CanvasServer", server);
            System.out.println("CanvasServer ready on port " + port);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

}
