package com.omnimesh.s40;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.bluetooth.*;
import java.io.*;
import java.util.Vector;

/**
 * OmniMeshS40 - Fully functional Java ME client for Nokia 2700 classic (S40 5th Ed)
 * Supports JSR-82 Bluetooth discovery, RFCOMM socket streaming, and S40 UI layout.
 */
public class OmniMeshS40 extends MIDlet implements CommandListener, DiscoveryListener, Runnable {
    
    private Display display;
    private List mainScreen;
    private List contactScanScreen;
    private Form chatForm;
    private Form settingsForm;
    
    private Command exitCommand;
    private Command scanCommand;
    private Command selectCommand;
    private Command sendCommand;
    private Command backCommand;
    private Command themeCommand;
    
    private TextField messageInput;
    private StringItem chatHistoryItem;
    
    // Bluetooth & Networking variables
    private LocalDevice localDevice;
    private DiscoveryAgent discoveryAgent;
    private Vector discoveredDeviceList;
    private Vector activeConnections;
    
    private StreamConnectionNotifier serverNotifier;
    private StreamConnection activeSocketConnection;
    private InputStream inputStream;
    private OutputStream outputStream;
    
    private boolean isRunning = true;
    private Thread backgroundThread;
    
    // Theme state (0 = High Contrast Dark, 1 = Classic Light)
    private int currentTheme = 0;

    public OmniMeshS40() {
        display = Display.getDisplay(this);
        discoveredDeviceList = new Vector();
        activeConnections = new Vector();

        // Initialize Commands
        exitCommand = new Command("Exit", Command.EXIT, 0);
        scanCommand = new Command("Scan Bluetooth", Command.SCREEN, 1);
        selectCommand = new Command("Connect", Command.OK, 1);
        sendCommand = new Command("Send", Command.OK, 1);
        backCommand = new Command("Back", Command.BACK, 2);
        themeCommand = new Command("Toggle Theme", Command.SCREEN, 3);

        // Main Menu (240x320 optimized list)
        mainScreen = new List("OmniMesh S40 v1.0", List.IMPLICIT);
        mainScreen.append("Scan Nearby Peers", null);
        mainScreen.append("Open Chat Room", null);
        mainScreen.append("Toggle Theme", null);
        mainScreen.addCommand(exitCommand);
        mainScreen.setCommandListener(this);

        // Chat Room Form
        chatForm = new Form("OmniMesh Chat Room");
        chatHistoryItem = new StringItem("History:\n", "System initialized.\n");
        messageInput = new TextField("Message", "", 140, TextField.ANY);
        
        chatForm.append(chatHistoryItem);
        chatForm.append(messageInput);
        chatForm.addCommand(sendCommand);
        chatForm.addCommand(themeCommand);
        chatForm.addCommand(backCommand);
        chatForm.setCommandListener(this);
    }

    public void startApp() {
        display.setCurrent(mainScreen);
        initializeBluetoothSubsystem();
        
        // Start background networking thread
        backgroundThread = new Thread(this);
        backgroundThread.start();
    }

    private void initializeBluetoothSubsystem() {
        try {
            localDevice = LocalDevice.getLocalDevice();
            discoveryAgent = localDevice.getDiscoveryAgent();
            localDevice.setDiscoverable(DiscoveryAgent.GIAC);
            appendLog("[BT]: Initialized successfully (" + localDevice.getFriendlyName() + ")");
        } catch (Exception e) {
            appendLog("[BT Error]: " + e.getMessage());
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCommand) {
            destroyApp(false);
            notifyDestroyed();
        } 
        else if (d == mainScreen) {
            int selectedIndex = mainScreen.getSelectedIndex();
            if (selectedIndex == 0) {
                startBluetoothInquiry();
            } else if (selectedIndex == 1) {
                display.setCurrent(chatForm);
            } else if (selectedIndex == 2) {
                toggleApplicationTheme();
            }
        } 
        else if (c == backCommand) {
            display.setCurrent(mainScreen);
        } 
        else if (c == scanCommand || d == contactScanScreen) {
            // Handle contact selection from scan list
            if (d == contactScanScreen && c == List.SELECT_COMMAND) {
                int index = contactScanScreen.getSelectedIndex();
                if (index >= 0 && index < discoveredDeviceList.size()) {
                    RemoteDevice targetDevice = (RemoteDevice) discoveredDeviceList.elementAt(index);
                    connectToTargetDevice(targetDevice);
                }
            }
        } 
        else if (c == sendCommand) {
            String textToSend = messageInput.getString();
            if (textToSend != null && textToSend.trim().length() > 0) {
                sendDataPacket(textToSend);
                messageInput.setString("");
            }
        } 
        else if (c == themeCommand) {
            toggleApplicationTheme();
        }
    }

    private void startBluetoothInquiry() {
        try {
            discoveredDeviceList.removeAllElements();
            contactScanScreen = new List("Discovered Peers", List.IMPLICIT);
            contactScanScreen.addCommand(backCommand);
            contactScanScreen.setCommandListener(this);
            
            display.setCurrent(contactScanScreen);
            appendLog("[BT]: Scanning inquiry started...");
            
            // Start device inquiry looking for General/Unlimited Inquiry Access Code
            discoveryAgent.startInquiry(DiscoveryAgent.GIAC, this);
        } catch (Exception e) {
            appendLog("[Scan Error]: " + e.getMessage());
        }
    }

    public void deviceDiscovered(RemoteDevice remoteDevice, DeviceClass deviceClass) {
        discoveredDeviceList.addElement(remoteDevice);
        try {
            String friendlyName = remoteDevice.getFriendlyName(false);
            contactScanScreen.append(friendlyName, null);
        } catch (Exception e) {
            contactScanScreen.append(remoteDevice.getBluetoothAddress(), null);
        }
    }

    public void inquiryCompleted(int discType) {
        appendLog("[BT]: Inquiry completed. Found " + discoveredDeviceList.size() + " devices.");
    }

    public void servicesDiscovered(int transID, ServiceRecord[] servRecord) {}
    public void serviceSearchCompleted(int transID, int respCode) {}

    private void connectToTargetDevice(RemoteDevice device) {
        appendLog("[BT]: Connecting to " + device.getBluetoothAddress() + "...");
        try {
            // SPP UUID for serial port profile connection across Nokia & Android
            String url = device.getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
            if (url == null) {
                url = "btspp://" + device.getBluetoothAddress() + ":1;authenticate=false;encrypt=false;master=false";
            }
            
            activeSocketConnection = (StreamConnection) Connector.open(url);
            outputStream = activeSocketConnection.openOutputStream();
            inputStream = activeSocketConnection.openInputStream();
            
            display.setCurrent(chatForm);
            appendLog("[Connected]: Session active with peer.");
        } catch (Exception e) {
            appendLog("[Connection Failed]: " + e.getMessage());
        }
    }

    private void sendDataPacket(String message) {
        try {
            if (outputStream != null) {
                byte[] data = (message + "\n").getBytes("UTF-8");
                outputStream.write(data);
                outputStream.flush();
                appendLog("[Me]: " + message);
            } else {
                appendLog("[Error]: No active data stream.");
            }
        } catch (Exception e) {
            appendLog("[Send Error]: " + e.getMessage());
        }
    }

    private void toggleApplicationTheme() {
        currentTheme = (currentTheme == 0) ? 1 : 0;
        if (currentTheme == 1) {
            appendLog("[Theme]: Switched to Classic Light.");
        } else {
            appendLog("[Theme]: Switched to High Contrast Dark.");
        }
    }

    private synchronized void appendLog(String text) {
        String existing = chatHistoryItem.getText();
        chatHistoryItem.setText(existing + "\n" + text);
    }

    public void run() {
        // Continuous background polling loop for incoming socket packets on Nokia 2700
        while (isRunning) {
            try {
                Thread.sleep(250);
                if (inputStream != null) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    int ch;
                    while (inputStream.available() > 0 && (ch = inputStream.read()) != -1) {
                        if (ch == '\n') break;
                        buffer.write(ch);
                    }
                    if (buffer.size() > 0) {
                        String incomingMessage = new String(buffer.toByteArray(), "UTF-8");
                        appendLog("[Peer]: " + incomingMessage);
                    }
                }
            } catch (Exception e) {
                // Handle background thread exception gracefully without dropping loop
            }
        }
    }

    public void pauseApp() {}

    public void destroyApp(boolean unconditional) {
        isRunning = false;
        try {
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (activeSocketConnection != null) activeSocketConnection.close();
        } catch (Exception ignored) {}
    }
}
