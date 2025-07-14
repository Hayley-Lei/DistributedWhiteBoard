package client;

import common.*;
import clientUI.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.rmi.*;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.net.ConnectException;
import java.util.*;
import javax.swing.DefaultListModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.JFileChooser;
import java.io.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Client entry point: handles RMI registration and launches the Swing UI.
 */
public class CanvasClient extends UnicastRemoteObject implements CanvasClientInterface {
    private final CanvasServerInterface server;
    private final String name;
    private final boolean isAdmin;
    private CanvasClientForm form;
    private List<CanvasAction> pendingHistory = null;
    private File currentBoardFile = null;
    private JFrame frame;
    private String boardName;
    private boolean isNewBoard = true;

    /**
     * Connects to the server, ensures a unique username, and either registers
     * as admin (first user) or requests to join.
     */
    public CanvasClient(String host, int port, String name) throws Exception {
        super();
        // connect to RMI registry
        Registry reg = LocateRegistry.getRegistry(host, port);
        server = (CanvasServerInterface) reg.lookup("CanvasServer");

        // fetch existing users and enforce unique username
        List<String> existing = server.getClientList();
        while (existing.contains(name)) {
            String input = JOptionPane.showInputDialog("Name already taken, enter another:");
            if (input == null) System.exit(0);
            name = input.trim();
        }
        this.name = name;

        // first user becomes admin
        isAdmin = existing.isEmpty();
        if (isAdmin) {
            InitData init = server.registerClient(name, this);
            initUI(init);
        } else {
            server.requestToJoin(name, this);
            // UI will be built after approval
        }
    }

    @Override
    public void receiveAction(CanvasAction action) throws RemoteException {
        SwingUtilities.invokeLater(() -> {
            form.getCanvasComponent().addAction(action);
            form.getCanvasComponent().repaint();
            isNewBoard = true;
        });
    }

    @Override
    public void receiveFullHistory(List<CanvasAction> history) throws RemoteException {
        SwingUtilities.invokeLater(() -> {
            if (form == null) {
                // buffer history until UI is initialized
                pendingHistory = new ArrayList<>(history);
            } else {
                // apply history immediately
                form.getCanvasComponent().setHistory(history);
                form.getCanvasComponent().repaint();
            }
        });
    }

    @Override
    public void updateUserList(List<String> users) throws RemoteException {
        SwingUtilities.invokeLater(() -> {
            if (form == null) return;
            DefaultListModel<String> model =
                    (DefaultListModel<String>) form.getUserList().getModel();
            model.clear();
            for (String u : users) {
                if (isAdmin && u.equals(name)) {
                    model.addElement(u + " (Manager)");
                } else {
                    model.addElement(u);
                }
            }
        });
    }

    @Override
    public void receiveChatMessage(String sender, String msg) throws RemoteException {
        SwingUtilities.invokeLater(() ->
                form.getChatArea().append(sender + ": " + msg + "\n")
        );
    }

    @Override
    public void notifyJoinRequest(String candidate) throws RemoteException {
        if (!isAdmin) return;
        SwingUtilities.invokeLater(() -> {
            int resp = JOptionPane.showConfirmDialog(
                    null,
                    candidate + " wants to join. Approve?",
                    "Join Request",
                    JOptionPane.YES_NO_OPTION
            );
            try {
                if (resp == JOptionPane.YES_OPTION) {
                    server.approveJoin(candidate);
                } else {
                    server.rejectJoin(candidate);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public void notifyJoinDecision(boolean approved) throws RemoteException {
        SwingUtilities.invokeLater(() -> {
            if (approved) {
                try {
                    InitData init = server.registerClient(name, this);
                    initUI(init);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                JOptionPane.showMessageDialog(null, "Your join request was rejected.");
                System.exit(0);
            }
        });
    }

    @Override
    public void receiveKick() throws RemoteException {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                    null,
                    "You have been removed by the Manager.",
                    "Removed",
                    JOptionPane.WARNING_MESSAGE
            );
            System.exit(0);
        });
    }

    /** Triggers a kick on the specified user via server RPC */
    public void kickUser(String clientName) {
        try {
            server.kickUser(clientName);
        } catch (RemoteException e) {
            JOptionPane.showMessageDialog(
                    null,
                    "Failed to remove user: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    /** Returns true if this client is the manager */
    public boolean isAdmin() {
        return isAdmin;
    }

    /** Returns this client’s username */
    public String getName() {
        return name;
    }

    /**
     * Builds and displays the main application window.
     * Adds the File menu only for the manager.
     */
    private void initUI(InitData init) {
        SwingUtilities.invokeLater(() -> {
            form = new CanvasClientForm(server, this, init);
            frame = new JFrame("Whiteboard: " + name + (isAdmin ? " (Manager)" : ""));
            frame.setContentPane(form.getRootPanel());

            if (isAdmin) {
                JMenuBar menuBar = new JMenuBar();
                JMenu fileMenu = new JMenu("File");

                JMenuItem newItem = new JMenuItem("New");
                newItem.addActionListener(e -> {
                    // 1) if there are unsaved changes, ask to save
                    if (isNewBoard) {
                        int choice = JOptionPane.showConfirmDialog(
                                frame,
                                "The current board has unsaved changes.\nSave before creating a new board?",
                                "Unsaved Changes",
                                JOptionPane.YES_NO_CANCEL_OPTION,
                                JOptionPane.WARNING_MESSAGE
                        );
                        if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
                            return;  // abort New
                        }
                        if (choice == JOptionPane.YES_OPTION) {
                            // normal Save (not Save As)
                            doSave(false);
                            // if still unsaved (user cancelled the save dialog), abort
                            if (isNewBoard) {
                                return;
                            }
                        }
                        // if NO_OPTION, fall through to discard changes
                    }
                    // 2) now it’s safe to clear for everyone
                    newBoard();
                });
                fileMenu.add(newItem);

                JMenuItem openItem = new JMenuItem("Open...");
                openItem.addActionListener(e -> doOpen());
                fileMenu.add(openItem);

                JMenuItem saveItem = new JMenuItem("Save");
                saveItem.addActionListener(e -> doSave(false));
                fileMenu.add(saveItem);

                JMenuItem saveAsItem = new JMenuItem("Save As...");
                saveAsItem.addActionListener(e -> doSave(true));
                fileMenu.add(saveAsItem);

                fileMenu.addSeparator();

                JMenuItem closeItem = new JMenuItem("Close Board");
                closeItem.addActionListener(ev ->
                        frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING))
                );
                fileMenu.add(closeItem);

                menuBar.add(fileMenu);
                frame.setJMenuBar(menuBar);
            }

            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    if (isAdmin) {
                        // 1) If there are unsaved changes, prompt to save
                        if (isNewBoard) {
                            int saveOpt = JOptionPane.showConfirmDialog(
                                    frame,
                                    "The board has not been saved yet. Save before closing?",
                                    "Unsaved Board",
                                    JOptionPane.YES_NO_CANCEL_OPTION,
                                    JOptionPane.WARNING_MESSAGE
                            );
                            if (saveOpt == JOptionPane.CANCEL_OPTION || saveOpt == JOptionPane.CLOSED_OPTION) {
                                // Cancel: do nothing, stay open
                                return;
                            }
                            if (saveOpt == JOptionPane.YES_OPTION) {
                                doSave(false);
                                // if still new (user canceled save dialog), abort close
                                if (isNewBoard) {
                                    return;
                                }
                            }
                            // if NO_OPTION, fall through to close without saving
                        }
                        // 2) Confirm final close for everyone
                        int r = JOptionPane.showConfirmDialog(
                                frame,
                                "Are you sure you want to close the board for everyone?",
                                "Confirm Close",
                                JOptionPane.YES_NO_OPTION
                        );
                        if (r == JOptionPane.YES_OPTION) {
                            try {
                                server.closeBoard();
                            } catch (RemoteException ex) {
                                ex.printStackTrace();
                            }
                            // the server.shutdown will exit
                        }
                    } else {
                        // peer just exits
                        frame.dispose();
                        try { server.unregisterClient(name); } catch (Exception ex) {}
                        System.exit(0);
                    }
                }
            });

            frame.pack();
            frame.setVisible(true);

            if (pendingHistory != null) {
                form.getCanvasComponent().setHistory(pendingHistory);
                form.getCanvasComponent().repaint();
                pendingHistory = null;
            }
        });
    }

    @Override
    public void notifyBoardClosed() throws RemoteException {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                    null,
                    "Manager has closed the board. Exiting now.",
                    "Board Closed",
                    JOptionPane.INFORMATION_MESSAGE
            );
            System.exit(0);
        });
    }

    // wrapper methods for common actions
    public void undo()   { try { server.undoLastForUser(name); }   catch (RemoteException e) { e.printStackTrace(); } }
    public void redo()   { try { server.redoLastForUser(name); }   catch (RemoteException e) { e.printStackTrace(); } }
    public void sendChat(String msg) { try { server.sendChatMessage(name, msg); } catch (RemoteException e) { e.printStackTrace(); } }

    /**
     * Clears the board on the server and locally resets file state.
     */
    public void newBoard() {
        try { server.clearBoard(); } catch (RemoteException e) { e.printStackTrace(); }
        currentBoardFile = null;
        isNewBoard = true;
        boardName = null;
        form.getCanvasComponent().setHistory(new ArrayList<>());
        form.getCanvasComponent().repaint();
    }

    /**
     * Opens a saved whiteboard file (.wb) and loads its history via the server.
     */
    public void doOpen() {
        // Prompt user to choose a .wb file
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Whiteboard Files (*.wb)", "wb"));
        int ret = chooser.showOpenDialog(form.getRootPanel());
        if (ret == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
                // Deserialize the list of drawing actions
                @SuppressWarnings("unchecked")
                List<CanvasAction> history = (List<CanvasAction>) in.readObject();
                // Send the loaded history to the server for broadcasting
                server.loadHistory(history);
                currentBoardFile = f;
                this.isNewBoard = false;
            } catch (Exception e) {
                // Show error if loading fails
                JOptionPane.showMessageDialog(
                        form.getRootPanel(),
                        "Failed to open file: " + e.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        }
    }

    /**
     * Saves the current board. If saveAs==true or no file yet, prompts for filename.
     * Supports .wb (serialized), .png, and .jpg/.jpeg exports.
     */
    public void doSave(boolean saveAs) {
        // 1) If "Save As" or never saved before, prompt—but
        //    only offer PNG/JPG when saveAs==true
        if (saveAs || currentBoardFile == null) {
            JFileChooser chooser = new JFileChooser();
            chooser.setAcceptAllFileFilterUsed(false);

            // always allow .wb
            chooser.addChoosableFileFilter(
                    new FileNameExtensionFilter("Whiteboard File (*.wb)", "wb")
            );

            // only allow images on Save As
            if (saveAs) {
                chooser.addChoosableFileFilter(
                        new FileNameExtensionFilter("PNG Image (*.png)", "png")
                );
                chooser.addChoosableFileFilter(
                        new FileNameExtensionFilter("JPEG Image (*.jpg)", "jpg","jpeg")
                );
            }

            int ret = chooser.showSaveDialog(frame);
            if (ret != JFileChooser.APPROVE_OPTION) return;

            currentBoardFile = chooser.getSelectedFile();
            String fn = currentBoardFile.getName().toLowerCase();
            // always enforce extension based on the selected FileFilter
            FileNameExtensionFilter filter =
                    (FileNameExtensionFilter) chooser.getFileFilter();
            String ext = filter.getExtensions()[0];   // e.g. "wb", "png", or "jpg"
            if (!fn.endsWith("." + ext)) {
                currentBoardFile = new File(
                        currentBoardFile.getParentFile(),
                        chooser.getSelectedFile().getName() + "." + ext
                );
            }
        }

        // 2) Actually write to disk
        String fname = currentBoardFile.getName().toLowerCase();
        try {
            if (fname.endsWith(".wb")) {
                try (ObjectOutputStream out =
                             new ObjectOutputStream(new FileOutputStream(currentBoardFile))) {
                    @SuppressWarnings("unchecked")
                    List<CanvasAction> history = form.getCanvasComponent().getHistory();
                    out.writeObject(history);
                    isNewBoard = false;  // now saved
                }
            } else if (fname.endsWith(".png")
                    || fname.endsWith(".jpg")
                    || fname.endsWith(".jpeg")) {
                BufferedImage img = form.getCanvasComponent().getCanvasImage();
                // choose "png" or "jpeg"
                String fmt = fname.endsWith(".png") ? "png" : "jpeg";
                boolean ok = ImageIO.write(img, fmt, currentBoardFile);
                if (!ok) {
                    // fallback for JPEG on some platforms
                    BufferedImage rgb = new BufferedImage(
                            img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB
                    );
                    rgb.getGraphics().drawImage(img,0,0,Color.WHITE,null);
                    ImageIO.write(rgb, fmt, currentBoardFile);
                }
                isNewBoard = false;
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    frame,
                    "Failed to save: " + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }
    /**
     * Application entry point:
     * Usage: java -jar client.jar <serverIP> <port>
     * Prompts for username, handles connection errors.
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            if (args.length != 2) {
                JOptionPane.showMessageDialog(
                        null,
                        "Usage: java -jar client.jar <serverIP> <port>",
                        "Invalid Arguments",
                        JOptionPane.ERROR_MESSAGE
                );
                System.exit(1);
            }

            String host = args[0];
            int port;
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(
                        null,
                        "Invalid port number: " + args[1],
                        "Invalid Argument",
                        JOptionPane.ERROR_MESSAGE
                );
                System.exit(1);
                return;
            }

            // Prompt for non-empty username
            String name = JOptionPane.showInputDialog("Enter your username (cannot be empty):");
            if (name == null || name.trim().isEmpty()) {
                System.exit(0);
            }
            name = name.trim();

            try {
                new CanvasClient(host, port, name);
            } catch (ConnectException e) {
                JOptionPane.showMessageDialog(
                        null,
                        "Unable to connect to server at " + host + ":" + port +
                                "\nPlease make sure the server is running and the port is correct.",
                        "Connection Failed",
                        JOptionPane.ERROR_MESSAGE
                );
                System.exit(1);
            } catch (NotBoundException e) {
                JOptionPane.showMessageDialog(
                        null,
                        "Server not bound. Please ensure the server is running.",
                        "Lookup Failed",
                        JOptionPane.ERROR_MESSAGE
                );
                System.exit(1);
            } catch (RemoteException e) {
                JOptionPane.showMessageDialog(
                        null,
                        "Communication error with server at " + host + ":" + port + ".\n" +
                                "Please check the server address, port, and your network connection.",
                        "Communication Error",
                        JOptionPane.ERROR_MESSAGE
                );
                System.exit(1);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(
                        null,
                        "Unexpected error:\n" + e,
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                );
                System.exit(1);
            }
        });
    }
}
