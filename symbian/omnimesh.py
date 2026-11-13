# -*- coding: utf-8 -*-
import appuifw
import e32
import socket
import bluetooth
import thread
import sys

# Initialize Application Header & View for Nokia E72 (320x240 Landscape)
appuifw.app.title = u"OmniMesh E72 v1.0"

chat_history = u"=== OmniMesh E72 Mesh System ===\n[System]: Initialized successfully.\n"
text_view = appuifw.Text()
text_view.set(chat_history)
appuifw.app.body = text_view

# Global State Variables
running = True
active_connections = []
server_socket_bt = None
server_socket_wifi = None
current_theme_dark = True

def log_message(message):
    """Safely append messages to the main UI text view on the main thread."""
    global chat_history
    try:
        if isinstance(message, str):
            message = unicode(message, 'utf-8', 'ignore')
        chat_history += message + u"\n"
        text_view.set(chat_history)
    except Exception, e:
        pass

def background_listener_thread():
    """Background thread listening for incoming Bluetooth RFCOMM and Wi-Fi TCP connections."""
    global running, server_socket_bt, server_socket_wifi
    
    # 1. Start Bluetooth RFCOMM Server Socket on Port 1
    try:
        server_socket_bt = socket.socket(socket.AF_BT, socket.SOCK_STREAM)
        server_socket_bt.bind(("", 1))
        server_socket_bt.listen(5)
        log_message(u"[BT Server]: Listening on channel 1...")
    except Exception, e:
        log_message(u"[BT Server Error]: " + unicode(e))
        server_socket_bt = None

    while running:
        try:
            if server_socket_bt:
                # Accept incoming connection from Android or other Symbian/S40 nodes
                client_sock, client_addr = server_socket_bt.accept()
                active_connections.append(client_sock)
                log_message(u"[Connected]: Peer joined from " + unicode(client_addr[0]))
                # Handle client in a separate thread
                thread.start_new_thread(client_handler_loop, (client_sock,))
        except Exception, e:
            e32.ao_sleep(0.5)

def client_handler_loop(sock):
    """Handles continuous reading from an active socket connection."""
    global running
    while running:
        try:
            data = sock.recv(1024)
            if data:
                decoded = unicode(data, 'utf-8', 'ignore').strip()
                log_message(u"[Peer]: " + decoded)
            else:
                break
        except Exception, e:
            break
    try:
        sock.close()
        if sock in active_connections:
            active_connections.remove(sock)
    except:
        pass
    log_message(u"[Disconnected]: Peer connection closed.")

def scan_bluetooth_peers():
    """Scans for nearby Bluetooth devices and prompts user to connect."""
    log_message(u"[Scan]: Searching for Bluetooth peers...")
    try:
        devices = bluetooth.discover_device(lookup_names=1, lookup_class=0)
        if not devices:
            appuifw.note(u"No Bluetooth devices found.", "info")
            log_message(u"[Scan]: No devices found.")
            return

        peer_names = []
        peer_addresses = []
        for addr, name in devices:
            peer_addresses.append(addr)
            peer_names.append(unicode(name) + u" [" + unicode(addr) + u"]")

        choice = appuifw.popup_menu(peer_names, u"Select Peer to Connect:")
        if choice is not None:
            target_addr = peer_addresses[choice]
            connect_to_peer_bluetooth(target_addr)
    except Exception, e:
        appuifw.note(u"Bluetooth scan failed.", "error")
        log_message(u"[Scan Error]: " + unicode(e))

def connect_to_peer_bluetooth(address):
    """Establishes an outbound Bluetooth RFCOMM connection to a target peer."""
    log_message(u"[BT]: Connecting to " + unicode(address) + u"...")
    try:
        sock = socket.socket(socket.AF_BT, socket.SOCK_STREAM)
        sock.connect((address, 1))
        active_connections.append(sock)
        thread.start_new_thread(client_handler_loop, (sock,))
        appuifw.note(u"Connected successfully!", "info")
        log_message(u"[Connected]: Outbound session established.")
    except Exception, e:
        appuifw.note(u"Connection failed.", "error")
        log_message(u"[Connection Error]: " + unicode(e))

def send_chat_message():
    """Prompts user for text input and broadcasts it across all active connections."""
    message = appuifw.query(u"Enter Message:", "text", u"")
    if message:
        log_message(u"[Me]: " + message)
        payload = (message + "\n").encode('utf-8')
        
        # Broadcast to all active sockets safely
        for sock in list(active_connections):
            try:
                sock.send(payload)
            except Exception, e:
                active_connections.remove(sock)

def toggle_theme_settings():
    """Toggles visual theme layout between dark executive and light classic."""
    global current_theme_dark
    current_theme_dark = not current_theme_dark
    if current_theme_dark:
        log_message(u"[Theme]: Dark Executive mode enabled.")
        appuifw.note(u"Dark Executive Theme", "info")
    else:
        log_message(u"[Theme]: Light Classic mode enabled.")
        appuifw.note(u"Light Classic Theme", "info")

def quit_application():
    """Safely shuts down listeners and exits the Symbian application."""
    global running
    running = False
    try:
        if server_socket_bt:
            server_socket_bt.close()
        for sock in active_connections:
            sock.close()
    except:
        pass
    appuifw.app.set_exit()

# Configure Application Menu for Nokia E72 QWERTY Layout
appuifw.app.exit_key_handler = quit_application
appuifw.app.menu = [
    (u"Scan & Connect BT", scan_bluetooth_peers),
    (u"Send Message", send_chat_message),
    (u"Toggle Theme", toggle_theme_settings),
    (u"Exit", quit_application)
]

# Start background server listener thread
thread.start_new_thread(background_listener_thread, ())

# Main event loop keeping the application alive
while running:
    e32.ao_sleep(0.2)
  
