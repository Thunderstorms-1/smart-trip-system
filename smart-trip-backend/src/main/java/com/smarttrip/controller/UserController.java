package com.smarttrip.controller;

import com.smarttrip.common.*;
import com.smarttrip.entity.User;
import com.smarttrip.mapper.UserMapper;
import com.smarttrip.service.ResourceAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/user")
public class UserController {
  private final UserMapper users;
  private final ResourceAccess access;
  private final PasswordEncoder passwords;
  private final Path uploads;

  public UserController(
      UserMapper users,
      ResourceAccess access,
      PasswordEncoder passwords,
      @Value("${storage.upload-dir:../.local/uploads}") String directory) {
    this.users = users;
    this.access = access;
    this.passwords = passwords;
    this.uploads = Path.of(directory).toAbsolutePath().normalize();
  }

  public record Profile(
      @NotBlank @Size(max = 50) String nickname,
      @Size(max = 500) String bio,
      @Size(max = 50) String homeCity,
      @Size(max = 1000) String travelPreferences) {}

  public record PasswordChange(
      @NotNull String currentPassword, @NotNull @Size(min = 8, max = 72) String newPassword) {}

  private User current() {
    User user = users.findById(access.userId());
    if (user == null) throw new BusinessException(401, "账号不存在");
    return user;
  }

  private Map<String, Object> projection(User u, boolean own) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("id", u.getId());
    v.put("nickname", u.getNickname());
    v.put("avatar", u.getAvatar());
    v.put("bio", u.getBio());
    v.put("homeCity", u.getHomeCity());
    if (own) {
      v.put("username", u.getUsername());
      v.put("email", u.getEmail());
      v.put("travelPreferences", u.getTravelPreferences());
      v.put("aiQuotaDaily", u.getAiQuotaDaily());
      v.put("aiQuotaUsed", u.getAiQuotaUsed());
    }
    return v;
  }

  @GetMapping("/info")
  public Result<?> info() {
    return Result.success(projection(current(), true));
  }

  @GetMapping("/public/{id}")
  public Result<?> author(@PathVariable Long id) {
    User u = users.findById(id);
    if (u == null || !Integer.valueOf(1).equals(u.getStatus()))
      throw new BusinessException(404, "作者不存在");
    return Result.success(projection(u, false));
  }

  @PutMapping("/profile")
  public Result<?> profile(@Valid @RequestBody Profile dto) {
    User u = current();
    u.setNickname(dto.nickname().trim());
    u.setBio(dto.bio());
    u.setHomeCity(dto.homeCity());
    u.setTravelPreferences(dto.travelPreferences());
    users.updateProfile(u);
    return Result.success(projection(u, true));
  }

  @PutMapping("/password")
  public Result<?> password(@Valid @RequestBody PasswordChange dto) {
    User u = current();
    if (dto.newPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new BusinessException(400, "密码最多72个UTF-8字节");
    if (!passwords.matches(dto.currentPassword(), u.getPassword()))
      throw new BusinessException(400, "当前密码错误");
    if (users.updatePassword(u.getId(), passwords.encode(dto.newPassword()), u.getPassword()) != 1)
      throw new BusinessException(409, "密码已发生变更，请重新登录");
    return Result.success();
  }

  @PostMapping("/logout-all")
  public Result<?> logout() {
    users.invalidateTokens(access.userId());
    return Result.success();
  }

  @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Result<?> avatar(@RequestParam("file") MultipartFile file) throws IOException {
    User u = current();
    if (file.isEmpty() || file.getSize() > 2 * 1024 * 1024)
      throw new BusinessException(400, "图片必须在2MB以内");
    java.awt.image.BufferedImage image;
    try (var in = ImageIO.createImageInputStream(file.getInputStream())) {
      var readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) throw new BusinessException(400, "仅支持PNG或JPEG图片");
      var reader = readers.next();
      try {
        String format = reader.getFormatName();
        if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg"))
          throw new BusinessException(400, "仅支持PNG或JPEG图片");
        reader.setInput(in);
        int w = reader.getWidth(0), h = reader.getHeight(0);
        if (w < 1 || h < 1 || w > 4096 || h > 4096 || (long) w * h > 16000000)
          throw new BusinessException(400, "图片尺寸过大");
        image = reader.read(0);
      } finally {
        reader.dispose();
      }
    }
    int size = 256;
    var thumb =
        new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    var g = thumb.createGraphics();
    g.setRenderingHint(
        java.awt.RenderingHints.KEY_INTERPOLATION,
        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    int side = Math.min(image.getWidth(), image.getHeight()),
        x = (image.getWidth() - side) / 2,
        y = (image.getHeight() - side) / 2;
    g.drawImage(image, 0, 0, size, size, x, y, x + side, y + side, null);
    g.dispose();
    Files.createDirectories(uploads);
    String name = UUID.randomUUID() + ".png";
    ImageIO.write(thumb, "png", uploads.resolve(name).toFile());
    String url = "/api/user/avatars/" + name;
    users.updateAvatar(u.getId(), url);
    return Result.success(Map.of("avatar", url));
  }

  @GetMapping("/avatars/{name}")
  public ResponseEntity<byte[]> avatarFile(@PathVariable String name) throws IOException {
    if (!name.matches("[a-f0-9-]{36}\\.png")) throw new BusinessException(404, "头像不存在");
    Path file = uploads.resolve(name);
    if (!Files.isRegularFile(file)) throw new BusinessException(404, "头像不存在");
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1)))
        .body(Files.readAllBytes(file));
  }
}
