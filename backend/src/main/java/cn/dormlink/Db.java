package cn.dormlink;

import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
class Db {
  final JdbcTemplate jdbc;

  Db(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        (rs, n) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          var md = rs.getMetaData();
          for (int i = 1; i <= md.getColumnCount(); i++) {
            String key = camel(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
            Object v = rs.getObject(i);
            if (v instanceof Timestamp t) v = t.toInstant().toString();
            else if (v instanceof java.sql.Date d) v = d.toLocalDate().toString();
            else if ((key.equals("id") || key.endsWith("Id")) && v != null) v = v.toString();
            m.put(key, v);
          }
          return m;
        },
        args);
  }

  Map<String, Object> one(String sql, Object... args) {
    var list = rows(sql, args);
    if (list.isEmpty()) throw ApiException.missing();
    return list.getFirst();
  }

  Map<String, Object> find(String sql, Object... args) {
    var list = rows(sql, args);
    return list.isEmpty() ? null : list.getFirst();
  }

  long count(String sql, Object... args) {
    Long n = jdbc.queryForObject(sql, Long.class, args);
    return n == null ? 0 : n;
  }

  int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  String insert(String sql, Object... args) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
          return ps;
        },
        key);
    return Objects.requireNonNull(key.getKey()).toString();
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

  static long number(Map<String, Object> m, String k) {
    Object v = m.get(k);
    return v == null ? 0 : Long.parseLong(v.toString());
  }
}
