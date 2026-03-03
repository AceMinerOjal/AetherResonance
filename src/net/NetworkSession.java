package net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class NetworkSession implements AutoCloseable {
  private final NetworkConfig config;
  private final AtomicBoolean running = new AtomicBoolean(true);

  private final Map<Integer, NetInput> hostSlotInputs = new ConcurrentHashMap<>();
  private final Map<SocketAddress, Integer> udpClientSlots = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<SocketAddress> udpClients = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<TcpClientPeer> tcpClients = new CopyOnWriteArrayList<>();
  private final AtomicReference<NetSnapshot> latestSnapshot = new AtomicReference<>(new NetSnapshot("", List.of()));

  private volatile int assignedSlot = -1;

  private DatagramSocket udpSocket;
  private ServerSocket tcpServer;
  private Socket tcpClient;
  private PrintWriter tcpClientOut;

  public NetworkSession(NetworkConfig config) {
    this.config = config;
    if (config.mode().isHost()) {
      startHost();
    } else if (config.mode().isClient()) {
      startClient();
    }
  }

  public Map<Integer, NetInput> hostInputs() {
    return hostSlotInputs;
  }

  public NetSnapshot latestSnapshot() {
    return latestSnapshot.get();
  }

  public void sendInput(NetInput input) {
    if (!config.mode().isClient() || input == null) {
      return;
    }
    if (assignedSlot < 0) {
      if (config.mode().isLan()) {
        sendUdp(NetProtocol.hello());
      } else {
        PrintWriter out = tcpClientOut;
        if (out != null) {
          out.println(NetProtocol.hello());
        }
      }
    }
    String payload = NetProtocol.input(input);
    if (config.mode().isLan()) {
      sendUdp(payload);
    } else {
      PrintWriter out = tcpClientOut;
      if (out != null) {
        out.println(payload);
      }
    }
  }

  public void publishSnapshot(NetSnapshot snapshot) {
    if (!config.mode().isHost() || snapshot == null) {
      return;
    }
    String msg = NetProtocol.snapshot(snapshot);
    if (config.mode().isLan()) {
      byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
      for (SocketAddress addr : udpClients) {
        try {
          udpSocket.send(new DatagramPacket(bytes, bytes.length, ((InetSocketAddress) addr).getAddress(),
              ((InetSocketAddress) addr).getPort()));
        } catch (IOException ignored) {
        }
      }
    } else {
      for (TcpClientPeer peer : tcpClients) {
        peer.out.println(msg);
      }
    }
  }

  private void startHost() {
    if (config.mode().isLan()) {
      startLanHost();
    } else {
      startTcpHost();
    }
  }

  private void startClient() {
    if (config.mode().isLan()) {
      startLanClient();
    } else {
      startTcpClient();
    }
  }

  private void startLanHost() {
    try {
      udpSocket = new DatagramSocket(config.port());
      Thread t = new Thread(this::lanHostLoop, "LanHostLoop");
      t.setDaemon(true);
      t.start();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to start LAN host on port " + config.port(), e);
    }
  }

  private void lanHostLoop() {
    byte[] buf = new byte[2048];
    while (running.get()) {
      try {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        udpSocket.receive(packet);
        String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
        InetSocketAddress from = new InetSocketAddress(packet.getAddress(), packet.getPort());
        if ("HELLO".equals(msg)) {
          int slot = udpClientSlots.computeIfAbsent(from, key -> assignRemoteSlot());
          if (slot > 0) {
            if (!udpClients.contains(from)) {
              udpClients.add(from);
            }
            byte[] reply = NetProtocol.assign(slot).getBytes(StandardCharsets.UTF_8);
            udpSocket.send(new DatagramPacket(reply, reply.length, from.getAddress(), from.getPort()));
          }
        } else {
          NetInput in = NetProtocol.parseInput(msg);
          if (in != null) {
            Integer slot = udpClientSlots.get(from);
            if (slot != null && slot > 0) {
              hostSlotInputs.put(slot, in);
            }
          }
        }
      } catch (IOException ignored) {
      }
    }
  }

  private void startLanClient() {
    try {
      udpSocket = new DatagramSocket();
      udpSocket.connect(new InetSocketAddress(config.host(), config.port()));
      Thread t = new Thread(this::lanClientLoop, "LanClientLoop");
      t.setDaemon(true);
      t.start();
      sendUdp(NetProtocol.hello());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to start LAN client " + config.host() + ":" + config.port(), e);
    }
  }

  private void lanClientLoop() {
    byte[] buf = new byte[4096];
    while (running.get()) {
      try {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        udpSocket.receive(packet);
        String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
        if (msg.startsWith("ASSIGN|")) {
          assignedSlot = NetProtocol.parseAssignedSlot(msg);
        } else if (msg.startsWith("SNAP|")) {
          NetSnapshot snapshot = NetProtocol.parseSnapshot(msg);
          if (snapshot != null) {
            latestSnapshot.set(snapshot);
          }
        }
      } catch (IOException ignored) {
      }
    }
  }

  private void sendUdp(String msg) {
    try {
      byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
      udpSocket.send(new DatagramPacket(bytes, bytes.length));
    } catch (IOException ignored) {
    }
  }

  private void startTcpHost() {
    try {
      tcpServer = new ServerSocket(config.port());
      Thread accept = new Thread(this::tcpHostAcceptLoop, "TcpHostAcceptLoop");
      accept.setDaemon(true);
      accept.start();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to start TCP host on port " + config.port(), e);
    }
  }

  private void tcpHostAcceptLoop() {
    while (running.get()) {
      try {
        Socket socket = tcpServer.accept();
        TcpClientPeer peer = new TcpClientPeer(socket);
        tcpClients.add(peer);
        Thread t = new Thread(() -> tcpHostClientLoop(peer), "TcpHostClientLoop");
        t.setDaemon(true);
        t.start();
      } catch (IOException ignored) {
      }
    }
  }

  private void tcpHostClientLoop(TcpClientPeer peer) {
    try {
      String line;
      while (running.get() && (line = peer.in.readLine()) != null) {
        if ("HELLO".equals(line)) {
          if (peer.slot < 0) {
            peer.slot = assignRemoteSlot();
            if (peer.slot > 0) {
              peer.out.println(NetProtocol.assign(peer.slot));
            }
          }
          continue;
        }
        NetInput input = NetProtocol.parseInput(line);
        if (input != null && peer.slot > 0) {
          hostSlotInputs.put(peer.slot, input);
        }
      }
    } catch (IOException ignored) {
    } finally {
      tcpClients.remove(peer);
      closeQuietly(peer.socket);
    }
  }

  private void startTcpClient() {
    try {
      tcpClient = new Socket(config.host(), config.port());
      tcpClientOut = new PrintWriter(tcpClient.getOutputStream(), true);
      Thread t = new Thread(this::tcpClientLoop, "TcpClientLoop");
      t.setDaemon(true);
      t.start();
      tcpClientOut.println(NetProtocol.hello());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to connect TCP client " + config.host() + ":" + config.port(), e);
    }
  }

  private void tcpClientLoop() {
    try (BufferedReader in = new BufferedReader(new InputStreamReader(tcpClient.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while (running.get() && (line = in.readLine()) != null) {
        if (line.startsWith("ASSIGN|")) {
          assignedSlot = NetProtocol.parseAssignedSlot(line);
        } else if (line.startsWith("SNAP|")) {
          NetSnapshot snapshot = NetProtocol.parseSnapshot(line);
          if (snapshot != null) {
            latestSnapshot.set(snapshot);
          }
        }
      }
    } catch (IOException ignored) {
    }
  }

  private int assignRemoteSlot() {
    boolean[] used = new boolean[] { false, false, false, false };
    used[0] = true;
    for (Integer slot : udpClientSlots.values()) {
      if (slot != null && slot >= 0 && slot < used.length) {
        used[slot] = true;
      }
    }
    for (TcpClientPeer peer : tcpClients) {
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

  @Override
  public void close() {
    running.set(false);
    closeQuietly(udpSocket);
    closeQuietly(tcpServer);
    closeQuietly(tcpClient);
    for (TcpClientPeer peer : new ArrayList<>(tcpClients)) {
      closeQuietly(peer.socket);
    }
    tcpClients.clear();
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

  private static final class TcpClientPeer {
    final Socket socket;
    final BufferedReader in;
    final PrintWriter out;
    volatile int slot = -1;

    TcpClientPeer(Socket socket) throws IOException {
      this.socket = socket;
      this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      this.out = new PrintWriter(socket.getOutputStream(), true);
    }
  }
}
