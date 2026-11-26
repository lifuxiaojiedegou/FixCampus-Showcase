package com.fixcampus.api.v1;

import java.net.URI;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.AttachmentImages;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/v1")
@Profile({"web-slice", "demo", "prod"})
public class OrderAttachmentsApi {
    private static final String STUDENT = "/orders/{id}/attachments";
    private static final String WORKER = "/worker" + STUDENT;
    private static final String ADMIN = "/admin" + STUDENT;
    private static final String CONTENT = "/{aid}/content";

    private final OrderAttachments attachments;
    private final AttachmentImages images;

    public OrderAttachmentsApi(OrderAttachments attachments, AttachmentImages images) {
        this.attachments = attachments;
        this.images = images;
    }

    @GetMapping({STUDENT, WORKER, ADMIN})
    public OrderAttachments.OrderAttachmentListResponse list(@AuthenticationPrincipal CampusPrincipal actor,
                                                             @PathVariable long id) {
        return attachments.list(actor, id);
    }

    @PostMapping(value = {STUDENT, WORKER}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OrderAttachments.OrderAttachmentItem> upload(@AuthenticationPrincipal CampusPrincipal actor,
            @PathVariable long id, MultipartHttpServletRequest request) {
        var parts = request.getMultiFileMap();
        var files = parts.get("file");
        if (parts.size() != 1 || files == null || files.size() != 1) throw AttachmentImages.invalid();
        var item = attachments.upload(actor, id, images.prepare(files.getFirst()));
        return ResponseEntity.created(URI.create(item.contentUrl())).body(item);
    }

    @DeleteMapping(STUDENT + "/{aid}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable long id,
                                       @PathVariable long aid) {
        attachments.delete(actor, id, aid);
        return ResponseEntity.noContent().build();
    }

    @GetMapping({STUDENT + CONTENT, WORKER + CONTENT, ADMIN + CONTENT})
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal CampusPrincipal actor, @PathVariable long id,
                                          @PathVariable long aid, HttpServletResponse response) {
        var image = attachments.content(actor, id, aid);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.bytes().length)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"attachment-" + aid + "." + AttachmentImages.extension(image.contentType()) + "\"")
                .body(image.bytes());
    }
}
