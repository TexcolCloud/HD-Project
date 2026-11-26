package cn.dormlink.identity;

import org.springframework.stereotype.Component;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import javax.imageio.ImageIO;

@Component
public class CaptchaRenderer {
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Color[] INKS = {
        new Color(0x242622), new Color(0x3A5A40), new Color(0x7A4B2A), new Color(0x355263)
    };
    // Coordinate pairs on a 4 × 6 grid. A space lifts the pen for another stroke.
    private static final Map<Character, String> GLYPHS =
            Map.ofEntries(
                    Map.entry('2', "01103041420646"),
                    Map.entry('3', "001030414233234445360616"),
                    Map.entry('4', "3036 300444"),
                    Map.entry('5', "400003334445361606"),
                    Map.entry('6', "4010010516364544331304"),
                    Map.entry('7', "004016"),
                    Map.entry('8', "103041423313020110 133344453616050413"),
                    Map.entry('9', "453616 454130100102133342"),
                    Map.entry('A', "062046 1444"),
                    Map.entry('B', "06003041423303 3344453606"),
                    Map.entry('C', "4130100105163645"),
                    Map.entry('D', "06002041453606"),
                    Map.entry('E', "40000646 0333"),
                    Map.entry('F', "060040 0333"),
                    Map.entry('G', "41301001051636454323"),
                    Map.entry('H', "0006 4046 0343"),
                    Map.entry('J', "104045361605"),
                    Map.entry('K', "0006 400346"),
                    Map.entry('M', "0600234046"),
                    Map.entry('N', "06004640"),
                    Map.entry('P', "06003041423303"),
                    Map.entry('Q', "10304145463616050110 2446"),
                    Map.entry('R', "06003041423303 2346"),
                    Map.entry('S', "413010010213334445361605"),
                    Map.entry('T', "0040 2026"),
                    Map.entry('U', "000516364540"),
                    Map.entry('V', "002640"),
                    Map.entry('W', "0006234640"),
                    Map.entry('X', "0046 4006"),
                    Map.entry('Y', "002340 2326"),
                    Map.entry('Z', "00400646"));

    static String newCode() {
        StringBuilder code = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    public String render(String code) {
        var image = new BufferedImage(160, 56, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xFAF7F0));
            g.fillRect(0, 0, 160, 56);
            g.setColor(new Color(0xB6ADA2));
            for (int i = 0; i < 2; i++) {
                g.draw(
                        new CubicCurve2D.Double(
                                0,
                                RANDOM.nextInt(56),
                                40,
                                RANDOM.nextInt(56),
                                110,
                                RANDOM.nextInt(56),
                                160,
                                RANDOM.nextInt(56)));
            }
            for (int i = 0; i < 65; i++) {
                g.fillOval(RANDOM.nextInt(160), RANDOM.nextInt(56), 2, 2);
            }
            for (int i = 0; i < code.length(); i++) {
                drawGlyph(g, code.charAt(i), i);
            }
        } finally {
            g.dispose();
        }
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void drawGlyph(Graphics2D canvas, char letter, int index) {
        var g = (Graphics2D) canvas.create();
        try {
            g.translate(13 + index * 37, 11 + RANDOM.nextInt(7) - 3);
            g.rotate(Math.toRadians(RANDOM.nextInt(29) - 14), 10, 16);
            g.setStroke(new BasicStroke(3.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(INKS[RANDOM.nextInt(INKS.length)]);
            var path = new Path2D.Double();
            for (String stroke : GLYPHS.get(letter).split(" ")) {
                path.moveTo(5 * (stroke.charAt(0) - '0'), 5 * (stroke.charAt(1) - '0'));
                for (int i = 2; i < stroke.length(); i += 2) {
                    path.lineTo(5 * (stroke.charAt(i) - '0'), 5 * (stroke.charAt(i + 1) - '0'));
                }
            }
            g.draw(path);
        } finally {
            g.dispose();
        }
    }
}
