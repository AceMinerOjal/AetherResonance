package main;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Generates player spritesheets that match the runtime expectations in Entity/Player:
 * - walk sheet: IDLE rows 0-2, WALK rows 3-5
 * - action sheet: ATTACK rows 0-2, DIE rows 3-5
 * - direction rows per state: DOWN, UP, SIDE
 * - RIGHT is rendered by mirroring the SIDE row at runtime
 */
public final class SpriteSheetGenerator {
    private static final int FRAME = 32;
    private static final int WALK_FRAMES = 4;
    private static final int ACTION_FRAMES = 5;
    private static final int STATE_ROWS = 3;

    private static final int WALK_SHEET_WIDTH = FRAME * WALK_FRAMES;
    private static final int WALK_SHEET_HEIGHT = FRAME * (STATE_ROWS * 2);
    private static final int ACTION_SHEET_WIDTH = FRAME * ACTION_FRAMES;
    private static final int ACTION_SHEET_HEIGHT = FRAME * (STATE_ROWS * 2);

    private static final String OUTPUT_DIR = "res/sprites/players";
    private static final Color OUTLINE = new Color(24, 20, 26);
    private static final Color SHADOW = new Color(0, 0, 0, 56);
    private static final Color STEEL = new Color(187, 194, 205);
    private static final Color CLOTH_SHADE = new Color(255, 255, 255, 30);

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        new SpriteSheetGenerator().generateAll();
    }

    public void generateAll() throws IOException {
        File dir = new File(OUTPUT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create output directory: " + OUTPUT_DIR);
        }

        List<Style> styles = Arrays.asList(
            new Style("mage", Archetype.MAGE,
                new Color(84, 196, 255),
                new Color(42, 104, 165),
                new Color(230, 191, 96),
                new Color(245, 218, 176),
                new Color(110, 78, 60)),
            new Style("warrior", Archetype.WARRIOR,
                new Color(198, 112, 64),
                new Color(112, 68, 42),
                new Color(214, 220, 228),
                new Color(227, 191, 152),
                new Color(86, 57, 36)),
            new Style("tank", Archetype.TANK,
                new Color(108, 160, 94),
                new Color(66, 98, 58),
                new Color(160, 128, 88),
                new Color(215, 178, 136),
                new Color(148, 92, 54)),
            new Style("priest", Archetype.PRIEST,
                new Color(223, 112, 148),
                new Color(144, 64, 106),
                new Color(243, 212, 86),
                new Color(235, 208, 181),
                new Color(223, 208, 156))
        );

        for (Style style : styles) {
            generateClassSpritesheets(style);
        }

        System.out.println("All spritesheets generated in " + OUTPUT_DIR);
    }

    private void generateClassSpritesheets(Style style) throws IOException {
        BufferedImage walkSheet = newSheet(WALK_SHEET_WIDTH, WALK_SHEET_HEIGHT);
        BufferedImage actionSheet = newSheet(ACTION_SHEET_WIDTH, ACTION_SHEET_HEIGHT);

        renderWalkSheet(walkSheet, style);
        renderActionSheet(actionSheet, style);

        ImageIO.write(walkSheet, "PNG", new File(OUTPUT_DIR, style.name + "_walk.png"));
        ImageIO.write(actionSheet, "PNG", new File(OUTPUT_DIR, style.name + "_action.png"));
        System.out.println("Generated " + style.name + " spritesheets");
    }

    private BufferedImage newSheet(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        configure(g);
        g.dispose();
        return image;
    }

    private void renderWalkSheet(BufferedImage sheet, Style style) {
        for (Facing facing : Facing.values()) {
            drawFrame(sheet, 0, facing.row, style, facing, Pose.idle(facing));
            for (int frame = 0; frame < WALK_FRAMES; frame++) {
                drawFrame(sheet, frame, facing.row + STATE_ROWS, style, facing, Pose.walk(facing, frame));
            }
        }
    }

    private void renderActionSheet(BufferedImage sheet, Style style) {
        for (Facing facing : Facing.values()) {
            for (int frame = 0; frame < 3; frame++) {
                drawFrame(sheet, frame, facing.row, style, facing, Pose.attack(facing, frame, style.archetype));
            }
            for (int frame = 0; frame < ACTION_FRAMES; frame++) {
                drawFrame(sheet, frame, facing.row + STATE_ROWS, style, facing, Pose.die(facing, frame));
            }
        }
    }

    private void drawFrame(BufferedImage sheet, int col, int row, Style style, Facing facing, Pose pose) {
        int x = col * FRAME;
        int y = row * FRAME;

        Graphics2D g = sheet.createGraphics();
        configure(g);
        g.clipRect(x, y, FRAME, FRAME);
        g.translate(x, y);
        drawCharacter(g, style, facing, pose);
        g.dispose();
    }

    private void drawCharacter(Graphics2D g, Style style, Facing facing, Pose pose) {
        int baseX = 16 + pose.leanX;
        int baseY = 6 + pose.bobY + pose.fallY;

        if (pose.alpha < 1f) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pose.alpha));
        }

        drawShadow(g, baseX, pose);
        drawLegs(g, style, facing, pose, baseX, baseY);
        drawTorso(g, style, facing, pose, baseX, baseY);
        drawArms(g, style, facing, pose, baseX, baseY);
        drawHead(g, style, facing, pose, baseX, baseY);
        drawWeapon(g, style, facing, pose, baseX, baseY);
    }

    private void drawShadow(Graphics2D g, int baseX, Pose pose) {
        int width = Math.max(10, 16 - pose.fallFrame);
        int offsetY = 23 + Math.min(4, pose.fallY / 2);
        fillOval(g, baseX - width / 2, offsetY, width, 5, SHADOW);
    }

    private void drawLegs(Graphics2D g, Style style, Facing facing, Pose pose, int baseX, int baseY) {
        Color legColor = style.secondary;
        int leftX = baseX - 5 + pose.leftLegX;
        int rightX = baseX + 1 + pose.rightLegX;
        int legTop = baseY + 16 + pose.legY;

        if (pose.fallFrame >= 3) {
            drawHorizontalLimb(g, baseX - 8, baseY + 18, 8, legColor);
            drawHorizontalLimb(g, baseX, baseY + 20, 8, darken(legColor, 0.1f));
            return;
        }

        if (facing == Facing.SIDE) {
            fillRectOutlined(g, baseX - 2 + pose.rightLegX, legTop, 4, 9, legColor);
            fillRectOutlined(g, baseX - 5 + pose.leftLegX, legTop + 1, 3, 8, darken(legColor, 0.08f));
        } else {
            fillRectOutlined(g, leftX, legTop, 4, 9, legColor);
            fillRectOutlined(g, rightX, legTop, 4, 9, darken(legColor, 0.08f));
        }
    }

    private void drawTorso(Graphics2D g, Style style, Facing facing, Pose pose, int baseX, int baseY) {
        int torsoX = baseX - 7 + pose.torsoOffsetX;
        int torsoY = baseY + 7 + pose.torsoOffsetY;
        int torsoWidth = facing == Facing.SIDE ? 12 : 14;

        if (pose.fallFrame >= 3) {
            fillRectOutlined(g, baseX - 9, baseY + 12, 16, 8, style.primary);
            fillRect(g, baseX - 8, baseY + 15, 14, 2, style.accent);
            return;
        }

        fillRectOutlined(g, torsoX, torsoY, torsoWidth, 10, style.primary);
        fillRect(g, torsoX + 1, torsoY + 1, torsoWidth - 2, 2, brighten(style.primary, 0.15f));
        fillRect(g, torsoX, torsoY + 4, torsoWidth, 2, style.accent);

        if (style.archetype == Archetype.TANK) {
            fillRectOutlined(g, torsoX + 2, torsoY + 1, torsoWidth - 4, 4, darken(style.accent, 0.18f));
        }
        if (style.archetype == Archetype.PRIEST) {
            drawPriestTrim(g, torsoX, torsoY, torsoWidth, style.accent, facing);
        }
        if (style.archetype == Archetype.MAGE) {
            g.setColor(CLOTH_SHADE);
            g.drawLine(torsoX + 2, torsoY + 2, torsoX + torsoWidth - 3, torsoY + 7);
        }
    }

    private void drawArms(Graphics2D g, Style style, Facing facing, Pose pose, int baseX, int baseY) {
        if (pose.fallFrame >= 3) {
            fillRectOutlined(g, baseX - 11, baseY + 12, 4, 3, style.primary);
            fillRectOutlined(g, baseX + 7, baseY + 16, 4, 3, style.primary);
            fillRect(g, baseX - 12, baseY + 14, 2, 2, style.skin);
            fillRect(g, baseX + 10, baseY + 18, 2, 2, style.skin);
            return;
        }

        ArmPose front = pose.frontArm;
        ArmPose back = pose.backArm;

        if (facing == Facing.SIDE) {
            drawSideArm(g, style, baseX, baseY, back, false);
            drawSideArm(g, style, baseX, baseY, front, true);
            return;
        }

        drawFrontArm(g, style, baseX - 8, baseY + 8, back, false);
        drawFrontArm(g, style, baseX + 5, baseY + 8, front, true);
    }

    private void drawHead(Graphics2D g, Style style, Facing facing, Pose pose, int baseX, int baseY) {
        int headX = baseX - (facing == Facing.SIDE ? 4 : 5);
        int headY = baseY;
        int headW = facing == Facing.SIDE ? 8 : 10;

        if (pose.fallFrame >= 3) {
            fillRectOutlined(g, baseX + 8, baseY + 11, 8, 7, style.skin);
            drawHair(g, style, facing, baseX + 8, baseY + 10);
            return;
        }

        fillRectOutlined(g, headX, headY, headW, 9, style.skin);
        drawHair(g, style, facing, headX, headY);
        drawFace(g, style, facing, headX, headY, headW);
    }

    private void drawWeapon(Graphics2D g, Style style, Facing facing, Pose pose, int baseX, int baseY) {
        if (pose.weapon == WeaponPose.NONE || pose.fallFrame >= 4) {
            return;
        }

        switch (style.archetype) {
            case MAGE:
                drawStaff(g, facing, pose, baseX, baseY, style);
                break;
            case WARRIOR:
                drawSword(g, facing, pose, baseX, baseY, style);
                break;
            case TANK:
                drawShield(g, facing, pose, baseX, baseY, style);
                break;
            case PRIEST:
                drawMace(g, facing, pose, baseX, baseY, style);
                break;
            default:
                break;
        }
    }

    private void drawStaff(Graphics2D g, Facing facing, Pose pose, int baseX, int baseY, Style style) {
        int x;
        int y;
        int h;
        if (facing == Facing.UP) {
            x = baseX + 5 + pose.weaponShiftX;
            y = baseY + 1 + pose.weaponShiftY;
            h = pose.weapon == WeaponPose.ATTACK ? 12 : 15;
        } else if (facing == Facing.SIDE) {
            x = baseX + 5 + pose.weaponShiftX;
            y = baseY + 2 + pose.weaponShiftY;
            h = pose.weapon == WeaponPose.ATTACK ? 10 : 14;
        } else {
            x = baseX + 6 + pose.weaponShiftX;
            y = baseY + 3 + pose.weaponShiftY;
            h = pose.weapon == WeaponPose.ATTACK ? 10 : 14;
        }
        fillRectOutlined(g, x, y, 2, h, darken(style.secondary, 0.15f));
        fillRectOutlined(g, x - 1, y - 2, 4, 4, style.accent);
    }

    private void drawSword(Graphics2D g, Facing facing, Pose pose, int baseX, int baseY, Style style) {
        int hiltX = baseX + 6 + pose.weaponShiftX;
        int hiltY = baseY + 11 + pose.weaponShiftY;

        if (facing == Facing.SIDE) {
            fillRectOutlined(g, hiltX, hiltY, 4, 2, style.accent);
            fillRectOutlined(g, hiltX + 3, hiltY - 5, 2, 7, STEEL);
            return;
        }

        if (pose.weapon == WeaponPose.ATTACK) {
            fillRectOutlined(g, baseX + 7, baseY + 9, 7, 2, STEEL);
            fillRectOutlined(g, baseX + 5, baseY + 9, 3, 2, style.accent);
            return;
        }

        fillRectOutlined(g, hiltX, hiltY, 2, 3, style.accent);
        fillRectOutlined(g, hiltX, hiltY - 8, 2, 8, STEEL);
        fillRect(g, hiltX - 1, hiltY, 4, 1, style.accent);
    }

    private void drawShield(Graphics2D g, Facing facing, Pose pose, int baseX, int baseY, Style style) {
        int shieldX = facing == Facing.SIDE ? baseX + 4 : baseX + 6;
        int shieldY = baseY + 8 + pose.weaponShiftY;
        int shieldW = facing == Facing.SIDE ? 6 : 5;
        int shieldH = pose.weapon == WeaponPose.ATTACK ? 6 : 8;
        fillRectOutlined(g, shieldX, shieldY, shieldW, shieldH, darken(style.accent, 0.1f));
        fillRect(g, shieldX + 1, shieldY + 1, shieldW - 2, shieldH - 2, brighten(style.accent, 0.1f));
        fillRect(g, shieldX + shieldW / 2, shieldY + 1, 1, shieldH - 2, STEEL);
    }

    private void drawMace(Graphics2D g, Facing facing, Pose pose, int baseX, int baseY, Style style) {
        int shaftX = baseX + 6 + pose.weaponShiftX;
        int shaftY = baseY + 7 + pose.weaponShiftY;

        if (facing == Facing.SIDE && pose.weapon == WeaponPose.ATTACK) {
            fillRectOutlined(g, shaftX, shaftY + 4, 7, 2, darken(style.secondary, 0.15f));
            fillRectOutlined(g, shaftX + 6, shaftY + 2, 4, 5, style.accent);
            return;
        }

        fillRectOutlined(g, shaftX, shaftY, 2, 8, darken(style.secondary, 0.15f));
        fillRectOutlined(g, shaftX - 1, shaftY - 3, 4, 4, style.accent);
        fillRect(g, shaftX, shaftY - 2, 2, 2, brighten(style.accent, 0.08f));
    }

    private void drawHair(Graphics2D g, Style style, Facing facing, int headX, int headY) {
        switch (style.archetype) {
            case MAGE:
                fillRectOutlined(g, headX - 1, headY - 3, facing == Facing.SIDE ? 10 : 12, 4, style.primary);
                Polygon hat = new Polygon();
                hat.addPoint(headX + 2, headY - 3);
                hat.addPoint(headX + 5, headY - 7);
                hat.addPoint(headX + 7, headY - 3);
                g.setColor(style.primary);
                g.fillPolygon(hat);
                g.setColor(OUTLINE);
                g.drawPolygon(hat);
                fillRect(g, headX, headY + 1, facing == Facing.SIDE ? 7 : 10, 2, style.hair);
                break;
            case WARRIOR:
                fillRectOutlined(g, headX - 1, headY - 2, facing == Facing.SIDE ? 10 : 12, 5, darken(style.accent, 0.15f));
                fillRectOutlined(g, headX + 3, headY - 4, 2, 4, new Color(185, 48, 48));
                break;
            case TANK:
                fillRectOutlined(g, headX - 1, headY - 1, facing == Facing.SIDE ? 10 : 12, 6, darken(style.accent, 0.2f));
                fillRect(g, headX + 1, headY + 2, facing == Facing.SIDE ? 4 : 6, 1, new Color(64, 64, 70));
                if (facing != Facing.UP) {
                    fillRect(g, headX + 2, headY + 8, 4, 2, style.hair);
                }
                break;
            case PRIEST:
                fillRectOutlined(g, headX - 1, headY - 2, facing == Facing.SIDE ? 10 : 12, 6, style.primary);
                fillRect(g, headX + 2, headY, facing == Facing.SIDE ? 3 : 2, 5, style.accent);
                fillRect(g, headX + 1, headY + 1, facing == Facing.SIDE ? 5 : 4, 2, style.accent);
                break;
            default:
                break;
        }
    }

    private void drawFace(Graphics2D g, Style style, Facing facing, int headX, int headY, int headW) {
        if (style.archetype == Archetype.TANK && facing != Facing.UP) {
            fillRect(g, headX + 2, headY + 3, headW - 4, 1, new Color(40, 40, 48));
            return;
        }

        g.setColor(OUTLINE);
        if (facing == Facing.SIDE) {
            g.fillRect(headX + headW - 2, headY + 3, 1, 1);
        } else if (facing == Facing.UP) {
            g.fillRect(headX + 2, headY + 2, 2, 1);
            g.fillRect(headX + headW - 4, headY + 2, 2, 1);
        } else {
            g.fillRect(headX + 2, headY + 3, 2, 2);
            g.fillRect(headX + headW - 4, headY + 3, 2, 2);
        }
    }

    private void drawPriestTrim(Graphics2D g, int torsoX, int torsoY, int torsoWidth, Color accent, Facing facing) {
        int center = torsoX + torsoWidth / 2;
        fillRect(g, center, torsoY + 1, 1, 7, accent);
        if (facing != Facing.UP) {
            fillRect(g, center - 1, torsoY + 3, 3, 1, accent);
        }
    }

    private void drawFrontArm(Graphics2D g, Style style, int x, int y, ArmPose pose, boolean front) {
        Color armColor = front ? style.primary : darken(style.primary, 0.1f);
        fillRectOutlined(g, x + pose.offsetX, y + pose.offsetY, 3, 7, armColor);
        fillRect(g, x + pose.offsetX, y + pose.offsetY + 6, 2, 2, style.skin);
    }

    private void drawSideArm(Graphics2D g, Style style, int baseX, int baseY, ArmPose pose, boolean front) {
        int x = baseX + pose.offsetX;
        int y = baseY + 8 + pose.offsetY;
        int width = pose.horizontal ? 6 : 3;
        int height = pose.horizontal ? 3 : 7;
        Color armColor = front ? style.primary : darken(style.primary, 0.1f);
        fillRectOutlined(g, x, y, width, height, armColor);
        if (pose.horizontal) {
            fillRect(g, x + width - 1, y + 1, 2, 2, style.skin);
        } else {
            fillRect(g, x, y + height - 1, 2, 2, style.skin);
        }
    }

    private void drawHorizontalLimb(Graphics2D g, int x, int y, int width, Color color) {
        fillRectOutlined(g, x, y, width, 3, color);
    }

    private void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setStroke(new BasicStroke(1f));
    }

    private void fillRectOutlined(Graphics2D g, int x, int y, int width, int height, Color fill) {
        fillRect(g, x, y, width, height, fill);
        g.setColor(OUTLINE);
        g.drawRect(x, y, width - 1, height - 1);
    }

    private void fillRect(Graphics2D g, int x, int y, int width, int height, Color fill) {
        g.setColor(fill);
        g.fillRect(x, y, width, height);
    }

    private void fillOval(Graphics2D g, int x, int y, int width, int height, Color fill) {
        g.setColor(fill);
        g.fillOval(x, y, width, height);
    }

    private Color darken(Color color, float amount) {
        float scale = Math.max(0f, 1f - amount);
        return new Color(
            clamp(Math.round(color.getRed() * scale)),
            clamp(Math.round(color.getGreen() * scale)),
            clamp(Math.round(color.getBlue() * scale)),
            color.getAlpha());
    }

    private Color brighten(Color color, float amount) {
        return new Color(
            clamp(Math.round(color.getRed() + (255 - color.getRed()) * amount)),
            clamp(Math.round(color.getGreen() + (255 - color.getGreen()) * amount)),
            clamp(Math.round(color.getBlue() + (255 - color.getBlue()) * amount)),
            color.getAlpha());
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private enum Facing {
        DOWN(0),
        UP(1),
        SIDE(2);

        private final int row;

        Facing(int row) {
            this.row = row;
        }
    }

    private enum Archetype {
        MAGE,
        WARRIOR,
        TANK,
        PRIEST
    }

    private enum WeaponPose {
        NONE,
        READY,
        ATTACK
    }

    private static final class Style {
        private final String name;
        private final Archetype archetype;
        private final Color primary;
        private final Color secondary;
        private final Color accent;
        private final Color skin;
        private final Color hair;

        private Style(String name, Archetype archetype, Color primary, Color secondary,
                Color accent, Color skin, Color hair) {
            this.name = name;
            this.archetype = archetype;
            this.primary = primary;
            this.secondary = secondary;
            this.accent = accent;
            this.skin = skin;
            this.hair = hair;
        }
    }

    private static final class ArmPose {
        private final int offsetX;
        private final int offsetY;
        private final boolean horizontal;

        private ArmPose(int offsetX, int offsetY, boolean horizontal) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.horizontal = horizontal;
        }
    }

    private static final class Pose {
        private final int bobY;
        private final int leanX;
        private final int leftLegX;
        private final int rightLegX;
        private final int legY;
        private final int torsoOffsetX;
        private final int torsoOffsetY;
        private final int weaponShiftX;
        private final int weaponShiftY;
        private final int fallY;
        private final int fallFrame;
        private final float alpha;
        private final WeaponPose weapon;
        private final ArmPose frontArm;
        private final ArmPose backArm;

        private Pose(int bobY, int leanX, int leftLegX, int rightLegX, int legY,
                int torsoOffsetX, int torsoOffsetY, int weaponShiftX, int weaponShiftY,
                int fallY, int fallFrame, float alpha, WeaponPose weapon,
                ArmPose frontArm, ArmPose backArm) {
            this.bobY = bobY;
            this.leanX = leanX;
            this.leftLegX = leftLegX;
            this.rightLegX = rightLegX;
            this.legY = legY;
            this.torsoOffsetX = torsoOffsetX;
            this.torsoOffsetY = torsoOffsetY;
            this.weaponShiftX = weaponShiftX;
            this.weaponShiftY = weaponShiftY;
            this.fallY = fallY;
            this.fallFrame = fallFrame;
            this.alpha = alpha;
            this.weapon = weapon;
            this.frontArm = frontArm;
            this.backArm = backArm;
        }

        private static Pose idle(Facing facing) {
            return new Pose(
                0, 0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 1f,
                WeaponPose.READY,
                defaultFrontArm(facing),
                defaultBackArm(facing));
        }

        private static Pose walk(Facing facing, int frame) {
            int[] swing = {0, 1, 0, -1};
            int[] bob = {0, -1, 0, -1};
            return new Pose(
                bob[frame], 0,
                -swing[frame], swing[frame], 0,
                0, 0, 0, 0,
                0, 0, 1f,
                WeaponPose.READY,
                new ArmPose(frontArmX(facing) - swing[frame], frontArmY(facing), facing == Facing.SIDE),
                new ArmPose(backArmX(facing) + swing[frame], backArmY(facing), facing == Facing.SIDE));
        }

        private static Pose attack(Facing facing, int frame, Archetype archetype) {
            int lean = frame == 0 ? -1 : frame == 1 ? 2 : 0;
            int weaponShiftX = frame == 1 ? 2 : 0;
            int weaponShiftY = frame == 0 ? -2 : frame == 1 ? -1 : 0;
            WeaponPose weapon = frame == 1 ? WeaponPose.ATTACK : WeaponPose.READY;
            ArmPose front;
            ArmPose back;

            if (facing == Facing.SIDE) {
                front = new ArmPose(frame == 1 ? 4 : 2, frame == 0 ? -2 : -1, true);
                back = new ArmPose(-4, frame == 1 ? 1 : 0, false);
            } else {
                front = new ArmPose(frame == 1 ? 2 : 0, frame == 0 ? -2 : -1, false);
                back = new ArmPose(frame == 1 ? -1 : 0, frame == 2 ? 1 : 0, false);
            }

            if (archetype == Archetype.TANK && frame == 1) {
                weaponShiftY = 1;
            }

            return new Pose(
                0, lean, 0, 0, 0,
                lean / 2, 0, weaponShiftX, weaponShiftY,
                0, 0, 1f,
                weapon,
                front, back);
        }

        private static Pose die(Facing facing, int frame) {
            int fallY = frame * 3;
            float alpha = Math.max(0.28f, 1f - frame * 0.16f);

            if (frame >= 3) {
                return new Pose(
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0,
                    fallY, frame, alpha,
                    WeaponPose.NONE,
                    defaultFrontArm(facing),
                    defaultBackArm(facing));
            }

            return new Pose(
                0, frame - 1, -frame / 2, frame / 2, frame,
                frame - 1, frame, 0, frame - 1,
                fallY, frame, alpha,
                WeaponPose.READY,
                new ArmPose(frontArmX(facing) + frame, frontArmY(facing) + frame, facing == Facing.SIDE),
                new ArmPose(backArmX(facing) - frame, backArmY(facing) + frame, facing == Facing.SIDE));
        }

        private static ArmPose defaultFrontArm(Facing facing) {
            return new ArmPose(frontArmX(facing), frontArmY(facing), facing == Facing.SIDE);
        }

        private static ArmPose defaultBackArm(Facing facing) {
            return new ArmPose(backArmX(facing), backArmY(facing), facing == Facing.SIDE);
        }

        private static int frontArmX(Facing facing) {
            return facing == Facing.SIDE ? 2 : 5;
        }

        private static int frontArmY(Facing facing) {
            return facing == Facing.UP ? 7 : 8;
        }

        private static int backArmX(Facing facing) {
            return facing == Facing.SIDE ? -4 : -8;
        }

        private static int backArmY(Facing facing) {
            return facing == Facing.UP ? 7 : 8;
        }
    }
}
