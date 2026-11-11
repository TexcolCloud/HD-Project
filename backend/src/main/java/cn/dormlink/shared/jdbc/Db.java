package cn.dormlink.shared.jdbc;

import cn.dormlink.shared.api.ApiException;

import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
public class Db {
  final JdbcTemplate jdbc;
  final String product;

  public Db(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
      product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot determine database dialect", failure);
    }
  }

  public String chinaDate(String column) {
    if (product.contains("h2")) return "CAST(DATEADD('HOUR',8," + column + ") AS DATE)";
    if (product.contains("mysql")) return "DATE(DATE_ADD(" + column + ",INTERVAL 8 HOUR))";
    return "CAST(" + column + " + INTERVAL '8 hours' AS DATE)";
  }

  public String durationHours(String start, String end) {
    if (product.contains("h2")) return "DATEDIFF('MILLISECOND'," + start + "," + end + ")/3600000.0";
    if (product.contains("mysql")) return "TIMESTAMPDIFF(MICROSECOND," + start + "," + end + ")/3600000000.0";
    return "EXTRACT(EPOCH FROM (" + end + "-" + start + "))/3600.0";
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        ps -> bind(ps, args),
        (rs, n) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          var md = rs.getMetaData();
          for (int i = 1; i <= md.getColumnCount(); i++) {
            String key = camel(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
            Object v = rs.getObject(i);
            if (v instanceof Timestamp) v = rs.getTimestamp(i, utc()).toInstant().toString();
            else if (v instanceof java.sql.Date d) v = d.toLocalDate().toString();
            else if ((key.equals("id") || key.endsWith("Id")) && v != null) v = v.toString();
            m.put(key, v);
          }
          return m;
        });
  }

  public Map<String, Object> one(String sql, Object... args) {
    var list = rows(sql, args);
    if (list.isEmpty()) throw ApiException.missing();
    return list.getFirst();
  }

  public Map<String, Object> find(String sql, Object... args) {
    var list = rows(sql, args);
    return list.isEmpty() ? null : list.getFirst();
  }

  public long count(String sql, Object... args) {
    Long n = jdbc.query(sql, ps -> bind(ps, args), rs -> rs.next() ? rs.getLong(1) : 0L);
    return n == null ? 0 : n;
  }

  public int update(String sql, Object... args) {
    return jdbc.update(sql, ps -> bind(ps, args));
  }

  public String insert(String sql, Object... args) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var ps = c.prepareStatement(sql, new String[] {"id"});
          bind(ps, args);
          return ps;
        },
        key);
    return Objects.requireNonNull(key.getKey()).toString();
  }

  private static Calendar utc() {
    return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
  }

  private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      if (args[i] instanceof Timestamp value) ps.setTimestamp(i + 1, value, utc());
      else ps.setObject(i + 1, args[i]);
    }
  }

  static String camel(String s) {
    StringBuilder o = new StringBuilder();
    boolean cap = false;
    for (char c : s.toCharArray()) {
      if (c == '_') {
        cap = true;
        continue;
      }
      o.append(cap ? Character.toUpperCase(c) : c);
      cap = false;
    }
    return o.toString();
  }

  public static long number(Map<String, Object> m, String k) {
    Object v = m.get(k);
    return v == null ? 0 : Long.parseLong(v.toString());
  }
}
