package cn.dormlink.assistance;

import java.util.*;

/** Request identity is kept separate from model prompts and never sent to the supplier. */
record AiCommand(String route, String key, Map<String, Object> body) {
  AiCommand {
    body = Collections.unmodifiableMap(new LinkedHashMap<>(body));
  }
}
