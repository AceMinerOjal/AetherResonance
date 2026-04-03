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
  private final NetworkConfig config;
  private final AtomicBoolean running = new AtomicBoolean(true);

  // My assigned slot (set by host or self-assigned for host)
  private volatile int mySlot = -1;

  // Inputs received from other peers, keyed by their slot
  private final Map<Integer, NetInput> remoteInputs = new ConcurrentHashMap<>();

  // Latest full snapshot received from the host
  private volatile NetSnapshot latestSnapshot = new NetSnapshot("", List.of());

  // Host-side: all connected clients
  private final CopyOnWriteArrayList<HostPeer> hostPeers = new CopyOnWriteArrayList<>();
  private ServerSocket serverSocket;
  private Socket hostSocket;
  private PrintWriter hostOut;

  public NetworkSession(NetworkConfig config) {
    this.config = config;
    if (config.mode().isHost()) {
      startHost();
    } else if (config.mode().isPeer()) {
      startPeer();
    }
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

  /**
   * Send my input to the host.
   */
  public void sendInput(NetInput input) {
    if (input == null || mySlot < 0) {
      return;
    }
    String msg = NetProtocol.input(mySlot, input);
    PrintWriter out = hostOut;
    if (out != null) {
      out.println(msg);
    }
  }

  /**
   * Publish a full snapshot to all connected clients.
   */
  public void publishSnapshot(NetSnapshot snapshot) {
    if (snapshot == null || !config.mode().isHost()) {
      return;
    }
    String msg = NetProtocol.snapshot(snapshot);
    for (HostPeer hp : hostPeers) {
      hp.out.println(msg);
    }
  }

  public List<Integer> connectedSlots() {
    List<Integer> slots = new ArrayList<>();
    for (HostPeer hp : hostPeers) {
      if (hp.slot > 0) {
        slots.add(hp.slot);
      }
    }
    return slots;
  }

  // =========================================================================
  // Host logic
  // =========================================================================

  private void startHost() {
    try {
      serverSocket = new ServerSocket(config.port());
      mySlot = 0; // host always gets slot 0
      Thread accept = new Thread(this::hostAcceptLoop, "P2PHostAccept");
      accept.setDaemon(true);
      accept.start();
      System.out.println("[P2P] Host listening on port " + config.port() + " (slot 0)");
    } catch (IOException e) {
      throw new IllegalStateException("Failed to start P2P host on port " + config.port(), e);
    }
  }

  private void hostAcceptLoop() {
    while (running.get()) {
      try {
        Socket socket = serverSocket.accept();
        HostPeer peer = new HostPeer(socket);
        hostPeers.add(peer);
        Thread t = new Thread(() -> hostPeerLoop(peer), "P2PHostPeerLoop");
        t.setDaemon(true);
        t.start();
      } catch (IOException ignored) {
      }
    }
  }

  private void hostPeerLoop(HostPeer peer) {
    try {
      String line;
      while (running.get() && (line = peer.in.readLine()) != null) {
        if ("HELLO".equals(line)) {
          if (peer.slot < 0) {
            peer.slot = assignSlot();
            if (peer.slot > 0) {
              peer.out.println(NetProtocol.assign(peer.slot));
            }
          }
          continue;
        }
        if (line.startsWith("INPUT|")) {
          NetProtocol.ParsedInputMessage parsed = NetProtocol.parseInputMessage(line);
          if (parsed != null && parsed.slot() == peer.slot) {
            remoteInputs.put(parsed.slot(), parsed.input());
          }
        }
      }
    } catch (IOException ignored) {
    } finally {
      hostPeers.remove(peer);
      if (peer.slot > 0) {
        remoteInputs.remove(peer.slot);
      }
      closeQuietly(peer.socket);
    }
  }

  private int assignSlot() {
    boolean[] used = new boolean[4]; // slots 0-3, 0 is host
    used[0] = true;
    for (HostPeer peer : hostPeers) {
      if (peer.slot >= 0 && peer.slot < used.length) {
        used[peer.slot] = true;
      }
    }
    for (int i = 1; i < used.length; i++) {
      if (!used[i]) {
        return i;
      }
    }
    return -1;
  }

  // =========================================================================
  // Peer logic
  // =========================================================================

  private void startPeer() {
    try {
      hostSocket = new Socket(config.host(), config.port());
      hostOut = new PrintWriter(hostSocket.getOutputStream(), true);
      BufferedReader hostIn = new BufferedReader(
          new InputStreamReader(hostSocket.getInputStream(), StandardCharsets.UTF_8));

      hostOut.println(NetProtocol.hello());

      Thread t = new Thread(() -> peerHostLoop(hostSocket, hostIn), "P2PClientHostLoop");
      t.setDaemon(true);
      t.start();
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to connect to P2P host " + config.host() + ":" + config.port(), e);
    }
  }

  private void peerHostLoop(Socket hostSocket, BufferedReader in) {
    try {
      String line;
      while (running.get() && (line = in.readLine()) != null) {
        handlePeerMessage(line);
      }
    } catch (IOException ignored) {
    } finally {
      closeQuietly(hostSocket);
    }
  }

  private void handlePeerMessage(String line) {
    if (line.startsWith("ASSIGN|")) {
      mySlot = NetProtocol.parseAssignedSlot(line);
      System.out.println("[P2P] Assigned slot " + mySlot);
    } else if (line.startsWith("SNAP|")) {
      NetSnapshot snapshot = NetProtocol.parseSnapshot(line);
      if (snapshot != null) {
        latestSnapshot = snapshot;
      }
    } else if (NetProtocol.isHeartbeat(line)) {
      // ignore
    }
  }

  @Override
  public void close() {
    running.set(false);
    closeQuietly(serverSocket);
    closeQuietly(hostSocket);
    for (HostPeer hp : new ArrayList<>(hostPeers)) {
      closeQuietly(hp.socket);
    }
    hostPeers.clear();
  }

  private static void closeQuietly(AutoCloseable c) {
    if (c == null) return;
    try {
      c.close();
    } catch (Exception ignored) {
    }
  }

  // =========================================================================
  // Inner classes
  // =========================================================================

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
}
