package entity.statusEffects;

import java.util.List;

public final class StatusEffectUtils {

  private StatusEffectUtils() {}

  public static void addWithRefresh(StatusEffect effect, List<StatusEffect> activeList, EffectTarget owner) {
    for (int i = 0; i < activeList.size(); i++) {
      StatusEffect active = activeList.get(i);
      if (active.getName().equals(effect.getName())) {
        active.onFinish();
        activeList.set(i, effect);
        effect.apply(owner);
        return;
      }
    }
    effect.apply(owner);
    activeList.add(effect);
  }
}
