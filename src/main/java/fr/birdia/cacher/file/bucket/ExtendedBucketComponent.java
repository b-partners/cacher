package fr.birdia.cacher.file.bucket;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Component
public class ExtendedBucketComponent extends BucketComponent {
  private final BucketConf bucketConf;

  public ExtendedBucketComponent(BucketConf bucketConf) {
    super(bucketConf);
    this.bucketConf = bucketConf;
  }

  public boolean exists(String objectKey) {
    var s3client = bucketConf.getS3Client();
    try {
      s3client.headObject(
          HeadObjectRequest.builder().bucket(bucketConf.getBucketName()).key(objectKey).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    }
  }
}
