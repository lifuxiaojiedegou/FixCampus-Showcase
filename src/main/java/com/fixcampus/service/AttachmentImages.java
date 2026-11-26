package com.fixcampus.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import com.fixcampus.api.v1.ApiProblem;
import com.fixcampus.config.AttachmentProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class AttachmentImages {
    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final List<String> CONTENT_TYPES = List.of(PNG, JPEG);
    private static final byte[] PNG_MAGIC = HexFormat.of().parseHex("89504e470d0a1a0a");
    private static final byte[] JPEG_MAGIC = HexFormat.of().parseHex("ffd8ff");
    private static final int PNG_HEADER = 0x49484452; // IHDR
    private static final int PNG_DATA = 0x49444154; // IDAT
    private static final int PNG_END = 0x49454e44; // IEND

    private final AttachmentProperties limits;
    private final Semaphore imageSlots = new Semaphore(2);

    public AttachmentImages(AttachmentProperties limits) {
        this.limits = limits;
    }

    public Prepared prepare(MultipartFile file) {
        if (file.isEmpty()) throw invalid();
        checkSize(file.getSize());
        try {
            imageSlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiProblem(503, "TEMPORARILY_UNAVAILABLE", "附件处理暂不可用");
        }
        try {
            return prepareImage(file);
        } finally {
            imageSlots.release();
        }
    }

    private Prepared prepareImage(MultipartFile file) {
        byte[] input;
        try (var stream = file.getInputStream()) {
            input = stream.readNBytes(limits.maxFileBytes());
            if (stream.read() != -1) throw tooLarge();
        } catch (IOException e) {
            throw invalid();
        }
        boolean png = startsWith(input, PNG_MAGIC);
        if (!png && !startsWith(input, JPEG_MAGIC)) throw invalid();

        byte[] stored;
        BufferedImage image;
        String format = png ? "png" : "jpeg";
        try {
            image = decode(input, format, png);
            if (!png && image.getType() != BufferedImage.TYPE_INT_RGB) {
                var rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = rgb.createGraphics();
                try {
                    graphics.drawImage(image, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                image = rgb;
            }
            var bytes = new ByteArrayOutputStream();
            try (var output = new BoundedImageOutputStream(bytes, limits.maxFileBytes())) {
                if (!ImageIO.write(image, format, output)) throw invalid();
            }
            stored = bytes.toByteArray();
        } catch (ApiProblem e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid();
        }
        String contentType = png ? PNG : JPEG;
        return new Prepared(filename(file.getOriginalFilename(), contentType), contentType, image.getWidth(),
                image.getHeight(), stored, sha256(stored));
    }

    private BufferedImage decode(byte[] bytes, String format, boolean png) throws IOException {
        // The PNG reader can return pixels even when the final chunk is missing or a CRC is bad.
        if (png) verifyPngChunks(bytes);
        var reader = ImageIO.getImageReadersByFormatName(format).next();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < 1 || width > limits.maxWidth() || height < 1 || height > limits.maxHeight()
                    || (long) width * height > limits.maxPixels()) throw invalid();
            var warned = new AtomicBoolean();
            reader.addIIOReadWarningListener((source, warning) -> warned.set(true));
            var image = reader.read(0);
            if (warned.get() || image == null || image.getWidth() != width || image.getHeight() != height) {
                throw invalid();
            }
            return image;
        } finally {
            reader.dispose();
        }
    }

    private static void verifyPngChunks(byte[] bytes) {
        var input = ByteBuffer.wrap(bytes).position(PNG_MAGIC.length);
        var crc = new CRC32();
        boolean first = true;
        boolean hasData = false;
        while (input.remaining() >= 12) {
            int size = input.getInt();
            if (size < 0 || size > input.remaining() - 8) throw invalid();
            int offset = input.position();
            int type = input.getInt();
            if (first && type != PNG_HEADER) throw invalid();
            first = false;
            crc.reset();
            crc.update(bytes, offset, size + 4);
            input.position(input.position() + size);
            if (crc.getValue() != Integer.toUnsignedLong(input.getInt())) throw invalid();
            if (type == PNG_DATA) hasData = true;
            if (type == PNG_END) {
                if (size != 0 || !hasData) throw invalid();
                return;
            }
        }
        throw invalid();
    }

    private static boolean startsWith(byte[] input, byte[] magic) {
        return input.length >= magic.length && Arrays.equals(input, 0, magic.length, magic, 0, magic.length);
    }

    private static String filename(String filename, String contentType) {
        if (filename == null) return "image." + extension(contentType);
        String name = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1)
                .replaceAll("\\p{Cc}", "").strip();
        if (name.isEmpty()) return "image." + extension(contentType);
        return name.substring(0, name.offsetByCodePoints(0, Math.min(120, name.codePointCount(0, name.length()))));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void checkSize(long size) {
        if (size > limits.maxFileBytes()) throw tooLarge();
    }

    static final class BoundedImageOutputStream extends MemoryCacheImageOutputStream {
        private final int maxBytes;

        BoundedImageOutputStream(OutputStream bytes, int maxBytes) {
            super(bytes);
            this.maxBytes = maxBytes;
        }

        // PNG encoders seek back to fill chunk lengths before flushing. Bound the cached writes too.
        @Override
        public void write(int value) throws IOException {
            checkWriteSize(1);
            super.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            checkWriteSize(length);
            super.write(bytes, offset, length);
        }

        private void checkWriteSize(int length) throws IOException {
            if (length > maxBytes - getStreamPosition()) throw tooLarge();
        }
    }

    public static String extension(String contentType) {
        return PNG.equals(contentType) ? "png" : "jpg";
    }

    public static ApiProblem invalid() {
        return new ApiProblem(400, "INVALID_ATTACHMENT", "请上传有效的 PNG 或 JPEG 图片");
    }

    public static ApiProblem tooLarge() {
        return new ApiProblem(413, "PAYLOAD_TOO_LARGE", "附件大小超出限制");
    }

    public record Prepared(String filename, String contentType, int width, int height, byte[] bytes, String sha256) { }
}
