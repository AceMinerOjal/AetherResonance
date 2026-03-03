package save;

import java.util.List;

public record SaveState(
    String mapId,
    List<PlayerSaveState> players) {
}
