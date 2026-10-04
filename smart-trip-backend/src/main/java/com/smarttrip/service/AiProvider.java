package com.smarttrip.service;

import com.fasterxml.jackson.databind.*;
import com.smarttrip.common.BusinessException;
import com.smarttrip.config.AiProperties;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.stereotype.Service;

@Service
public class AiProvider {
  private final AiProperties config;
  private final ObjectMapper json =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final OkHttpClient client =
      new OkHttpClient.Builder()
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(120, TimeUnit.SECONDS)
          .callTimeout(150, TimeUnit.SECONDS)
          .build();

  public AiProvider(AiProperties config) {
    this.config = config;
  }

  public boolean available() {
    return config.isEnabled()
        && config.isFreeQuotaVerified()
        && config.getApiKey() != null
        && !config.getApiKey().isBlank()
        && !config.getApiKey().contains("YOUR_");
  }

  public String complete(String input) {
    if (!available()) throw new BusinessException(503, "AI免费额度尚未确认，暂未启用");
    String system =
        "你是中国境内旅行规划助手。只返回一个JSON对象：title,destination,startDate(YYYY-MM-DD或null),summary,tripStyle,travelGroup,days[{id(修改时保留已有ID),dayTheme,items[{id(修改时保留已有ID),itemType(SCENIC/FOOD/HOTEL/TRAFFIC/OTHER),itemName,startTime(HH:mm),costTimeHours,costEstimate,aiTips,transportTips}]}]。最多30天，不编造经纬度、评分、图片、实时天气。修改保持未改变的节点ID；新增节点id为null。费用只能是明确标记的预估。";
    try {
      String body =
          json.writeValueAsString(
              Map.of(
                  "model",
                  config.getModel(),
                  "stream",
                  true,
                  "messages",
                  List.of(
                      Map.of("role", "system", "content", system),
                      Map.of("role", "user", "content", input))));
      Request req =
          new Request.Builder()
              .url(config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions")
              .header("Authorization", "Bearer " + config.getApiKey())
              .post(RequestBody.create(body, MediaType.get("application/json")))
              .build();
      try (Response response = client.newCall(req).execute()) {
        if (!response.isSuccessful() || response.body() == null)
          throw new BusinessException(502, "AI提供方请求失败，请检查免费额度与模型权限");
        try (var reader = new BufferedReader(response.body().charStream())) {
          return readSse(reader, json);
        }
      }
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(502, "AI连接中断或输出异常，请重试");
    }
  }

  public static String readSse(BufferedReader reader, ObjectMapper json) throws IOException {
    StringBuilder output = new StringBuilder(), event = new StringBuilder();
    String line;
    boolean ended = false;
    while ((line = reader.readLine()) != null) {
      if (line.length() > 262144) throw new IOException("event too large");
      if (line.isEmpty()) {
        if (event.length() > 0) {
          String data = event.toString().stripTrailing();
          event.setLength(0);
          if (data.equals("[DONE]")) {
            ended = true;
            break;
          }
          JsonNode n = json.readTree(data);
          if (n.has("error")) throw new IOException("provider error");
          JsonNode choices = n.path("choices");
          for (var c : choices) {
            String text = c.path("delta").path("content").asText("");
            output.append(text);
            if (!c.path("finish_reason").isNull()
                && !c.path("finish_reason").isMissingNode()
                && !c.path("finish_reason").asText().equals("stop"))
              throw new IOException("incomplete response");
          }
          if (output.length() > 200000) throw new IOException("response too large");
        }
      } else if (line.startsWith("data:")) {
        event.append(line.substring(5).stripLeading()).append('\n');
      }
    }
    if (!ended || output.isEmpty()) throw new IOException("missing completion marker");
    return output.toString();
  }

  public TripResult parse(String text) {
    String clean = text.trim();
    if (clean.startsWith("~~~")) throw new BusinessException(422, "AI未返回JSON");
    if (clean.startsWith("```")) {
      int first = clean.indexOf('\n'), last = clean.lastIndexOf("```");
      if (first < 0 || last <= first) throw new BusinessException(422, "AI返回格式不完整");
      clean = clean.substring(first + 1, last).trim();
    }
    try {
      JsonNode n = json.readTree(clean);
      if (n == null || !n.isObject() || !n.path("days").isArray()) throw new IOException();
      return new TripResult(clean);
    } catch (Exception e) {
      throw new BusinessException(422, "AI返回的路书结构无效，未保存任何节点");
    }
  }

  public record TripResult(String json) {}
}
