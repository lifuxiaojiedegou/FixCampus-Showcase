package com.fixcampus.api.v1;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;
import com.fixcampus.config.AttachmentProperties;
import com.fixcampus.entity.RepairOrder;
import com.fixcampus.entity.Role;
import com.fixcampus.exception.OrderConflictException;
import com.fixcampus.exception.OrderNotFoundException;
import com.fixcampus.repository.OrderAttachmentRepository;
import com.fixcampus.repository.OrderAttachmentRepository.Attachment;
import com.fixcampus.repository.RepairOrderRepository;
import com.fixcampus.security.CampusPrincipal;
import com.fixcampus.service.AttachmentImages;
import com.fixcampus.service.AttachmentStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static com.fixcampus.entity.RepairStatus.*;

@Service
public class OrderAttachments {
    private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");
    private final RepairOrderRepository orders;
    private final OrderAttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final AttachmentProperties properties;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public OrderAttachments(RepairOrderRepository orders, OrderAttachmentRepository attachments,
                            AttachmentStorage storage, AttachmentProperties properties, Clock clock,
                            PlatformTransactionManager transactions) {
        this.orders = orders;
        this.attachments = attachments;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
        writeTransaction = new TransactionTemplate(transactions);
        writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTransaction = new TransactionTemplate(transactions);
        readTransaction.setReadOnly(true);
        readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public OrderAttachmentListResponse list(CampusPrincipal actor, long orderId) {
        var order = visibleOrder(actor, orderId);
        var rows = attachments.findByOrderId(orderId);
        long ownRoleCount = rows.stream().filter(row -> row.uploaderRole() == actor.getRole()).count();
        var limits = new Limits(properties.maxFileBytes(), properties.maxWidth(), properties.maxHeight(),
                properties.maxPixels(), properties.maxStudentPerOrder(), properties.maxWorkerPerOrder(),
                AttachmentImages.CONTENT_TYPES);
        return new OrderAttachmentListResponse(Long.toString(orderId),
                rows.stream().map(row -> item(actor, order, row)).toList(), limits,
                uploadAllowed(actor.getRole(), order) && ownRoleCount < roleLimit(actor.getRole()));
    }

    public OrderAttachmentItem upload(CampusPrincipal actor, long orderId, AttachmentImages.Prepared image) {
        return writeVisibleOrder(actor, orderId, order -> {
            Role role = actor.getRole();
            if (!uploadAllowed(role, order)) throw new OrderConflictException();
            if (attachments.countByOrderIdAndUploaderRole(orderId, role) >= roleLimit(role)) {
                throw new ApiProblem(409, "ATTACHMENT_LIMIT_EXCEEDED", "当前工单的附件数量已达上限");
            }
            if (attachments.sumSizeBytes() > properties.maxTotalBytes() - image.bytes().length) {
                throw new ApiProblem(409, "ATTACHMENT_QUOTA_EXCEEDED", "附件存储空间已达上限");
            }
            String key = storage.store(image,
                    candidate -> readTransaction.execute(status -> attachments.existsByStorageKey(candidate)));
            var row = attachments.insert(orderId, actor.getId(), role, image.filename(), image.contentType(),
                    image.bytes().length, image.width(), image.height(), image.sha256(), key,
                    LocalDateTime.ofInstant(clock.instant(), HONG_KONG).truncatedTo(ChronoUnit.MICROS));
            return item(actor, order, row);
        });
    }

    public void delete(CampusPrincipal actor, long orderId, long attachmentId) {
        writeVisibleOrder(actor, orderId, order -> {
            var row = attachment(orderId, attachmentId);
            if (!deleteAllowed(actor, order, row)) throw new OrderConflictException();
            attachments.delete(attachmentId, orderId);
            storage.deleteAfterCommit(row.storageKey());
            return null;
        });
    }

    public Content content(CampusPrincipal actor, long orderId, long attachmentId) {
        var row = visibleAttachment(actor, orderId, attachmentId);
        var bytes = storage.read(row.storageKey()).orElseGet(() -> {
            visibleAttachment(actor, orderId, attachmentId);
            throw AttachmentStorage.unavailable();
        });
        return new Content(row.contentType(), bytes);
    }

    private Attachment visibleAttachment(CampusPrincipal actor, long orderId, long attachmentId) {
        return readTransaction.execute(status -> {
            visibleOrder(actor, orderId);
            return attachment(orderId, attachmentId);
        });
    }

    private <T> T writeVisibleOrder(CampusPrincipal actor, long orderId, Function<RepairOrder, T> write) {
        return storage.serializeWrite(() -> {
            // Keep the visibility query's entity and snapshot out of the locking transaction.
            readTransaction.executeWithoutResult(status -> visibleOrder(actor, orderId));
            return writeTransaction.execute(status -> write.apply(lockedOrder(actor, orderId)));
        });
    }

    private RepairOrder visibleOrder(CampusPrincipal actor, long orderId) {
        var order = switch (actor.getRole()) {
            case STUDENT -> orders.findForStudentById(orderId, actor.getId());
            case WORKER -> orders.findForWorkerById(orderId, actor.getId());
            case ADMIN -> orders.findById(orderId);
        };
        return order.orElseThrow(OrderNotFoundException::new);
    }

    private RepairOrder lockedOrder(CampusPrincipal actor, long orderId) {
        return orders.findByIdForUpdate(orderId).filter(order -> switch (actor.getRole()) {
            case STUDENT -> actor.getId().equals(order.getStudent().getId());
            case WORKER -> order.getWorker() != null && actor.getId().equals(order.getWorker().getId());
            case ADMIN -> false;
        }).orElseThrow(OrderNotFoundException::new);
    }

    private Attachment attachment(long orderId, long attachmentId) {
        return attachments.findByIdAndOrderId(attachmentId, orderId)
                .orElseThrow(() -> new ApiProblem(404, "ATTACHMENT_NOT_FOUND", "附件不存在或无权访问"));
    }

    private static boolean uploadAllowed(Role role, RepairOrder order) {
        return switch (role) {
            case STUDENT -> order.getStatus() == PENDING_ASSIGNMENT || order.getStatus() == PENDING_REPAIR;
            case WORKER -> order.getStatus() == IN_PROGRESS;
            case ADMIN -> false;
        };
    }

    private int roleLimit(Role role) {
        return switch (role) {
            case STUDENT -> properties.maxStudentPerOrder();
            case WORKER -> properties.maxWorkerPerOrder();
            case ADMIN -> 0;
        };
    }

    private static boolean deleteAllowed(CampusPrincipal actor, RepairOrder order, Attachment row) {
        return actor.getRole() == Role.STUDENT && order.getStatus() == PENDING_ASSIGNMENT
                && row.uploaderRole() == Role.STUDENT && actor.getId().equals(row.uploaderId());
    }

    private static OrderAttachmentItem item(CampusPrincipal actor, RepairOrder order, Attachment row) {
        String prefix = switch (actor.getRole()) {
            case STUDENT -> "/api/v1/orders/";
            case WORKER -> "/api/v1/worker/orders/";
            case ADMIN -> "/api/v1/admin/orders/";
        };
        String url = prefix + row.orderId() + "/attachments/" + row.id() + "/content";
        return new OrderAttachmentItem(Long.toString(row.id()), Long.toString(row.orderId()), row.uploaderRole(),
                row.uploaderName(), row.originalFilename(), row.contentType(), row.sizeBytes(), row.width(),
                row.height(), row.createdAt().atZone(HONG_KONG).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                url, deleteAllowed(actor, order, row));
    }

    public record Content(String contentType, byte[] bytes) { }

    public record OrderAttachmentItem(String id, String orderId, Role uploaderRole, String uploaderName,
                                      String originalFilename, String contentType, long sizeBytes, int width,
                                      int height, String createdAt, String contentUrl, boolean canDelete) { }

    public record OrderAttachmentListResponse(String orderId, List<OrderAttachmentItem> items, Limits limits,
                                              boolean canUpload) { }

    public record Limits(int maxFileBytes, int maxWidth, int maxHeight, long maxPixels, int maxStudentAttachments,
                         int maxWorkerAttachments, List<String> allowedContentTypes) { }
}
