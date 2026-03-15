package com.wedocode.queuelab.api;

import io.javalin.websocket.WsContext;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class QueueEventHub {
  private final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();

  public void register(WsContext context) {
    sessions.add(context);
  }

  public void unregister(WsContext context) {
    sessions.remove(context);
  }

  public int activeConnections() {
    return sessions.size();
  }

  public void broadcast(String payload) {
    sessions.removeIf(context -> !context.session.isOpen());
    for (var context : sessions) {
      context.send(payload);
    }
  }
}
