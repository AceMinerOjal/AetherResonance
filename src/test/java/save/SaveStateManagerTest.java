package save;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SaveStateManagerTest {

  @TempDir
  Path tempDir;

  @Test
  void roundTripSaveAndLoad() {
    Path savePath = tempDir.resolve("test-quicksave.properties");
    SaveStateManager mgr = new SaveStateManager(savePath);

    PlayerSaveState p = new PlayerSaveState(
        0, "entity.player.classes.Mage", "FIRE", "BURN",
        100.0, 200.0, 50.0, 20.0, 15.0, 8.0, 5, 120);
    SaveState state = new SaveState("world", List.of(p));

    mgr.saveQuick(state);
    assertTrue(mgr.hasQuickSave());

    SaveState loaded = mgr.loadQuick();
    assertEquals("world", loaded.mapId());
    assertEquals(1, loaded.players().size());

    PlayerSaveState lp = loaded.players().get(0);
    assertEquals("entity.player.classes.Mage", lp.playerClassName());
    assertEquals("FIRE", lp.signatureElement());
    assertEquals(100.0, lp.x(), 0.001);
    assertEquals(200.0, lp.y(), 0.001);
    assertEquals(5, lp.level());
    assertEquals(120, lp.exp());
  }

  @Test
  void hasQuickSaveReturnsFalseWhenNoFile() {
    Path savePath = tempDir.resolve("nonexistent.properties");
    SaveStateManager mgr = new SaveStateManager(savePath);
    assertFalse(mgr.hasQuickSave());
  }

  @Test
  void saveCreatesParentDirectories() {
    Path savePath = tempDir.resolve("sub/dir/save.properties");
    SaveStateManager mgr = new SaveStateManager(savePath);

    PlayerSaveState p = new PlayerSaveState(
        0, "test.Class", "ICE", "FREEZE",
        0, 0, 10, 5, 3, 2, 1, 0);
    SaveState state = new SaveState("cave", List.of(p));
    mgr.saveQuick(state);

    assertTrue(mgr.hasQuickSave());
  }
}
