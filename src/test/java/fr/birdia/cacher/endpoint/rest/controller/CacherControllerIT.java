package fr.birdia.cacher.endpoint.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.birdia.cacher.conf.FacadeIT;
import fr.birdia.cacher.file.bucket.ExtendedBucketComponent;
import fr.birdia.cacher.hash.SHA256;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.server.ResponseStatusException;

class CacherControllerIT extends FacadeIT {

  @Autowired CacherController subject;

  @Autowired SHA256 SHA256;

  @MockBean ExtendedBucketComponent bucketComponent;

  @Test
  void unauthorized() {
    assertThrows(
        ResponseStatusException.class, () -> subject.getWithCache("dummy", "invalid-api-key"));
  }

  @Test
  void miss() throws MalformedURLException {
    var encodedUrl =
        "https%3A%2F%2Fimages.unsplash.com%2Fphoto-1779896412092-4cb69cf4ccad%3Fq%3D80%26w%3D3000%26auto%3Dformat%26fit%3Dcrop%26ixlib%3Drb-4.1.0%26ixid%3DM3wxMjA3fDF8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%253D%253D";
    var decodedUrl =
        "https://images.unsplash.com/photo-1779896412092-4cb69cf4ccad?q=80&w=3000&auto=format&fit=crop&ixlib=rb-4.1.0&ixid=M3wxMjA3fDF8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D";
    var bucketKey = SHA256.apply(decodedUrl);
    assertEquals("8547b3662d07fb7867a11c2c89672a664fd6e4ffb370dee9f60e0e65eeebde09", bucketKey);

    when(bucketComponent.exists(bucketKey)).thenReturn(false);
    when(bucketComponent.presign(eq(bucketKey), any(Duration.class)))
        .thenReturn(
            URI.create("https://example.com/presigned?param1=3&param2=4%20param3=5").toURL());

    var response = subject.getWithCache(encodedUrl, "test-api-key");

    verify(bucketComponent, times(1)).upload(any(File.class), eq(bucketKey));
    assertEquals("https://example.com/presigned?param1=3&param2=4%20param3=5", response);
  }
}
