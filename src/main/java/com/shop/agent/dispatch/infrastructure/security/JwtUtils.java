package com.shop.agent.dispatch.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 工具类，负责 Token 的生成、解析与校验。
 *
 * <p>基于 jjwt 0.12.x，使用 HMAC-SHA256 签名算法，线程安全。</p>
 */
@Slf4j
@Component
public class JwtUtils {

  @Value("${jwt.secret:RGVmYXVsdFNlY3JldEtleUZvclNob3BBZ2VudDIwMjY=}")
  private String secret;

  @Value("${jwt.expiration-ms:86400000}")
  private long expirationMs;

  /**
   * 生成 JWT Token。
   *
   * @param username 用户名
   * @param role     角色（如 MANAGER）
   * @return 签名后的 Token 字符串
   */
  public String generateToken(String username, String role) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + expirationMs);

    return Jwts.builder()
        .subject(username)
        .claim("role", role)
        .issuedAt(now)
        .expiration(expiry)
        .signWith(getSigningKey(), Jwts.SIG.HS256)
        .compact();
  }

  /**
   * 校验 Token 是否合法且未过期。
   *
   * @param token JWT Token
   * @return true=有效，false=无效或过期
   */
  public boolean validateToken(String token) {
    try {
      parseClaims(token);
      return true;
    } catch (SecurityException e) {
      log.warn("【JWT】签名错误: {}", e.getMessage());
    } catch (MalformedJwtException e) {
      log.warn("【JWT】Token 格式错误: {}", e.getMessage());
    } catch (ExpiredJwtException e) {
      log.warn("【JWT】Token 已过期: {}", e.getMessage());
    } catch (UnsupportedJwtException e) {
      log.warn("【JWT】不支持的 Token: {}", e.getMessage());
    } catch (IllegalArgumentException e) {
      log.warn("【JWT】Token 为空: {}", e.getMessage());
    }
    return false;
  }

  /**
   * 从 Token 中提取用户名。
   *
   * @param token JWT Token
   * @return 用户名
   */
  public String getUsernameFromToken(String token) {
    return parseClaims(token).getSubject();
  }

  /**
   * 从 Token 中提取角色。
   *
   * @param token JWT Token
   * @return 角色字符串
   */
  public String getRoleFromToken(String token) {
    return parseClaims(token).get("role", String.class);
  }

  private Claims parseClaims(String token) {
    return Jwts.parser()
        .verifyWith(getSigningKey())
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }

  private SecretKey getSigningKey() {
    byte[] keyBytes = Decoders.BASE64.decode(secret);
    return Keys.hmacShaKeyFor(keyBytes);
  }
}
