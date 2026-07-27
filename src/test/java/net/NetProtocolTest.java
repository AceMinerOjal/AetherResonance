package net;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetProtocolTest {

  @Test
  void helloMessage() {
    assertEquals("HELLO", NetProtocol.hello());
  }

  @Test
  void assignMessageRoundTrip() {
    String msg = NetProtocol.assign(2);
    assertEquals(2, NetProtocol.parseAssignedSlot(msg));
  }

  @Test
  void assignInvalidReturnsMinusOne() {
    assertEquals(-1, NetProtocol.parseAssignedSlot("GARBAGE"));
    assertEquals(-1, NetProtocol.parseAssignedSlot("ASSIGN|abc"));
  }

  @Test
  void heartbeatDetection() {
    assertTrue(NetProtocol.isHeartbeat("HEARTBEAT"));
    assertFalse(NetProtocol.isHeartbeat("SNAP|world"));
    assertFalse(NetProtocol.isHeartbeat(""));
  }

  @Test
  void inputMessageRoundTrip() {
    NetInput input = new NetInput(true, false, true, false, false, true, false, true, false);
    String msg = NetProtocol.input(3, input);
    NetProtocol.ParsedInputMessage parsed = NetProtocol.parseInputMessage(msg);

    assertNotNull(parsed);
    assertEquals(3, parsed.slot());
    assertTrue(parsed.input().up());
    assertFalse(parsed.input().down());
    assertTrue(parsed.input().left());
    assertFalse(parsed.input().right());
    assertFalse(parsed.input().item());
    assertTrue(parsed.input().skill1());
    assertFalse(parsed.input().skill2());
    assertTrue(parsed.input().skill3());
    assertFalse(parsed.input().skill4());
  }

  @Test
  void inputInvalidFormatReturnsNull() {
    assertNull(NetProtocol.parseInputMessage("GARBAGE"));
    assertNull(NetProtocol.parseInputMessage("INPUT|abc|1|0|1|0|0|1|0|1|0"));
  }

  @Test
  void snapshotRoundTrip() {
    NetSnapshot snap = new NetSnapshot("world",
        List.of(new NetPlayerState(0, "mage", 10.5, 20.0, 32, 32, "DOWN", "WALK", 2)),
        List.of());
    String msg = NetProtocol.snapshot(snap);
    NetSnapshot parsed = NetProtocol.parseSnapshot(msg);

    assertNotNull(parsed);
    assertEquals("world", parsed.mapId());
    assertEquals(1, parsed.players().size());
    assertEquals(0, parsed.players().get(0).slot());
    assertEquals("mage", parsed.players().get(0).appearanceId());
    assertEquals(10.5, parsed.players().get(0).x(), 0.001);
  }

  @Test
  void snapshotWithEnemies() {
    NetSnapshot snap = new NetSnapshot("cave",
        List.of(),
        List.of(new NetEnemyState("enemy-3", 50.0, 60.0, 32, 32, "UP", "IDLE", 0)));
    String msg = NetProtocol.snapshot(snap);
    NetSnapshot parsed = NetProtocol.parseSnapshot(msg);

    assertNotNull(parsed);
    assertEquals(1, parsed.enemies().size());
    assertEquals("enemy-3", parsed.enemies().get(0).appearanceId());
  }

  @Test
  void snapshotInvalidReturnsNull() {
    assertNull(NetProtocol.parseSnapshot("GARBAGE"));
  }
}
