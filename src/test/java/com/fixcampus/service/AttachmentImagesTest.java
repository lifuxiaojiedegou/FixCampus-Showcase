package com.fixcampus.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import com.fixcampus.api.v1.ApiProblem;
import com.fixcampus.config.AttachmentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockMultipartFile;

import static com.fixcampus.AttachmentFixtures.image;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentImagesTest {
    @TempDir Path storage;

    @ParameterizedTest
    @CsvSource({"png,image/png", "jpeg,image/jpeg"})
    void trustsMagicOnlyAndReencodesPixelsWithoutTrailingData(String format, String mime) throws Exception {
        byte[] original = image(format, 5, 4);
        byte[] extra = "<script>trailing fixture</script>".getBytes(StandardCharsets.US_ASCII);
        var input = Arrays.copyOf(original, original.length + extra.length);
        System.arraycopy(extra, 0, input, original.length, extra.length);

        var result = images(2048, 32, 32, 1024).prepare(part("folder\\test.html", input));

        assertThat(result.filename()).isEqualTo("test.html");
        assertThat(result.contentType()).isEqualTo(mime);
        assertThat(result.width()).isEqualTo(5);
        assertThat(result.height()).isEqualTo(4);
        assertThat(result.bytes().length).isLessThan(input.length);
        assertThat(new String(result.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("trailing fixture");
        assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(result.bytes())));
        if (format.equals("png")) {
            var decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
            assertThat(decoded.getRGB(4, 3)).isEqualTo(0x773478ba);
        }
        assertThat(storage).isEmptyDirectory();
    }

    @Test
    void stripsJpegExifSegment() throws Exception {
        byte[] original = image("jpeg", 4, 4);
        byte[] exif = HexFormat.of().parseHex("ffe1001045786966000049492a0000000000");
        byte[] marked = new byte[original.length + exif.length];
        System.arraycopy(original, 0, marked, 0, 2);
        System.arraycopy(exif, 0, marked, 2, exif.length);
        System.arraycopy(original, 2, marked, 2 + exif.length, original.length - 2);

        var result = images(2048, 32, 32, 1024).prepare(part("exif.jpg", marked));
        assertThat(new String(result.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html>fixture</html>", "<svg xmlns='http://www.w3.org/2000/svg'/>", "picture", ""})
    void rejectsForgedContentAndEmptyFile(String contents) {
        var file = part("fixture.png", contents.getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> images(2048, 32, 32, 1024).prepare(file)).satisfies(error("INVALID_ATTACHMENT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg"})
    void rejectsTruncatedImageEvenWhenTheHeaderAndDimensionsAreIntact(String format) throws Exception {
        byte[] original = image(format, 5, 4);
        var subject = images(2048, 32, 32, 1024);
        assertThatThrownBy(() -> subject.prepare(part("fixture", Arrays.copyOf(original, original.length - 2))))
                .satisfies(error("INVALID_ATTACHMENT"));
        assertThatThrownBy(() -> subject.prepare(part("fixture", Arrays.copyOf(original, original.length / 2))))
                .satisfies(error("INVALID_ATTACHMENT"));
    }

    @ParameterizedTest
    @CsvSource({"8,12,144", "12,8,144", "12,12,80"})
    void rejectsWidthHeightAndPixelBombsBeforeImageDecode(int width, int height, long pixels) throws Exception {
        var file = part("fixture.png", image("png", 10, 10));
        assertThatThrownBy(() -> images(2048, width, height, pixels).prepare(file)).satisfies(error("INVALID_ATTACHMENT"));
    }

    @Test
    void rejectsBadPngCrcAndFileOverByteLimit() throws Exception {
        byte[] original = image("png", 4, 4);
        original[original.length - 1] ^= 1;
        var file = part("fixture.png", original);
        assertThatThrownBy(() -> images(original.length - 1, 32, 32, 1024).prepare(file))
                .satisfies(error("PAYLOAD_TOO_LARGE"));
        assertThatThrownBy(() -> images(original.length, 32, 32, 1024).prepare(file)).satisfies(error("INVALID_ATTACHMENT"));
    }

    @Test
    void rejectsPngWithChunksBeforeHeaderOrEndBeforeImageData() throws Exception {
        byte[] original = image("png", 4, 4);
        int signatureBytes = 8;
        int headerEnd = signatureBytes + 25;
        int endChunkBytes = 12;
        int endStart = original.length - endChunkBytes;
        byte[] dataBeforeHeader = ByteBuffer.allocate(original.length)
                .put(original, 0, signatureBytes)
                .put(original, headerEnd, endStart - headerEnd)
                .put(original, signatureBytes, headerEnd - signatureBytes)
                .put(original, endStart, endChunkBytes).array();
        byte[] endBeforeData = ByteBuffer.allocate(original.length)
                .put(original, 0, headerEnd)
                .put(original, endStart, endChunkBytes)
                .put(original, headerEnd, endStart - headerEnd).array();

        var subject = images(2048, 32, 32, 1024);
        assertThatThrownBy(() -> subject.prepare(part("fixture.png", dataBeforeHeader)))
                .satisfies(error("INVALID_ATTACHMENT"));
        assertThatThrownBy(() -> subject.prepare(part("fixture.png", endBeforeData)))
                .satisfies(error("INVALID_ATTACHMENT"));
    }

    @Test
    void rejectsImageThatExceedsByteLimitDuringReencoding() throws Exception {
        var gray = new BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY);
        var input = new ByteArrayOutputStream();
        assertThat(ImageIO.write(gray, "jpeg", input)).isTrue();
        var upload = part("gray.jpg", input.toByteArray());
        var reencoded = images(2048, 32, 32, 1024).prepare(upload);
        assertThat(reencoded.bytes().length).isGreaterThan(input.size());

        assertThatThrownBy(() -> images(input.size(), 32, 32, 1024).prepare(upload))
                .satisfies(error("PAYLOAD_TOO_LARGE"));
        assertThat(images(reencoded.bytes().length, 32, 32, 1024).prepare(upload).bytes())
                .hasSameSizeAs(reencoded.bytes());
    }

    @Test
    void rejectsOversizedEncoderWritesBeforeFlushing() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new AttachmentImages.BoundedImageOutputStream(bytes, 4)) {
            output.write(new byte[3]);
            assertThatThrownBy(() -> output.write(new byte[2])).satisfies(error("PAYLOAD_TOO_LARGE"));
            assertThat(output.getStreamPosition()).isEqualTo(3);
            output.write(1);
            assertThatThrownBy(() -> output.write(2)).satisfies(error("PAYLOAD_TOO_LARGE"));
            assertThat(bytes.size()).isZero();
        }
        assertThat(bytes.toByteArray()).containsExactly(0, 0, 0, 1);
    }

    @Test
    void sanitizesBasenameAndCapsByUnicodeCodePoints() throws Exception {
        var subject = images(2048, 32, 32, 1024);
        var png = image("png", 4, 4);
        assertThat(subject.prepare(part("../fake\\  na" + (char) 0 + "me.png\r\n ", png)).filename()).isEqualTo("name.png");
        assertThat(subject.prepare(part("folder/\r\n ", png)).filename()).isEqualTo("image.png");
        assertThat(subject.prepare(part("📷".repeat(121), png)).filename()).isEqualTo("📷".repeat(120));
        assertThat(subject.prepare(part("", image("jpeg", 4, 4))).filename()).isEqualTo("image.jpg");
    }

    @Test
    void storageRejectsPathsOutsideItsKeyFormat() {
        var subject = new AttachmentStorage(properties(2048, 32, 32, 1024), "test-db");
        for (String key : new String[]{"../private.png", "..\\private.jpg", "/root/image.jpg", "a".repeat(32) + ".svg"}) {
            assertThatThrownBy(() -> subject.read(key)).satisfies(error("TEMPORARILY_UNAVAILABLE"));
            assertThatThrownBy(() -> subject.deleteAfterCommit(key)).satisfies(error("TEMPORARILY_UNAVAILABLE"));
        }
    }

    @Test
    void rejectsNonPositiveConfigurationAtStartup() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(AttachmentStorage.class)
                .withPropertyValues("fixcampus.attachments.max-pixels=0")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Attachment limits must be positive."));
    }

    private AttachmentImages images(int bytes, int width, int height, long pixels) {
        return new AttachmentImages(properties(bytes, width, height, pixels));
    }

    private AttachmentProperties properties(int bytes, int width, int height, long pixels) {
        return new AttachmentProperties(storage, bytes, width, height, pixels, 3, 2, 4096);
    }

    private static MockMultipartFile part(String name, byte[] data) {
        return new MockMultipartFile("file", name, "text/html", data);
    }

    private static java.util.function.Consumer<Throwable> error(String code) {
        return error -> assertThat(error).isInstanceOfSatisfying(ApiProblem.class,
                problem -> assertThat(problem.code()).isEqualTo(code));
    }
}
