package com.smarttrip.config;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ClientAddressResolver {
  private final Set<String> trusted;

  public ClientAddressResolver(AuthLimitProperties properties) {
    var values = new HashSet<String>();
    for (String proxy : properties.getTrustedProxies()) {
      String normalized = literal(proxy);
      if (normalized == null)
        throw new IllegalArgumentException("Trusted proxy must be an IP literal");
      values.add(normalized);
    }
    trusted = Set.copyOf(values);
  }

  public String resolve(HttpServletRequest request) {
    String remote = literal(request.getRemoteAddr());
    if (remote == null) return "unknown"; // Invalid sources share one restrictive bucket.
    if (!trusted.contains(remote)) return remote;
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded == null || forwarded.isBlank() || forwarded.length() > 2048) return remote;
    String[] chain = forwarded.split(",", -1);
    if (chain.length > 16) return remote;
    List<String> addresses = new ArrayList<>();
    for (String address : chain) {
      String ip = literal(address.trim());
      if (ip == null) return remote;
      addresses.add(ip);
    }
    String current = remote;
    for (int n = addresses.size() - 1; n >= 0 && trusted.contains(current); n--)
      current = addresses.get(n);
    return current;
  }

  private static String literal(String value) {
    if (value == null || value.length() > 64) return null;
    try {
      if (value.contains(":")) {
        if (!value.matches("[0-9a-fA-F:.]+")) return null;
        return InetAddress.getByName(value).getHostAddress();
      }
      String[] parts = value.split("\\.", -1);
      if (parts.length != 4) return null;
      List<String> normalized = new ArrayList<>();
      for (String part : parts) {
        if (!part.matches("[0-9]{1,3}")) return null;
        int n = Integer.parseInt(part);
        if (n > 255) return null;
        normalized.add(Integer.toString(n));
      }
      return String.join(".", normalized);
    } catch (Exception invalid) {
      return null;
    }
  }
}
