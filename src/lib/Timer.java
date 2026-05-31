package lib;

public abstract class Timer {
  private double duration;
  private double timeLeft;
  private boolean active;

  public Timer(double duration) {
    this.duration = duration;
    this.timeLeft = 0;
    this.active = false;
  }

  public void start() {
    this.timeLeft = duration;
    this.active = true;
  }

  public void update(double dt) {
    if (!active)
      return;

    timeLeft -= dt;
    if (timeLeft <= 0) {
      timeLeft = 0;
      active = false;
      onFinish();
    }
  }

  public abstract void onFinish();

  public boolean isActive() {
    return active;
  }
}
