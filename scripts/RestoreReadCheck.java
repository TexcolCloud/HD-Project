import java.io.InputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.Objects;
import javax.imageio.ImageIO;

/** Read recovery rows with the application role and decode the paired images. */
public class RestoreReadCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].matches("dormlink_recovery_[a-z0-9_]+_test")) {
            throw new IllegalArgumentException("Use an isolated recovery database and its uploads directory");
        }
        String database = args[0];
        Path root = Path.of(args[1]).toRealPath();
        String url = "jdbc:opengauss://" + System.getenv("OG_HOST") + ":" + System.getenv("OG_PORT")
                + "/" + database + "?stringtype=unspecified&use_boolean=true&currentSchema=public";
        try (var c = DriverManager.getConnection(url, System.getenv("OG_USER"), System.getenv("OG_PASSWORD"))) {
            long orders;
            try (var s = c.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM repair_order")) {
                rows.next();
                orders = rows.getLong(1);
            }
            int decoded = 0;
            try (var s = c.createStatement(); var rows = s.executeQuery(
                    "SELECT f.storage_key,f.size_bytes FROM file_asset f LEFT JOIN repair_order r ON r.id=f.business_id "
                    + "WHERE f.state='BOUND' AND f.business_type='REPAIR' AND r.id IS NOT NULL ORDER BY f.id")) {
                while (rows.next()) {
                    Path path = root.resolve(rows.getString(1)).normalize();
                    if (!path.getParent().equals(root) || Files.isSymbolicLink(path)
                            || Files.size(path) != rows.getLong(2)) {
                        throw new IllegalStateException("Recovered attachment is not accessible");
                    }
                    try (InputStream stream = Files.newInputStream(path)) {
                        Objects.requireNonNull(ImageIO.read(stream), "Recovered image cannot be decoded");
                    }
                    decoded++;
                }
            }
            if (orders < 1 || decoded < 1) {
                throw new IllegalStateException("Recovery proof requires a real order and a bound image");
            }
            System.out.printf("{\"applicationRoleCanRead\":true,\"repairCount\":%d,\"boundRepairImagesDecoded\":%d}%n", orders, decoded);
        }
    }
}
