package fr.birdia.cacher.service;

import static java.net.http.HttpClient.newHttpClient;

import fr.birdia.cacher.file.bucket.ExtendedBucketComponent;
import fr.birdia.cacher.hash.SHA256;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CacheService {
  private static final Duration DOWNLOAD_DURATION = Duration.ofMinutes(5);
  private static final String LOCK_SUFFIX = ".lock";

  private final ExtendedBucketComponent bucketComponent;
  private final SHA256 SHA256;

  /**
   * A lock older than this is considered abandoned (its holder crashed or timed out without
   * releasing it). Lambda executions cannot exceed 15 minutes.
   */
  private final Duration staleLockAge;

  /**
   * How long we wait for a concurrent upload before doing it ourselves. Its holder may be dead
   * without its lock being stale yet: callers would time out long before 15 minutes. Uploading the
   * same file twice is harmless, only slower.
   */
  private final Duration maxWait;

  private final Duration pollInterval;

  private final HttpClient httpClient = newHttpClient();

  @Autowired
  public CacheService(ExtendedBucketComponent bucketComponent, SHA256 SHA256) {
    this(
        bucketComponent,
        SHA256,
        Duration.ofMinutes(15),
        Duration.ofMinutes(2),
        Duration.ofSeconds(1));
  }

  CacheService(
      ExtendedBucketComponent bucketComponent,
      SHA256 SHA256,
      Duration staleLockAge,
      Duration maxWait,
      Duration pollInterval) {
    this.bucketComponent = bucketComponent;
    this.SHA256 = SHA256;
    this.staleLockAge = staleLockAge;
    this.maxWait = maxWait;
    this.pollInterval = pollInterval;
  }

  public URL getWithCache(URL url) {
    var bucketKey = SHA256.apply(url.toString());
    cacheOrWaitForOngoingUpload(url, bucketKey);
    return bucketComponent.presign(bucketKey, DOWNLOAD_DURATION);
  }

  private enum Lock {
    ACQUIRED,
    HELD_BY_OTHER,
    /** The bucket refused or failed the lock operation: we go on without it. */
    UNAVAILABLE
  }

  /**
   * Requests run on distinct Lambda instances, so concurrency is coordinated through a lock object
   * in the bucket, created atomically with a conditional write.
   *
   * <ul>
   *   <li>Nobody is uploading: we take the lock and upload.
   *   <li>Someone else is uploading: we wait and reuse their upload once it lands.
   *   <li>Their upload failed (lock released without the object) or they died (stale lock): we
   *       proceed as if nothing had been started.
   * </ul>
   *
   * The lock only spares a second download, caching never depends on it: whenever an operation on
   * the lock fails (missing permission, conditional writes not supported, S3 error, ...) or we have
   * waited {@link #maxWait}, we download and upload ourselves, as without any lock.
   */
  private void cacheOrWaitForOngoingUpload(URL url, String bucketKey) {
    var lockKey = bucketKey + LOCK_SUFFIX;
    Instant waitingSince = null;
    while (true) {
      if (bucketComponent.exists(bucketKey)) {
        if (waitingSince != null) {
          log.info("Reusing upload of {} done by a concurrent request", url);
        }
        return;
      }

      var lock = tryLock(lockKey);
      if (lock == Lock.ACQUIRED) {
        try {
          // The previous holder may have finished between our exists() check and our lock.
          if (!bucketComponent.exists(bucketKey)) {
            upload(url, bucketKey);
          }
          return;
        } finally {
          releaseQuietly(lockKey);
        }
      }
      if (lock == Lock.UNAVAILABLE) {
        upload(url, bucketKey);
        return;
      }

      Optional<Instant> lockedAt;
      try {
        lockedAt = bucketComponent.lastModified(lockKey);
      } catch (Exception e) {
        log.warn("Cannot read lock {}, uploading {} without it", lockKey, url, e);
        upload(url, bucketKey);
        return;
      }
      if (lockedAt.isEmpty()) {
        continue; // Lock released in the meantime: re-check right away.
      }
      if (lockedAt.get().isBefore(Instant.now().minus(staleLockAge))) {
        log.warn("Lock {} acquired at {} is stale, discarding it", lockKey, lockedAt.get());
        // Worst case, two waiters race here and both upload: same content, so harmless.
        if (releaseQuietly(lockKey)) {
          continue;
        }
        upload(url, bucketKey);
        return;
      }

      if (waitingSince == null) {
        log.info("{} is being uploaded by a concurrent request, waiting for it", url);
        waitingSince = Instant.now();
      } else if (Instant.now().isAfter(waitingSince.plus(maxWait))) {
        log.warn("Waited {} for the concurrent upload of {}, uploading it ourselves", maxWait, url);
        upload(url, bucketKey);
        return;
      }
      sleep(pollInterval);
    }
  }

  private Lock tryLock(String lockKey) {
    try {
      return bucketComponent.createIfAbsent(lockKey) ? Lock.ACQUIRED : Lock.HELD_BY_OTHER;
    } catch (Exception e) {
      log.warn("Cannot create lock {}, going on without it", lockKey, e);
      return Lock.UNAVAILABLE;
    }
  }

  /** Never fails: a lock left behind only makes the next requests wait, at most maxWait. */
  private boolean releaseQuietly(String lockKey) {
    try {
      bucketComponent.delete(lockKey);
      return true;
    } catch (Exception e) {
      log.warn("Cannot delete lock {}", lockKey, e);
      return false;
    }
  }

  private void upload(URL url, String bucketKey) {
    bucketComponent.upload(downloadWithGet(url), bucketKey);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  @SneakyThrows
  private File downloadWithGet(URL url) {
    var request = HttpRequest.newBuilder(url.toURI()).GET().build();
    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    var contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);

    var destination = File.createTempFile("cacher-download", ".tmp");
    try (var in = response.body();
        var out = new FileOutputStream(destination)) {
      var buffer = new byte[8192];
      var totalRead = 0L;
      var lastLoggedDecile = -1;
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
        totalRead += read;
        if (contentLength > 0) {
          var decile = (int) (totalRead * 10 / contentLength);
          if (decile > lastLoggedDecile) {
            lastLoggedDecile = decile;
            log.info(
                "Downloading {}: {}% ({}/{} bytes)", url, decile * 10, totalRead, contentLength);
          }
        }
      }
    }
    return destination;
  }
}
