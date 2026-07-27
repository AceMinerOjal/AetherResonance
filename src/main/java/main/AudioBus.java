package main;

import entity.player.Player;

public interface AudioBus {
  void playSound(String name, SoundCategory category, float x, float y, float z, boolean loop);
  void playSound(String name, SoundCategory category);
  void playMusic(String name, boolean loop, boolean fadeIn);
  void stopMusic(boolean fadeOut);
  void setMasterVolume(float volume);
  void setSfxVolume(float volume);
  void setMusicVolume(float volume);
  void setListenerData(float x, float y, float z);
}
