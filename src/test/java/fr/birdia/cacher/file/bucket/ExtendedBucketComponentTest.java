package fr.birdia.cacher.file.bucket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class ExtendedBucketComponentTest {
  BucketConf bucketConf = mock();
  S3Client s3Client = mock();
  ExtendedBucketComponent subject = new ExtendedBucketComponent(bucketConf);

  @BeforeEach
  void setUp() {
    when(bucketConf.getS3Client()).thenReturn(s3Client);
    when(bucketConf.getBucketName()).thenReturn("bucket");
  }

  @Test
  void lastModified_of_existing_object() {
    var date = Instant.parse("2026-11-03T10:00:00Z");
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().lastModified(date).build());

    assertEquals(Optional.of(date), subject.lastModified("key"));
    assertTrue(subject.exists("key"));
  }

  @Test
  void lastModified_of_missing_object() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().build());

    assertEquals(Optional.empty(), subject.lastModified("key"));
    assertFalse(subject.exists("key"));
  }

  @Test
  void createIfAbsent_sends_conditional_put() {
    assertTrue(subject.createIfAbsent("key.lock"));

    var captor = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
    var request = captor.getValue();
    assertEquals("key.lock", request.key());
    assertEquals(
        "*", request.overrideConfiguration().orElseThrow().headers().get("If-None-Match").get(0));
  }

  @Test
  void createIfAbsent_returns_false_when_already_exists_or_conflicting() {
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(s3Exception(412))
        .thenThrow(s3Exception(409));

    assertFalse(subject.createIfAbsent("key.lock"));
    assertFalse(subject.createIfAbsent("key.lock"));
  }

  @Test
  void createIfAbsent_rethrows_other_errors() {
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(s3Exception(500));

    assertThrows(S3Exception.class, () -> subject.createIfAbsent("key.lock"));
  }

  @Test
  void delete_deletes_object() {
    subject.delete("key.lock");

    var captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(s3Client).deleteObject(captor.capture());
    assertEquals("bucket", captor.getValue().bucket());
    assertEquals("key.lock", captor.getValue().key());
  }

  private static S3Exception s3Exception(int statusCode) {
    return (S3Exception) S3Exception.builder().statusCode(statusCode).build();
  }
}
