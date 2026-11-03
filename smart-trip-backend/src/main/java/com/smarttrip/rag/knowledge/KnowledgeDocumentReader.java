package com.smarttrip.rag.knowledge;

import com.fasterxml.jackson.databind.*;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.*;
import com.smarttrip.config.RagProperties;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeDocumentReader {
  private final RagProperties config;
  private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private volatile Encoding encoding;
  public record PreparedDocument(KnowledgeDocument document, String contentHash, List<KnowledgeChunk> chunks) {}
  public KnowledgeDocumentReader(RagProperties config) { this.config = config; }
  public List<PreparedDocument> readManifest(Path manifest) {
    try {
      Path realManifest = manifest.toRealPath();
      if (Files.size(realManifest) > 1024 * 1024) throw new RagException("MANIFEST_INVALID", "知识清单超过1MB限制");
      JsonNode entries = json.readTree(Files.readString(realManifest, StandardCharsets.UTF_8)).path("documents");
      if (!entries.isArray() || entries.isEmpty() || entries.size() > 200)
        throw new RagException("MANIFEST_INVALID", "知识清单必须包含1至200个documents条目");
      Set<String> ids = new HashSet<>();
      List<PreparedDocument> results = new ArrayList<>();
      for (JsonNode entry : entries) {
        KnowledgeDocument metadata = json.treeToValue(entry, KnowledgeDocument.class);
        validate(metadata);
        if (!ids.add(metadata.documentId())) throw new RagException("MANIFEST_INVALID", "知识清单文档ID重复");
        Path file = realManifest.getParent().resolve(metadata.path()).normalize().toRealPath();
        if (!file.startsWith(realManifest.getParent()) || !Files.isRegularFile(file))
          throw new RagException("DOCUMENT_PATH_DENIED", "文档必须位于知识清单目录内");
        results.add(prepare(metadata, file));
      }
      return List.copyOf(results);
    } catch (RagException e) { throw e; }
    catch (Exception e) { throw new RagException("MANIFEST_INVALID", "知识清单或本地文档无法读取"); }
  }
  public void validate(KnowledgeDocument doc) {
    if (doc == null || !doc.authorized() || !"PUBLIC".equals(doc.visibility()) || doc.ownerId() != null)
      throw new RagException("DOCUMENT_ACCESS_DENIED", "仅允许导入明确授权的公开资料，私人资料不得进入公共索引");
    if (doc.documentId() == null || !doc.documentId().matches("[A-Za-z0-9_-]{1,120}")
        || doc.documentVersion() == null || !doc.documentVersion().matches("[A-Za-z0-9_.-]{1,80}")
        || doc.title() == null || doc.title().isBlank() || doc.title().length() > 240
        || doc.path() == null || doc.path().isBlank()
        || doc.category() == null || !doc.category().matches("[A-Z_]{2,40}")
        || doc.sourceName() == null || doc.sourceName().isBlank() || doc.sourceName().length() > 240
        || doc.destinations() == null || doc.destinations().isEmpty()
        || doc.regions() == null || doc.applicableSeasons() == null)
      throw new RagException("DOCUMENT_METADATA_INVALID", "知识来源、标题、版本或地理元数据不完整");
    try { KnowledgeValidity.window(doc.validFrom(), doc.validUntil()); }
    catch (RuntimeException ex) {
      throw new RagException("DOCUMENT_VALIDITY_INVALID", "资料有效期须同时填写ISO日期，且开始日期不得晚于结束日期");
    }
    try {
      URI source = URI.create(doc.sourceUrl());
      if (!Set.of("http", "https").contains(source.getScheme()) || source.getHost() == null
          || source.getRawUserInfo() != null || doc.sourceUrl().length() > 2000)
        throw new IllegalArgumentException();
      if (doc.sourceUpdatedAt() != null && LocalDate.parse(doc.sourceUpdatedAt()).isAfter(LocalDate.now().plusDays(1)))
        throw new IllegalArgumentException();
      for (List<String> values : List.of(doc.destinations(), doc.regions(), doc.applicableSeasons()))
        if (values.size() > 30 || values.stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 100))
          throw new IllegalArgumentException();
    } catch (RuntimeException e) { throw new RagException("DOCUMENT_SOURCE_INVALID", "知识来源URL或更新时间无效"); }
  }
  public PreparedDocument prepare(KnowledgeDocument doc, Path path) {
    validate(doc);
    if (config.getChunkTokens() < 64 || config.getChunkTokens() > 2048 || config.getChunkOverlapTokens() < 0
        || config.getChunkOverlapTokens() >= config.getChunkTokens() / 2)
      throw new RagException("RAG_CONFIG_INVALID", "切片Token必须为64至2048，重叠须少于切片的一半");
    try {
      if (Files.size(path) > 20 * 1024 * 1024) throw new RagException("DOCUMENT_TOO_LARGE", "文档超过20MB限制");
      String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
      List<Document> sections;
      if (filename.endsWith(".md") || filename.endsWith(".markdown")) {
        sections = new MarkdownDocumentReader(new FileSystemResource(path), MarkdownDocumentReaderConfig.builder()
            .withIncludeCodeBlock(false).withIncludeBlockquote(true).build()).get();
      } else if (filename.endsWith(".pdf")) {
        sections = new PagePdfDocumentReader(new FileSystemResource(path)).get();
      } else throw new RagException("DOCUMENT_FORMAT_UNSUPPORTED", "知识导入只接受Markdown或PDF");
      String fingerprint = sha256(json.writeValueAsString(doc) + "\n" + sha256(Files.readAllBytes(path))
          + "\n" + config.getChunkTokens() + ":" + config.getChunkOverlapTokens());
      var splitter = TokenTextSplitter.builder().withChunkSize(config.getChunkTokens() - config.getChunkOverlapTokens())
          .withMinChunkSizeChars(1).withMinChunkLengthToEmbed(1).withMaxNumChunks(2000).withKeepSeparator(true).build();
      List<KnowledgeChunk> chunks = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      String ingestedAt = Instant.now().toString();
      for (Document section : sections) {
        String clean = clean(section.getText());
        if (clean.isBlank()) continue;
        Object heading = section.getMetadata().get("title");
        String title = doc.title() + (heading == null || heading.toString().isBlank() ? "" : " · " + heading);
        String previous = "";
        for (Document piece : splitter.apply(List.of(new Document(clean)))) {
          String content = clean(piece.getText());
          if (content.isBlank()) continue;
          String withOverlap = previous.isBlank() ? content : previous + "\n" + content;
          previous = tailTokens(content, config.getChunkOverlapTokens());
          if (!seen.add(sha256(title + "\n" + withOverlap))) continue;
          String chunkId = sha256(doc.documentId() + ":" + fingerprint + ":" + chunks.size() + ":" + withOverlap);
          chunks.add(new KnowledgeChunk(doc.documentId(), chunkId, title, withOverlap, doc.destinations(), doc.regions(),
              doc.category(), doc.applicableSeasons(), doc.sourceName(), doc.sourceUrl(), doc.sourceUpdatedAt(), ingestedAt,
              doc.visibility(), doc.ownerId(), doc.documentVersion(), sha256(withOverlap), config.getEmbeddingModel(),
              config.getEmbeddingVersion(), doc.authorized(), doc.testOnly(), doc.validFrom(), doc.validUntil()));
          if (chunks.size() > 2000) throw new RagException("DOCUMENT_TOO_LARGE", "单文档切片超过2000限制");
        }
      }
      if (chunks.isEmpty()) throw new RagException("DOCUMENT_EMPTY", "文档未产生可索引文本，扫描PDF需先人工OCR");
      return new PreparedDocument(doc, fingerprint, List.copyOf(chunks));
    } catch (RagException e) { throw e; }
    catch (Exception e) { throw new RagException("DOCUMENT_READ_FAILED", "文档解析失败，未写入索引"); }
  }
  private String tailTokens(String value, int count) {
    if (count == 0) return "";
    if (encoding == null) synchronized (this) {
      if (encoding == null) encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    }
    IntArrayList tokens = encoding.encodeOrdinary(value), tail = new IntArrayList(count);
    for (int i = Math.max(0, tokens.size() - count); i < tokens.size(); i++) tail.add(tokens.get(i));
    return encoding.decode(tail).replace("\uFFFD", "").strip();
  }
  private static String clean(String value) {
    return value == null ? "" : value.replace("\u0000", "").replace("\r\n", "\n").replaceAll("[\\t ]+", " ")
        .replaceAll("\\n{3,}", "\n\n").strip();
  }
  public static String sha256(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }
  private static String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
  }
}