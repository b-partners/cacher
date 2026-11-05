package fr.birdia.cacher.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import fr.birdia.cacher.file.bucket.ExtendedBucketComponent;
import fr.birdia.cacher.hash.SHA256;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.S3Exception;

class CacheServiceTest {
  private static final Duration STALE_LOCK_AGE = Duration.ofMinutes(15);
  private static final Duration MAX_WAIT = Duration.ofMillis(50);

  ExtendedBucketComponent bucketComponent = mock();
  SHA256 sha256 = new SHA256();
  CacheService subject =
      new CacheService(bucketComponent, sha256, STALE_LOCK_AGE, MAX_WAIT, Duration.ofMillis(1));

  HttpServer source;
  URL url;
  String bucketKey;
  String lockKey;
  URL presigned;

  @BeforeEach
  void setUp() throws Exception {
    source = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    source.createContext(
        "/file",
        exchange -> {
          var body = "content".getBytes(UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    source.start();
    url = URI.create("http://localhost:" + source.getAddress().getPort() + "/file").toURL();
    bucketKey = sha256.apply(url.toString());
    lockKey = bucketKey + ".lock";
    presigned = URI.create("https://example.com/presigned").toURL();
    when(bucketComponent.presign(eq(bucketKey), any())).thenReturn(presigned);
  }

  @AfterEach
  void tearDown() {
    source.stop(0);
  }

  @Test
  void hit_does_not_upload() {
    when(bucketComponent.exists(bucketKey)).thenReturn(true);

    assertEquals(presigned, subject.getWithCache(url));

    verify(bucketComponent, never()).upload(any(), anyString());
    verify(bucketComponent, never()).createIfAbsent(anyString());
  }

  @Test
  void miss_uploads_under_lock_then_releases_it() {
    when(bucketComponent.exists(bucketKey)).thenReturn(false);
    when(bucketComponent.createIfAbsent(lockKey)).thenReturn(true);

    assertEquals(presigned, subject.getWithCache(url));

    verify(bucketComponent, times(1)).upload(any(File.class), eq(bucketKey));
    verify(bucketComponent, times(1)).delete(lockKey);
  }

  @Test
  void ongoing_upload_is_awaited_then_reused() {
    when(bucketComponent.exists(bucketKey)).thenReturn(false, false, false, true);
    when(bucketComponent.createIfAbsent(lockKey)).thenReturn(false);
    when(bucketComponent.lastModified(lockKey)).thenReturn(Optional.of(Instant.now()));

    assertEquals(presigned, subject.getWithCache(url));

    verify(bucketComponent, never()).upload(any(), anyString());
    verify(bucketComponent, never()).delete(anyString());
  }

  @Test
  void failed_ongoing_upload_is_retried_as_if_nothing_was_started() {
    when(bucketComponent.exists(bucketKey)).thenReturn(false);
    // Lock held by the other request, then released by it after its failure.
    when(bucketComponent.createIfAbsent(lockKey)).thenReturn(false, false, true);
    when(bucketComponent.lastModified(lockKey)).thenReturn(Optional.of(Instant.now()));

    assertEquals(presigned, subject.getWithCache(url));

    verify(bucketComponent, times(1)).upload(any(File.class), eq(bucketKey));
    verify(bucketComponent, times(1)).delete(lockKey);
  }

  @Test
  void upload_finished_right_before_lock_acquisition_is_not_redone() {
    when(bucketComponent.exists(bucketKey)).thenReturn(false, true);
    when(bucketComponent.createIfAbsent(lockKey)).thenReturn(true);

    assertEquals(presigned, subject.getWithCache(url));

    verify(bucketComponent, never()).upload(any(), anyString());
    verify(bucketComponent, times(1)).delete(lockKey);
  }

  @Test
  void stale_lock_is_discarded() {
    when(bucketComponent.exists(bucketKey)).thenReturn(false);
    when(bucketComponent.createIfAbsent(lockKey)).thenReturn(false, true);
    when(bucketComponent.lastModified(lockKey))
        .thenReturn(Optional.of(Instant.now().minus(STALE_LOCK_AGE).minusSeconds(1)));

    assertEquals(presigned, subject.getWithCache(url));

    verify(bucketComponent, times(1)).upload(any(File.class), eq(bucketKey));
    verify(bucketComponent, times(2)).delete(lockKey); // stale one, then ours
  }

  @Test
  void own_failed_upload_releases_lock() {
    when(bucketComponent.exists(bucketKey)).thenReturn(false);
    when(bucketComponent.createIfAbsent(lockKey)).thenReturn(true);
    doThrow(new RuntimeException("S3 down")).when(bucketComponent).upload(any(), eq(bucketKey));

    assertThrows(RuntimeException.class, () -> subject.getWithCache(url));

    verify(bucketComponent, times(1)).delete(lockKey);
  }

  @Test
  void lock_that_cannot_be_created_does_not_prevent_caching() {
    when(bucketComponent.exists(bucketKey)).thenReturn(false);
    when(bucketComponent.createIfAbsent(lockKey)).thenThrow(accessDenied());

    assertEquals(presigned, subject.getWithCache(url));

    verify(bucketComponent, times(1)).upload(any(File.class), eq(bucketKey));
    verify(bucketComponent, never()).delete(anyString());
  }

  @Test
  void lock_that_cannot_be_deleted_does_not_fail_the_upload() {
    when(bucketComponent.exists(bucketKey)).thenReturn(false);
    when(bucketComponent.createIfAbsent(lockKey)).thenReturn(true);
    doThrow(accessDenied()).when(bucketComponent).delete(lockKey);

    assertEquals(presigned, subject.getWithCache(url));

    verify(bucketComponent, times(1)).upload(any(File.class), eq(bucketKey));
  }

  @Test
  void own_failed_upload_is_reported_even_if_lock_cannot_be_deleted() {
    when(bucketComponent.exists(bucketKey)).thenReturn(false);
    when(bucketComponent.createIfAbsent(lockKey)).thenReturn(true);
    doThrow(new RuntimeException("S3 down")).when(bucketComponent).upload(any(), eq(bucketKey));
    doThrow(accessDenied()).when(bucketComponent).delete(lockKey);

    var actual = assertThrows(RuntimeException.class, () -> subject.getWithCache(url));

    assertEquals("S3 down", actual.getMessage());
  }

  @Test
  void lock_that_cannot_be_read_does_not_prevent_caching() {
    when(bucketComponent.exists(bucketKey)).thenReturn(false);
    when(bucketComponent.createIfAbsent(lockKey)).thenReturn(false);
    when(bucketComponent.lastModified(lockKey)).thenThrow(accessDenied());

    assertEquals(presigned, subject.getWithCache(url));

    verify(bucketComponent, times(1)).upload(any(File.class), eq(bucketKey));
  }

  @Test
  void stale_lock_that_cannot_be_deleted_does_not_prevent_caching() {
    when(bucketComponent.exists(bucketKey)).thenReturn(false);
    when(bucketComponent.createIfAbsent(lockKey)).thenReturn(false);
    when(bucketComponent.lastModified(lockKey))
        .thenReturn(Optional.of(Instant.now().minus(STALE_LOCK_AGE).minusSeconds(1)));
    doThrow(accessDenied()).when(bucketComponent).delete(lockKey);

    assertEquals(presigned, subject.getWithCache(url));

    verify(bucketComponent, times(1)).upload(any(File.class), eq(bucketKey));
  }

  @Test
  void concurrent_upload_that_never_lands_is_done_ourselves_after_max_wait() {
    when(bucketComponent.exists(bucketKey)).thenReturn(false);
    when(bucketComponent.createIfAbsent(lockKey)).thenReturn(false);
    when(bucketComponent.lastModified(lockKey)).thenReturn(Optional.of(Instant.now()));

    assertEquals(presigned, subject.getWithCache(url));

    verify(bucketComponent, times(1)).upload(any(File.class), eq(bucketKey));
    verify(bucketComponent, never()).delete(anyString());
  }

  private static S3Exception accessDenied() {
    return (S3Exception) S3Exception.builder().statusCode(403).message("Access Denied").build();
  }
}
