package cn.dormlink.media;

import cn.dormlink.identity.AuthService;
import cn.dormlink.shared.jdbc.Db;
import cn.dormlink.shared.application.BusinessSupport;

import cn.dormlink.shared.api.ApiException;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import java.util.concurrent.Semaphore;

@Service
public class FileService {
  private static final int CLEANUP_BATCH_SIZE = 500;
  private static final String UNBOUND_ASSET =
      "business_type IS NULL AND business_id IS NULL AND repair_event_id IS NULL";
  private long cleanupAfterId;
  final Db db;
  final AuthService auth;
  final BusinessSupport ops;
  final Path root;
  final TransactionTemplate metadata;
  final Semaphore processing = new Semaphore(2);

  FileService(Db db, AuthService auth, BusinessSupport ops, @Value("${app.files}") String path,
      PlatformTransactionManager transactions)
      throws IOException {
    this.db = db;
    this.auth = auth;
    this.ops = ops;
    metadata = new TransactionTemplate(transactions);
    root = Path.of(path).toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  public Object upload(MultipartFile file, String usage) throws IOException {
    if (!processing.tryAcquire()) {
      throw new ApiException(429, "FILE_PROCESSING_BUSY", "图片处理中，请稍后重试");
    }
    try {
      return processUpload(file, usage);
    } finally {
      processing.release();
    }
  }

  private Object processUpload(MultipartFile file, String usage) throws IOException {
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
    Path thumbnail = root.resolve(key + ".thumb.jpg");
    Files.write(target, bytes.toByteArray(), StandardOpenOption.CREATE_NEW);
    try {
      writeThumbnail(cleaned, thumbnail);
    } catch (IOException failure) {
      Files.deleteIfExists(target);
      throw failure;
    }
    String name =
        Objects.toString(file.getOriginalFilename(), "image").replaceAll("[\\\\/\\p{Cntrl}]", "_");
    if (name.length() > 150) name = name.substring(name.length() - 150);
    String safeName = name;
    try {
      return metadata.execute(status -> {
        String id =
          db.insert(
              "INSERT INTO"
                  + " file_asset(owner_user_id,storage_key,original_name,mime,size_bytes,usage_type,created_at)"
                  + " VALUES(?,?,?,?,?,?,?)",
              auth.current().id(),
              key,
              safeName,
              "image/jpeg",
              bytes.size(),
              usage,
              Timestamp.from(Instant.now()));
        return describe(db.one("SELECT * FROM file_asset WHERE id=?", id));
      });
    } catch (RuntimeException e) {
      Files.deleteIfExists(target);
      Files.deleteIfExists(thumbnail);
      throw e;
    }
  }

  private void writeThumbnail(BufferedImage source, Path target) throws IOException {
    double scale = Math.min(1, 360.0 / Math.max(source.getWidth(), source.getHeight()));
    var thumbnail = new BufferedImage(Math.max(1, (int) (source.getWidth() * scale)),
        Math.max(1, (int) (source.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
    var graphics = thumbnail.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.drawImage(source, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), null);
    } finally {
      graphics.dispose();
    }
    Path temporary = Files.createTempFile(root, "thumbnail-", ".tmp");
    try {
      ImageIO.write(thumbnail, "jpg", temporary.toFile());
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  Map<String, Object> describe(Map<String, Object> f) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String k : List.of("id", "originalName", "mime", "sizeBytes")) out.put(k, f.get(k));
    out.put("usage", f.get("usageType"));
    out.put("url", "/api/v1/files/" + f.get("id") + "/content");
    out.put("thumbnailUrl", "/api/v1/files/" + f.get("id") + "/thumbnail");
    return out;
  }

  public List<Map<String, Object>> assets(String type, String id, String usage) {
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

  public Map<String, List<Map<String, Object>>> assetsByBusiness(String type, List<String> ids) {
    if (ids.isEmpty()) return Map.of();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    var arguments = new ArrayList<Object>();
    arguments.add(type);
    arguments.addAll(ids);
    var grouped = new HashMap<String, List<Map<String, Object>>>();
    for (var file : db.rows("SELECT * FROM file_asset WHERE business_type=? AND business_id IN ("
        + placeholders + ") AND state='BOUND' ORDER BY id", arguments.toArray())) {
      grouped.computeIfAbsent(file.get("businessId").toString(), ignored -> new ArrayList<>()).add(describe(file));
    }
    return grouped;
  }

  public void bind(List<String> ids, String type, String businessId, String usage, String eventId) {
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

  public Map<String, Object> readable(String id) {
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

  public boolean hygieneVisible(Map<String, Object> h, String studentId) {
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

  public Resource content(String id, boolean thumbnail) throws IOException {
    var f = readable(id);
    Path p = root.resolve(f.get("storageKey").toString()).normalize();
    if (!p.startsWith(root) || !Files.isRegularFile(p)) throw ApiException.missing();
    if (thumbnail) {
      Path small = root.resolve(f.get("storageKey") + ".thumb.jpg").normalize();
      if (!Files.isRegularFile(small)) {
        if (!processing.tryAcquire()) throw new ApiException(429, "FILE_PROCESSING_BUSY", "图片处理中，请稍后重试");
        try {
          writeThumbnail(ImageIO.read(p.toFile()), small);
        } finally {
          processing.release();
        }
      }
      p = small;
    }
    return new FileSystemResource(p);
  }

  public byte[] modelContent(String id) throws IOException {
    try (var stream = content(id, false).getInputStream()) {
      return stream.readAllBytes();
    }
  }

  @Transactional
  public Object delete(String id) {
    var f = db.one("SELECT * FROM file_asset WHERE id=? FOR UPDATE", id);
    if (!auth.current().id().equals(f.get("ownerUserId")) || !"TEMP".equals(f.get("state")))
      throw ApiException.missing();
    db.update("UPDATE file_asset SET state='DELETED' WHERE id=?", id);
    return Map.of("deleted", true);
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public synchronized void cleanup() {
    var cutoff = Timestamp.from(Instant.now().minusSeconds(86400));
    var batch = expiredAssets(cutoff);
    if (batch.isEmpty() && cleanupAfterId > 0) {
      cleanupAfterId = 0;
      batch = expiredAssets(cutoff);
    }
    for (var file : batch) {
      // Advance past failed files, then retry them after completing one pass. Restarting is safe.
      cleanupAfterId = Db.number(file, "id");
      int claimed = db.update("UPDATE file_asset SET state='DELETED' WHERE id=? AND "
          + UNBOUND_ASSET + " AND ((state='TEMP' AND created_at<?) OR state='DELETED')",
          file.get("id"), cutoff);
      if (claimed != 1) {
        continue;
      }
      try {
        Files.deleteIfExists(cleanupPath(file.get("storageKey").toString()));
        Files.deleteIfExists(cleanupPath(file.get("storageKey") + ".thumb.jpg"));
        // No business table references these unbound temporary IDs. A failed deletion keeps its tombstone.
        db.update("DELETE FROM file_asset WHERE id=? AND storage_key=? AND state='DELETED' AND "
            + UNBOUND_ASSET, file.get("id"), file.get("storageKey"));
      } catch (IOException failure) {
        org.slf4j.LoggerFactory.getLogger(FileService.class)
            .warn("Temporary asset cleanup deferred: {}", file.get("id"));
      }
    }
    var keys = db.rows("SELECT id FROM idempotency_record WHERE created_at<? ORDER BY id LIMIT ?",
        cutoff, CLEANUP_BATCH_SIZE);
    if (!keys.isEmpty()) {
      var ids = keys.stream().map(key -> key.get("id")).toArray();
      String placeholders = String.join(",", Collections.nCopies(ids.length, "?"));
      db.update("DELETE FROM idempotency_record WHERE id IN (" + placeholders + ")", ids);
    }
  }

  private List<Map<String, Object>> expiredAssets(Timestamp cutoff) {
    return db.rows("SELECT id,storage_key FROM file_asset WHERE " + UNBOUND_ASSET
        + " AND ((state='TEMP' AND created_at<?) OR state='DELETED') AND id>? ORDER BY id LIMIT ?",
        cutoff, cleanupAfterId, CLEANUP_BATCH_SIZE);
  }

  private Path cleanupPath(String storageKey) throws IOException {
    Path target = root.resolve(storageKey).normalize();
    if (!target.startsWith(root) || target.equals(root)) {
      throw new IOException("Invalid asset storage path");
    }
    return target;
  }
}
