package net.kyori.adventure.audience;

/**
 * Test-only linkage shim. paper-api 26.1.2 (compile-low baseline) still references this enum in
 * deprecated {@code CommandSender} overloads, but the adventure 5.x on the test classpath removed
 * it, breaking {@code Proxy} construction over sender interfaces in tests. Mirrors the removed
 * adventure 4.x enum exactly. Never shipped: production relocates {@code net.kyori} and the server
 * supplies its own adventure.
 */
public enum MessageType {
  CHAT,
  SYSTEM
}
