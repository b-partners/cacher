package fr.birdia.cacher.endpoint.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
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
  void miss_case_one() throws MalformedURLException {
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

    verify(bucketComponent, never()).upload(any(), any());
    assertEquals(
        "https://images.unsplash.com/photo-1779896412092-4cb69cf4ccad?q=80&w=3000&auto=format&fit=crop&ixlib=rb-4.1.0&ixid=M3wxMjA3fDF8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D",
        response);
  }

  @Test
  void miss_case_two() throws MalformedURLException {
    var encodedUrl =
        "https%3A%2F%2Fdata.geopf.fr%2Ftelechargement%2Fdownload%2FLiDARHD-NUALID%2FNUALHD_1-0__LAZ_LAMB93_EM_2025-07-01%2FLHD_FXX_0404_6445_PTS_LAMB93_IGN69.copc.laz";
    var decodedUrl =
        "https://data.geopf.fr/telechargement/download/LiDARHD-NUALID/NUALHD_1-0__LAZ_LAMB93_EM_2025-07-01/LHD_FXX_0404_6445_PTS_LAMB93_IGN69.copc.laz";
    var bucketKey = SHA256.apply(decodedUrl);
    assertEquals("07826fff9818eb56aad68a40bcbd0348ecd211c386549ae2b5bdb5b7d7cb7db1", bucketKey);

    when(bucketComponent.exists(bucketKey)).thenReturn(false);
    when(bucketComponent.presign(eq(bucketKey), any(Duration.class)))
        .thenReturn(
            URI.create("https://example.com/presigned?param1=3&param2=4%20param3=5").toURL());

    var response = subject.getWithCache(encodedUrl, "test-api-key");

    verify(bucketComponent, times(1)).upload(any(File.class), eq(bucketKey));
    assertEquals("https://example.com/presigned?param1=3&param2=4%20param3=5", response);
  }
}
