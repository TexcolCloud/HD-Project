package cn.dormlink;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
class FileService {
  final Db db;
  final AuthService auth;
  final BusinessSupport ops;
  final Path root;

  FileService(Db db, AuthService auth, BusinessSupport ops, @Value("${app.files}") String path)
      throws IOException {
    this.db = db;
    this.auth = auth;
    this.ops = ops;
    root = Path.of(path).toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  @Transactional
  public Object upload(MultipartFile file, String usage) throws IOException {
    if (!List.of("REPORT", "RESULT", "INSPECTION").contains(usage))
      throw ApiException.bad("图片用途无效");
    if (usage.equals("REPORT")) auth.current().requireStudent();
    else auth.current().requireManager();
    if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024)
      throw new ApiException(413, "FILE_TOO_LARGE", "图片不能为空且最大10MB");
    byte[] original = file.getBytes();
    BufferedImage source;
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext())
        throw new ApiException(415, "FILE_TYPE_NOT_SUPPORTED", "仅支持真实JPG、PNG、WebP图片");
      var reader = readers.next();
      try {
        reader.setInput(stream);
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!List.of("jpeg", "jpg", "png", "webp").contains(format))
          throw new ApiException(415, "FILE_TYPE_NOT_SUPPORTED", "仅支持JPG、PNG、WebP图片");
        long w = reader.getWidth(0), h = reader.getHeight(0);
        if (w * h > 20_000_000L || w < 1 || h < 1) throw ApiException.bad("图片像素过大，请压缩到2000万像素以内");
        source = reader.read(0);
      } finally {
        reader.dispose();
      }
    }
    double scale = Math.min(1, 2048.0 / Math.max(source.getWidth(), source.getHeight()));
    var cleaned =
        new BufferedImage(
            Math.max(1, (int) (source.getWidth() * scale)),
            Math.max(1, (int) (source.getHeight() * scale)),
            BufferedImage.TYPE_INT_RGB);
    var g = cleaned.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, cleaned.getWidth(), cleaned.getHeight());
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(source, 0, 0, cleaned.getWidth(), cleaned.getHeight(), null);
    g.dispose();
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(cleaned, "jpg", bytes);
    String key = UUID.randomUUID() + ".jpg";
    Path target = root.resolve(key);
    Files.write(target, bytes.toByteArray(), StandardOpenOption.CREATE_NEW);
    String name =
        Objects.toString(file.getOriginalFilename(), "image").replaceAll("[\\\\/\\p{Cntrl}]", "_");
    if (name.length() > 150) name = name.substring(name.length() - 150);
    String id;
    try {
      id =
          db.insert(
              "INSERT INTO"
                  + " file_asset(owner_user_id,storage_key,original_name,mime,size_bytes,usage_type,created_at)"
                  + " VALUES(?,?,?,?,?,?,?)",
              auth.current().id(),
              key,
              name,
              "image/jpeg",
              bytes.size(),
              usage,
              Timestamp.from(Instant.now()));
    } catch (RuntimeException e) {
      Files.deleteIfExists(target);
      throw e;
    }
    return describe(db.one("SELECT * FROM file_asset WHERE id=?", id));
  }

  Map<String, Object> describe(Map<String, Object> f) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String k : List.of("id", "originalName", "mime", "sizeBytes")) out.put(k, f.get(k));
    out.put("usage", f.get("usageType"));
    out.put("url", "/api/v1/files/" + f.get("id") + "/content");
    out.put("thumbnailUrl", "/api/v1/files/" + f.get("id") + "/thumbnail");
    return out;
  }

  List<Map<String, Object>> assets(String type, String id, String usage) {
    return db
        .rows(
            "SELECT * FROM file_asset WHERE business_type=? AND business_id=? AND usage_type=? AND"
                + " state='BOUND' ORDER BY id",
            type,
            id,
            usage)
        .stream()
        .map(this::describe)
        .toList();
  }

  void bind(List<String> ids, String type, String businessId, String usage, String eventId) {
    long existing =
        db.count(
            "SELECT COUNT(*) FROM file_asset WHERE business_type=? AND business_id=? AND"
                + " usage_type=? AND state='BOUND'",
            type,
            businessId,
            usage);
    long additions = 0;
    for (String id : ids) {
      var f = db.one("SELECT * FROM file_asset WHERE id=? FOR UPDATE", id);
      if ("BOUND".equals(f.get("state"))
          && type.equals(f.get("businessType"))
          && businessId.equals(f.get("businessId"))
          && usage.equals(f.get("usageType"))) continue;
      if (!auth.current().id().equals(f.get("ownerUserId"))
          || !"TEMP".equals(f.get("state"))
          || !usage.equals(f.get("usageType"))) throw ApiException.missing();
      additions++;
      if (existing + additions > 6) throw ApiException.bad("每组图片最多6张");
      db.update(
          "UPDATE file_asset SET business_type=?,business_id=?,repair_event_id=?,state='BOUND'"
              + " WHERE id=?",
          type,
          businessId,
          eventId,
          id);
    }
  }

  Map<String, Object> readable(String id) {
    var a = auth.current();
    var f = db.one("SELECT * FROM file_asset WHERE id=? AND state<>'DELETED'", id);
    if ("TEMP".equals(f.get("state"))) {
      if (!a.id().equals(f.get("ownerUserId"))) throw ApiException.missing();
      return f;
    }
    if ("REPAIR".equals(f.get("businessType"))) {
      var order =
          db.one(
              "SELECT building_id,reporter_student_id FROM repair_order WHERE id=?",
              f.get("businessId"));
      if (a.manager()) ops.building(order.get("buildingId"));
      else if (!Objects.equals(a.studentId(), order.get("reporterStudentId")))
        throw ApiException.missing();
    } else if ("HYGIENE".equals(f.get("businessType"))) {
      var h =
          db.one(
              "SELECT * FROM hygiene_inspection WHERE id=? AND voided_at IS NULL",
              f.get("businessId"));
      if (a.manager()) ops.building(h.get("buildingId"));
      else if (!hygieneVisible(h, a.studentId())) throw ApiException.missing();
    } else throw ApiException.missing();
    return f;
  }

  boolean hygieneVisible(Map<String, Object> h, String studentId) {
    var time = Timestamp.from(Instant.parse(h.get("inspectedAt").toString()));
    return db.count(
            "SELECT COUNT(*) FROM residence WHERE student_id=? AND room_id=? AND checked_in_at<=?"
                + " AND (checked_out_at IS NULL OR checked_out_at>?)",
            studentId,
            h.get("roomId"),
            time,
            time)
        > 0;
  }

  byte[] content(String id) throws IOException {
    var f = readable(id);
    Path p = root.resolve(f.get("storageKey").toString()).normalize();
    if (!p.startsWith(root) || !Files.isRegularFile(p)) throw ApiException.missing();
    return Files.readAllBytes(p);
  }

  @Transactional
  public Object delete(String id) {
    var f = db.one("SELECT * FROM file_asset WHERE id=? FOR UPDATE", id);
    if (!auth.current().id().equals(f.get("ownerUserId")) || !"TEMP".equals(f.get("state")))
      throw ApiException.missing();
    db.update("UPDATE file_asset SET state='DELETED' WHERE id=?", id);
    return Map.of("deleted", true);
  }

  @Scheduled(fixedDelay = 3600000)
  public void cleanup() {
    var cutoff = Timestamp.from(Instant.now().minusSeconds(86400));
    for (var f :
        db.rows(
            "SELECT * FROM file_asset WHERE (state='TEMP' AND created_at<?) OR state='DELETED'",
            cutoff)) {
      int changed =
          db.update(
              "UPDATE file_asset SET state='DELETED' WHERE id=? AND state<>'BOUND'", f.get("id"));
      if (changed > 0)
        try {
          Files.deleteIfExists(root.resolve(f.get("storageKey").toString()));
        } catch (IOException ignored) {
        }
    }
    db.update("DELETE FROM idempotency_record WHERE created_at<?", cutoff);
  }
}
