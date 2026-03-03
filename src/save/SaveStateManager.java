package save;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class SaveStateManager {
  private final Path quicksavePath;

  public SaveStateManager() {
    this(Paths.get("saves", "quicksave.properties"));
  }

  public SaveStateManager(Path quicksavePath) {
    this.quicksavePath = quicksavePath;
  }

  public void saveQuick(SaveState state) {
    Properties props = new Properties();
    props.setProperty("mapId", state.mapId());
    props.setProperty("playerCount", Integer.toString(state.players().size()));
    for (int i = 0; i < state.players().size(); i++) {
      PlayerSaveState p = state.players().get(i);
      String prefix = "player." + i + ".";
      props.setProperty(prefix + "className", p.playerClassName());
      props.setProperty(prefix + "signatureElement", p.signatureElement());
      props.setProperty(prefix + "statusEffectType", p.statusEffectType());
      props.setProperty(prefix + "x", Double.toString(p.x()));
      props.setProperty(prefix + "y", Double.toString(p.y()));
      props.setProperty(prefix + "hp", Double.toString(p.hp()));
      props.setProperty(prefix + "mana", Double.toString(p.mana()));
      props.setProperty(prefix + "ap", Double.toString(p.ap()));
      props.setProperty(prefix + "defence", Double.toString(p.defence()));
      props.setProperty(prefix + "level", Integer.toString(p.level()));
      props.setProperty(prefix + "exp", Integer.toString(p.exp()));
    }

    try {
      Path parent = quicksavePath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (OutputStream out = Files.newOutputStream(quicksavePath)) {
        props.store(out, "Legend Java Quicksave");
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to save quicksave to " + quicksavePath, e);
    }
  }

  public boolean hasQuickSave() {
    return Files.exists(quicksavePath);
  }

  public SaveState loadQuick() {
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(quicksavePath)) {
      props.load(in);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load quicksave from " + quicksavePath, e);
    }

    int playerCount = parseInt(props, "playerCount");
    List<PlayerSaveState> players = new ArrayList<>(playerCount);
    for (int i = 0; i < playerCount; i++) {
      String prefix = "player." + i + ".";
      players.add(new PlayerSaveState(
          getRequired(props, prefix + "className"),
          props.getProperty(prefix + "signatureElement", "FIRE"),
          props.getProperty(prefix + "statusEffectType", "BURN"),
          parseDouble(props, prefix + "x"),
          parseDouble(props, prefix + "y"),
          parseDouble(props, prefix + "hp"),
          parseDouble(props, prefix + "mana"),
          parseDouble(props, prefix + "ap"),
          parseDouble(props, prefix + "defence"),
          parseInt(props, prefix + "level"),
          parseInt(props, prefix + "exp")));
    }

    return new SaveState(
        props.getProperty("mapId", ""),
        players);
  }

  private String getRequired(Properties props, String key) {
    String value = props.getProperty(key);
    if (value == null) {
      throw new IllegalStateException("Missing required save field: " + key);
    }
    return value;
  }

  private double parseDouble(Properties props, String key) {
    String value = getRequired(props, key);
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Invalid save field " + key + ": " + value, e);
    }
  }

  private int parseInt(Properties props, String key) {
    String value = getRequired(props, key);
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Invalid save field " + key + ": " + value, e);
    }
  }
}
