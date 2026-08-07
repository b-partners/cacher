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
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@AllArgsConstructor
@Service
public class CacheService {
  private static final long MIN_CACHEABLE_SIZE_BYTES = 1_000_000L;
  private final ExtendedBucketComponent bucketComponent;
  private final SHA256 SHA256;
  private final Duration DOWNLOAD_DURATION = Duration.ofMinutes(5);

  private final HttpClient httpClient = newHttpClient();

  public URL getWithCache(URL url) {
    var bucketKey = SHA256.apply(url.toString());
    if (!bucketComponent.exists(bucketKey)) {
      File downloadedFromSource = null;
      try {
        downloadedFromSource = downloadWithGet(url);
        if (downloadedFromSource.length() < MIN_CACHEABLE_SIZE_BYTES) {
          log.info(
              "File downloaded from {} too small ({} bytes < {}), skipping cache",
              url,
              downloadedFromSource.length(),
              MIN_CACHEABLE_SIZE_BYTES);
          return url;
        }
        bucketComponent.upload(downloadedFromSource, bucketKey);
      } finally {
        if (downloadedFromSource != null && downloadedFromSource.exists()) {
          if (!downloadedFromSource.delete()) {
            log.info("Failed to delete temporary file {}", downloadedFromSource);
          }
        }
      }
    }
    return bucketComponent.presign(bucketKey, DOWNLOAD_DURATION);
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
