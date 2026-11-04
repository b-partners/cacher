package fr.birdia.cacher.file.bucket;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class ExtendedBucketComponent extends BucketComponent {
  private static final int NOT_FOUND = 404;
  private static final int CONFLICT = 409;
  private static final int PRECONDITION_FAILED = 412;

  private final BucketConf bucketConf;

  public ExtendedBucketComponent(BucketConf bucketConf) {
    super(bucketConf);
    this.bucketConf = bucketConf;
  }

  public boolean exists(String objectKey) {
    return lastModified(objectKey).isPresent();
  }

  public Optional<Instant> lastModified(String objectKey) {
    var s3client = bucketConf.getS3Client();
    try {
      var head =
          s3client.headObject(
              HeadObjectRequest.builder()
                  .bucket(bucketConf.getBucketName())
                  .key(objectKey)
                  .build());
      return Optional.of(head.lastModified());
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      // HEAD has no body: a missing key can also come back as a bare 404
      if (e.statusCode() == NOT_FOUND) {
        return Optional.empty();
      }
      throw e;
    }
  }

  /**
   * Atomically creates an empty object, only if no object exists yet at that key (S3 conditional
   * write). Returns false if the object already exists.
   */
  public boolean createIfAbsent(String objectKey) {
    try {
      bucketConf
          .getS3Client()
          .putObject(
              PutObjectRequest.builder()
                  .bucket(bucketConf.getBucketName())
                  .key(objectKey)
                  .overrideConfiguration(c -> c.putHeader("If-None-Match", "*"))
                  .build(),
              RequestBody.empty());
      return true;
    } catch (S3Exception e) {
      // 412: already exists. 409: a concurrent conditional write on the same key is in progress.
      if (e.statusCode() == PRECONDITION_FAILED || e.statusCode() == CONFLICT) {
        return false;
      }
      throw e;
    }
  }

  public void delete(String objectKey) {
    bucketConf
        .getS3Client()
        .deleteObject(
            DeleteObjectRequest.builder()
                .bucket(bucketConf.getBucketName())
                .key(objectKey)
                .build());
  }
}
