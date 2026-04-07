package net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-authoritative network session.
 *
 * Architecture:
 * - P2P_HOST: listens on a TCP port, accepts client connections, assigns slots,
 *   receives player inputs, and publishes world snapshots.
 * - P2P_PEER: connects to the host, receives a slot assignment, sends local
 *   input upstream, and renders snapshots received from the host.
 */
public class NetworkSession implements AutoCloseable {
  private static final long DISCONNECT_TIMEOUT_MS = 5000;
  private static final int MAX_SLOTS = 4;

  private final NetworkConfig config;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Map<Integer, NetInput> remoteInputs = new ConcurrentHashMap<>();

  private volatile int mySlot = -1;
  private volatile NetSnapshot latestSnapshot = new NetSnapshot("", List.of(), List.of());
  private volatile save.PlayerSaveState pendingRestoreState;
  private volatile RestoreListener restoreListener;

  private final HostRuntime hostRuntime;
  private final PeerRuntime peerRuntime;

  @FunctionalInterface
  public interface RestoreListener {
    void onPeerRestored(int slot, save.PlayerSaveState state);
  }

  public NetworkSession(NetworkConfig config) {
    this.config = config;
    this.hostRuntime = config.mode().isHost() ? new HostRuntime() : null;
    this.peerRuntime = config.mode().isPeer() ? new PeerRuntime() : null;

    if (hostRuntime != null) {
      hostRuntime.start();
    } else if (peerRuntime != null) {
      peerRuntime.start();
    }
  }

  public void setRestoreListener(RestoreListener listener) {
    this.restoreListener = listener;
  }

  public int mySlot() {
    return mySlot;
  }

  public Map<Integer, NetInput> remoteInputs() {
    return remoteInputs;
  }

  public NetSnapshot latestSnapshot() {
    return latestSnapshot;
  }

  public boolean isConnected() {
    return peerRuntime == null || peerRuntime.isConnected();
  }

  public PeerConnectionState getConnectionState() {
    return peerRuntime == null ? PeerConnectionState.CONNECTED : peerRuntime.connectionState();
  }

  public void updateConnectionState() {
    if (peerRuntime != null) {
      peerRuntime.updateConnectionState();
    }
  }

  public synchronized void attemptReconnect() {
    if (peerRuntime != null) {
      peerRuntime.attemptReconnect();
    }
  }

  public void setRestoreState(save.PlayerSaveState state) {
    this.pendingRestoreState = state;
  }

  public void sendRestoreStateIfNeeded() {
    if (peerRuntime != null) {
      peerRuntime.sendRestoreStateIfNeeded();
    }
  }

  public void sendInput(NetInput input) {
    if (peerRuntime != null) {
      peerRuntime.sendInput(input);
    }
  }

  public void publishSnapshot(NetSnapshot snapshot) {
    if (hostRuntime != null) {
      hostRuntime.publishSnapshot(snapshot);
    }
  }

  public List<Integer> connectedSlots() {
    return hostRuntime == null ? List.of() : hostRuntime.connectedSlots();
  }

  @Override
  public void close() {
    running.set(false);
    if (hostRuntime != null) {
      hostRuntime.close();
    }
    if (peerRuntime != null) {
      peerRuntime.close();
    }
  }

  private void notifyPeerRestored(NetProtocol.ParsedRestoreMessage parsed) {
    RestoreListener listener = restoreListener;
    if (listener != null) {
      listener.onPeerRestored(parsed.slot(), parsed.state());
      System.out.println("[P2P] Peer in slot " + parsed.slot() + " restored with local state.");
    }
  }

  private void updateLatestSnapshot(NetSnapshot snapshot) {
    latestSnapshot = snapshot;
  }

  private static void closeQuietly(AutoCloseable c) {
    if (c == null) {
      return;
    }
    try {
      c.close();
    } catch (Exception ignored) {
    }
  }

  private final class HostRuntime {
    private final CopyOnWriteArrayList<HostPeer> peers = new CopyOnWriteArrayList<>();
    private ServerSocket serverSocket;

    void start() {
      try {
        serverSocket = new ServerSocket(config.port());
        mySlot = 0;
        Thread acceptThread = new Thread(this::acceptLoop, "P2PHostAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        System.out.println("[P2P] Host listening on port " + config.port() + " (slot 0)");
      } catch (IOException e) {
        throw new IllegalStateException("Failed to start P2P host on port " + config.port(), e);
      }
    }

    void publishSnapshot(NetSnapshot snapshot) {
      if (snapshot == null) {
        return;
      }
      String message = NetProtocol.snapshot(snapshot);
      for (HostPeer peer : peers) {
        peer.out.println(message);
      }
    }

    List<Integer> connectedSlots() {
      List<Integer> slots = new ArrayList<>();
      for (HostPeer peer : peers) {
        if (peer.slot > 0) {
          slots.add(peer.slot);
        }
      }
      return slots;
    }

    void close() {
      closeQuietly(serverSocket);
      for (HostPeer peer : new ArrayList<>(peers)) {
        closeQuietly(peer.socket);
      }
      peers.clear();
    }

    private void acceptLoop() {
      while (running.get()) {
        try {
          HostPeer peer = new HostPeer(serverSocket.accept());
          peers.add(peer);
          Thread peerThread = new Thread(() -> peerLoop(peer), "P2PHostPeerLoop");
          peerThread.setDaemon(true);
          peerThread.start();
        } catch (IOException ignored) {
        }
      }
    }

    private void peerLoop(HostPeer peer) {
      try {
        String line;
        while (running.get() && (line = peer.in.readLine()) != null) {
          if (handleHello(peer, line) || handleInput(peer, line) || handleRestore(peer, line)) {
            continue;
          }
        }
      } catch (IOException ignored) {
      } finally {
        peers.remove(peer);
        if (peer.slot > 0) {
          remoteInputs.remove(peer.slot);
        }
        closeQuietly(peer.socket);
      }
    }

    private boolean handleHello(HostPeer peer, String line) {
      if (!"HELLO".equals(line)) {
        return false;
      }
      if (peer.slot < 0) {
        peer.slot = assignSlot();
        if (peer.slot > 0) {
          peer.out.println(NetProtocol.assign(peer.slot));
        }
      }
      return true;
    }

    private boolean handleInput(HostPeer peer, String line) {
      if (!line.startsWith("INPUT|")) {
        return false;
      }
      NetProtocol.ParsedInputMessage parsed = NetProtocol.parseInputMessage(line);
      if (parsed != null && parsed.slot() == peer.slot) {
        remoteInputs.put(parsed.slot(), parsed.input());
      }
      return true;
    }

    private boolean handleRestore(HostPeer peer, String line) {
      if (!line.startsWith("RESTORE|")) {
        return false;
      }
      NetProtocol.ParsedRestoreMessage parsed = NetProtocol.parseRestoreMessage(line);
      if (parsed != null && parsed.slot() == peer.slot) {
        notifyPeerRestored(parsed);
      }
      return true;
    }

    private int assignSlot() {
      boolean[] used = new boolean[MAX_SLOTS];
      used[0] = true;
      for (HostPeer peer : peers) {
        if (peer.slot >= 0 && peer.slot < used.length) {
          used[peer.slot] = true;
        }
      }
      for (int slot = 1; slot < used.length; slot++) {
        if (!used[slot]) {
          return slot;
        }
      }
      return -1;
    }
  }

  private final class PeerRuntime {
    private final AtomicReference<PeerConnectionState> connectionState =
        new AtomicReference<>(PeerConnectionState.DISCONNECTED);

    private Socket hostSocket;
    private PrintWriter hostOut;
    private long lastSnapshotTime = System.currentTimeMillis();

    void start() {
      openConnection(true);
    }

    boolean isConnected() {
      return connectionState.get() == PeerConnectionState.CONNECTED;
    }

    PeerConnectionState connectionState() {
      return connectionState.get();
    }

    void updateConnectionState() {
      if (connectionState.get() != PeerConnectionState.CONNECTED) {
        return;
      }
      long timeSinceLastSnapshot = System.currentTimeMillis() - lastSnapshotTime;
      if (timeSinceLastSnapshot > DISCONNECT_TIMEOUT_MS) {
        transitionToDisconnected("Connection to host lost. Continuing in local mode.");
      }
    }

    synchronized void attemptReconnect() {
      if (connectionState.get() != PeerConnectionState.DISCONNECTED) {
        return;
      }
      openConnection(false);
    }

    void sendRestoreStateIfNeeded() {
      save.PlayerSaveState restoreState = pendingRestoreState;
      if (restoreState == null || mySlot <= 0 || hostOut == null) {
        return;
      }
      hostOut.println(NetProtocol.restore(mySlot, restoreState));
      pendingRestoreState = null;
    }

    void sendInput(NetInput input) {
      if (input == null || mySlot < 0 || !isConnected() || hostOut == null) {
        return;
      }
      hostOut.println(NetProtocol.input(mySlot, input));
    }

    void close() {
      closeQuietly(hostSocket);
    }

    private void openConnection(boolean initialConnect) {
      try {
        closeQuietly(hostSocket);
        hostSocket = new Socket(config.host(), config.port());
        hostOut = new PrintWriter(hostSocket.getOutputStream(), true);
        BufferedReader hostIn = new BufferedReader(
            new InputStreamReader(hostSocket.getInputStream(), StandardCharsets.UTF_8));

        connectionState.set(PeerConnectionState.RECONNECTING);
        hostOut.println(NetProtocol.hello());

        Thread hostThread = new Thread(() -> hostLoop(hostSocket, hostIn), "P2PClientHostLoop");
        hostThread.setDaemon(true);
        hostThread.start();

        if (!initialConnect) {
          System.out.println("[P2P] Reconnection attempt initiated.");
        }
      } catch (IOException e) {
        hostOut = null;
        connectionState.set(PeerConnectionState.DISCONNECTED);
        if (initialConnect) {
          throw new IllegalStateException(
              "Failed to connect to P2P host " + config.host() + ":" + config.port(), e);
        }
        System.out.println("[P2P] Reconnection failed: " + e.getMessage());
      }
    }

    private void hostLoop(Socket socket, BufferedReader in) {
      try {
        String line;
        while (running.get() && (line = in.readLine()) != null) {
          handleMessage(line);
        }
      } catch (IOException ignored) {
      } finally {
        closeQuietly(socket);
        if (hostSocket == socket) {
          hostSocket = null;
          hostOut = null;
          transitionToDisconnected("Host connection closed.");
        }
      }
    }

    private void handleMessage(String line) {
      if (line.startsWith("ASSIGN|")) {
        mySlot = NetProtocol.parseAssignedSlot(line);
        System.out.println("[P2P] Assigned slot " + mySlot);
        sendRestoreStateIfNeeded();
        return;
      }
      if (line.startsWith("SNAP|")) {
        NetSnapshot snapshot = NetProtocol.parseSnapshot(line);
        if (snapshot != null) {
          updateLatestSnapshot(snapshot);
          lastSnapshotTime = System.currentTimeMillis();
          transitionToConnected();
        }
        return;
      }
      if (NetProtocol.isHeartbeat(line)) {
        lastSnapshotTime = System.currentTimeMillis();
      }
    }

    private void transitionToConnected() {
      PeerConnectionState previous = connectionState.getAndSet(PeerConnectionState.CONNECTED);
      if (previous == PeerConnectionState.DISCONNECTED) {
        System.out.println("[P2P] Host connection restored.");
      }
    }

    private void transitionToDisconnected(String message) {
      PeerConnectionState previous = connectionState.getAndSet(PeerConnectionState.DISCONNECTED);
      if (previous != PeerConnectionState.DISCONNECTED) {
        System.out.println("[P2P] " + message);
      }
    }
  }

  private static final class HostPeer {
    final Socket socket;
    final BufferedReader in;
    final PrintWriter out;
    volatile int slot = -1;

    HostPeer(Socket socket) throws IOException {
      this.socket = socket;
      this.in = new BufferedReader(
          new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      this.out = new PrintWriter(socket.getOutputStream(), true);
    }
  }

  public enum PeerConnectionState {
    CONNECTED,
    DISCONNECTED,
    RECONNECTING
  }
}
