package main;

public record PlayerControls(
    int upKey,
    int downKey,
    int leftKey,
    int rightKey,
    int itemModifierKey,
    int[] skillKeys) {
}
