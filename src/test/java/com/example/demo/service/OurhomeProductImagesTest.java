package com.example.demo.service;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;
class OurhomeProductImagesTest {
    @Test void storesMainImageAndDoesNotUseLabelAsProductPhoto() {
        assertThat(OurhomeProductImages.from(Map.of("img1","https://tfs.ourhome.co.kr/main.jpg","img2","https://tfs.ourhome.co.kr/label.jpg")))
            .containsEntry("image_url","https://tfs.ourhome.co.kr/main.jpg");
        assertThat(OurhomeProductImages.from(Map.of("img1","https://tfs.ourhome.co.kr/grd_WF_ThumNoImg.png","img2","https://tfs.ourhome.co.kr/label.jpg")))
            .containsEntry("image_url",null).containsEntry("images_provided",true);
    }
    @Test void missingFieldsPreserveExistingImageAndInvalidUrlsAreRejected() {
        assertThat(OurhomeProductImages.from(Map.of())).containsEntry("images_provided",false);
        assertThat(OurhomeProductImages.from(Map.of("img1","javascript:alert(1)"))).containsEntry("image_url",null);
        assertThat(OurhomeProductImages.from(Map.of("img1","https://user:password@example.com/a.jpg"))).containsEntry("image_url",null);
        assertThat(OurhomeProductImages.from(Map.of("imageRoute","https://tfs.ourhome.co.kr/photo.jpg")))
            .containsEntry("image_url","https://tfs.ourhome.co.kr/photo.jpg");
    }
}
