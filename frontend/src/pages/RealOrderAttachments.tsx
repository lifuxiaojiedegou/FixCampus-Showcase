import { useEffect, useRef, useState } from 'react';
import type {
  MeResponse,
  OrderAttachmentItem,
  OrderAttachmentListResponse,
  OrderStatus,
} from '../shared/api/contracts';
import { RestProblem, StudentClient } from '../shared/api/rest/studentClient';
import { Button, FormField, Notice } from '../shared/components/ui';
import { formatDate } from '../shared/utils/formatDate';
import { RealErrorNotice } from './RealErrorNotice';
import styles from './pages.module.css';

const ALLOWED_TYPES = new Set(['image/png', 'image/jpeg']);

function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / (1024 * 1024)).toFixed(2)} MiB`;
  }
  if (bytes >= 1024) {
    return `${(bytes / 1024).toFixed(1)} KiB`;
  }
  return `${bytes} B`;
}

function isSafeAttachmentUrl(url: string): boolean {
  return /^\/api\/v1\/(?:(?:admin|worker)\/)?orders\/[1-9]\d*\/attachments\/[1-9]\d*\/content$/.test(
    url,
  );
}

function roleStatusAllowsUpload(
  role: MeResponse['role'],
  status: OrderStatus,
): boolean {
  if (role === 'STUDENT') {
    return status === 'PENDING_ASSIGNMENT' || status === 'PENDING_REPAIR';
  }
  if (role === 'WORKER') {
    return status === 'IN_PROGRESS';
  }
  return false;
}

function roleStatusAllowsDelete(
  role: MeResponse['role'],
  status: OrderStatus,
): boolean {
  return role === 'STUDENT' && status === 'PENDING_ASSIGNMENT';
}

export function RealOrderAttachments({
  client,
  orderId,
  role,
  orderStatus,
  reloadSeq,
  writesBlocked,
  onAuthBlocked,
}: {
  client: StudentClient;
  orderId: string;
  role: MeResponse['role'];
  orderStatus: OrderStatus;
  reloadSeq: number;
  writesBlocked: boolean;
  onAuthBlocked: (csrfAt: number | null) => void;
}) {
  const [data, setData] = useState<OrderAttachmentListResponse | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [refreshWarning, setRefreshWarning] = useState<unknown>(null);
  const [validationError, setValidationError] = useState('');
  const [success, setSuccess] = useState('');
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [outcomeUnknown, setOutcomeUnknown] = useState(false);
  const [conflictKind, setConflictKind] = useState<'state' | 'quota' | null>(
    null,
  );
  const [preview, setPreview] = useState<OrderAttachmentItem | null>(null);
  const [brokenIds, setBrokenIds] = useState<Record<string, boolean>>({});
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [reloadCount, setReloadCount] = useState(0);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const previewRef = useRef<HTMLDivElement>(null);
  const previewTriggerRef = useRef<HTMLButtonElement | null>(null);
  const listSeqRef = useRef(0);
  const lockRef = useRef(false);
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (preview) {
      previewRef.current?.focus();
    }
  }, [preview]);

  useEffect(() => {
    const seq = ++listSeqRef.current;
    void client
      .listAttachments(orderId)
      .then((res) => {
        if (!aliveRef.current || seq !== listSeqRef.current) return;
        setData(res);
        setLoadError(null);
        setRefreshWarning(null);
        setOutcomeUnknown(false);
        setConflictKind(null);
        setBrokenIds({});
        setPreview((current) =>
          current
            ? (res.items.find((item) => item.id === current.id) ?? null)
            : null,
        );
      })
      .catch((err) => {
        if (!aliveRef.current || seq !== listSeqRef.current) return;
        setData(null);
        setPreview(null);
        setConfirmDeleteId(null);
        setSelectedFile(null);
        setValidationError('');
        setLoadError(err);
      });
    return () => {
      if (listSeqRef.current === seq) {
        listSeqRef.current += 1;
      }
    };
  }, [client, orderId, role, orderStatus, reloadSeq, reloadCount]);

  function clearFileSelection() {
    setSelectedFile(null);
    setValidationError('');
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  }

  function openPreview(
    item: OrderAttachmentItem,
    trigger: HTMLButtonElement | null,
  ) {
    previewTriggerRef.current = trigger;
    setPreview(item);
  }

  function closePreview() {
    setPreview(null);
    previewTriggerRef.current?.focus();
  }

  function triggerReload() {
    clearFileSelection();
    setData(null);
    setPreview(null);
    setBrokenIds({});
    setLoadError(null);
    setActionError(null);
    setRefreshWarning(null);
    setSuccess('');
    setConfirmDeleteId(null);
    setReloadCount((n) => n + 1);
  }

  const canUploadNow =
    Boolean(data?.canUpload) && roleStatusAllowsUpload(role, orderStatus);
  const uploadBlocked =
    writesBlocked || outcomeUnknown || conflictKind !== null;
  const deleteBlocked =
    writesBlocked || outcomeUnknown || conflictKind === 'state';

  async function handleUpload(e: React.FormEvent) {
    e.preventDefault();
    if (lockRef.current || uploadBlocked || !data || !canUploadNow) {
      return;
    }
    setValidationError('');
    setActionError(null);
    setRefreshWarning(null);
    setSuccess('');

    if (!selectedFile) {
      setValidationError('请先选择一张 PNG 或 JPEG 图片。');
      fileInputRef.current?.focus();
      return;
    }
    if (selectedFile.size <= 0) {
      setValidationError('所选图片为空，请重新选择有效图片。');
      fileInputRef.current?.focus();
      return;
    }
    if (selectedFile.size > data.limits.maxFileBytes) {
      setValidationError(
        `图片大小不能超过 ${formatBytes(data.limits.maxFileBytes)}。`,
      );
      fileInputRef.current?.focus();
      return;
    }
    if (selectedFile.type && !ALLOWED_TYPES.has(selectedFile.type)) {
      setValidationError('仅支持 PNG 或 JPEG 格式图片。');
      fileInputRef.current?.focus();
      return;
    }

    lockRef.current = true;
    const confirmationBefore = client.writeConfirmation() ?? 0;
    const fileToUpload = selectedFile;
    setBusy(true);
    try {
      const created = await client.uploadAttachment(orderId, fileToUpload);
      if (!aliveRef.current) return;

      // Immediately update local state and clear selection before refreshing the list.
      clearFileSelection();
      setData((current) => {
        if (!current) return current;
        const nextItems = current.items.some((i) => i.id === created.id)
          ? current.items
          : [...current.items, created];
        const ownCount = nextItems.filter(
          (i) => i.uploaderRole === role,
        ).length;
        const roleLimit =
          role === 'WORKER'
            ? current.limits.maxWorkerAttachments
            : current.limits.maxStudentAttachments;
        return {
          ...current,
          items: nextItems,
          canUpload:
            roleStatusAllowsUpload(role, orderStatus) && ownCount < roleLimit,
        };
      });
      setSuccess('图片已上传并更新附件列表。');

      const seq = ++listSeqRef.current;
      try {
        const latest = await client.listAttachments(orderId);
        if (aliveRef.current && seq === listSeqRef.current) {
          setData(latest);
          setLoadError(null);
          setRefreshWarning(null);
        }
      } catch (refreshErr) {
        if (aliveRef.current && seq === listSeqRef.current) {
          setRefreshWarning(refreshErr);
        }
      }
    } catch (err) {
      if (aliveRef.current) {
        setActionError(err);
        if (!(err instanceof RestProblem)) {
          setOutcomeUnknown(true);
        } else if (err.problem.status === 409) {
          setConflictKind(
            err.problem.code === 'ATTACHMENT_QUOTA_EXCEEDED'
              ? 'quota'
              : 'state',
          );
        }
        if (
          (err instanceof RestProblem &&
            err.problem.code === 'CSRF_REJECTED') ||
          client.writeConfirmation() === null
        ) {
          onAuthBlocked(confirmationBefore);
        }
      }
    } finally {
      lockRef.current = false;
      if (aliveRef.current) setBusy(false);
    }
  }

  async function handleDelete(attachmentId: string) {
    if (lockRef.current || deleteBlocked) return;
    lockRef.current = true;
    const confirmationBefore = client.writeConfirmation() ?? 0;
    setBusy(true);
    setActionError(null);
    setRefreshWarning(null);
    setSuccess('');
    try {
      await client.deleteAttachment(orderId, attachmentId);
      if (!aliveRef.current) return;

      // Immediately remove deleted attachment locally before refreshing from server.
      setConfirmDeleteId(null);
      setPreview((current) => (current?.id === attachmentId ? null : current));
      setData((current) => {
        if (!current) return current;
        const nextItems = current.items.filter((i) => i.id !== attachmentId);
        const ownCount = nextItems.filter(
          (i) => i.uploaderRole === role,
        ).length;
        const roleLimit =
          role === 'WORKER'
            ? current.limits.maxWorkerAttachments
            : current.limits.maxStudentAttachments;
        return {
          ...current,
          items: nextItems,
          canUpload:
            roleStatusAllowsUpload(role, orderStatus) && ownCount < roleLimit,
        };
      });
      setSuccess('附件已删除。');

      const seq = ++listSeqRef.current;
      try {
        const latest = await client.listAttachments(orderId);
        if (aliveRef.current && seq === listSeqRef.current) {
          setData(latest);
          setLoadError(null);
          setRefreshWarning(null);
        }
      } catch (refreshErr) {
        if (aliveRef.current && seq === listSeqRef.current) {
          setRefreshWarning(refreshErr);
        }
      }
    } catch (err) {
      if (aliveRef.current) {
        setActionError(err);
        if (!(err instanceof RestProblem)) {
          setOutcomeUnknown(true);
        } else if (err.problem.status === 409) {
          setConflictKind(
            err.problem.code === 'ATTACHMENT_QUOTA_EXCEEDED'
              ? 'quota'
              : 'state',
          );
        }
        if (
          (err instanceof RestProblem &&
            err.problem.code === 'CSRF_REJECTED') ||
          client.writeConfirmation() === null
        ) {
          onAuthBlocked(confirmationBefore);
        }
      }
    } finally {
      lockRef.current = false;
      if (aliveRef.current) setBusy(false);
    }
  }

  return (
    <section
      className={styles.attachmentsSection}
      aria-labelledby="real-attachments-heading"
    >
      <div className={styles.attachmentsHeader}>
        <h3 id="real-attachments-heading">图片附件</h3>
        <Button variant="secondary" disabled={busy} onClick={triggerReload}>
          刷新附件
        </Button>
      </div>

      {loadError !== null && <RealErrorNotice error={loadError} />}
      {actionError !== null && <RealErrorNotice error={actionError} />}
      {success && <Notice>{success}</Notice>}
      {refreshWarning !== null && (
        <>
          <Notice>
            操作已成功，但重新读取附件列表失败，请点击「刷新附件」核对最新状态。
          </Notice>
          <RealErrorNotice error={refreshWarning} />
        </>
      )}
      {outcomeUnknown && (
        <Notice>
          附件提交结果未知，未自动重试。请先点击「刷新附件」核对列表后再操作。
        </Notice>
      )}
      {conflictKind === 'state' && (
        <Notice>
          工单或附件状态已变化，已暂停继续提交。请先点击「刷新附件」或「重新读取工单状态」核对最新权限。
        </Notice>
      )}
      {conflictKind === 'quota' && (
        <Notice>
          系统附件存储总配额已满，已暂停继续上传。请先清理无用附件或联系管理员释放空间，再点击「刷新附件」核对后重试。
        </Notice>
      )}
      {!data && loadError === null && <Notice>正在读取附件列表…</Notice>}

      {data && (
        <>
          <p className={styles.attachmentsHint}>
            支持 PNG / JPEG，单张上限 {formatBytes(data.limits.maxFileBytes)}，
            尺寸上限 {data.limits.maxWidth}×{data.limits.maxHeight} px；
            学生最多 {data.limits.maxStudentAttachments} 张，维修员最多{' '}
            {data.limits.maxWorkerAttachments} 张。
          </p>

          {canUploadNow && (
            <form
              aria-label="上传图片附件"
              className={styles.attachmentUploadForm}
              onSubmit={(e) => void handleUpload(e)}
            >
              <div className={styles.attachmentFileField}>
                <FormField
                  id="real-attachment-file"
                  label={
                    role === 'WORKER' ? '上传维修结果照片' : '上传报修现场照片'
                  }
                  hint="仅限 PNG 或 JPEG 图片，上传后将自动清理 EXIF 元数据。"
                  error={validationError}
                >
                  <input
                    ref={fileInputRef}
                    id="real-attachment-file"
                    type="file"
                    accept="image/png,image/jpeg"
                    disabled={busy || uploadBlocked}
                    aria-invalid={!!validationError}
                    aria-describedby={
                      validationError
                        ? 'real-attachment-file-hint real-attachment-file-error'
                        : 'real-attachment-file-hint'
                    }
                    onChange={(e) => {
                      const next = e.target.files?.[0] ?? null;
                      setSelectedFile(next);
                      setValidationError('');
                      setActionError(null);
                      setRefreshWarning(null);
                      setSuccess('');
                    }}
                  />
                </FormField>
              </div>
              <Button
                type="submit"
                disabled={busy || uploadBlocked || !selectedFile}
              >
                {busy ? '正在上传…' : '上传图片'}
              </Button>
            </form>
          )}

          {data.items.length === 0 ? (
            <Notice>暂无图片附件</Notice>
          ) : (
            <ul className={styles.attachmentGrid}>
              {data.items.map((item) => {
                const safeUrl = isSafeAttachmentUrl(item.contentUrl)
                  ? item.contentUrl
                  : '';
                const isWorker = item.uploaderRole === 'WORKER';
                const canDeleteItem =
                  item.canDelete && roleStatusAllowsDelete(role, orderStatus);
                const isBroken = Boolean(brokenIds[item.id]);
                return (
                  <li key={item.id} className={styles.attachmentCard}>
                    {safeUrl && (
                      <button
                        type="button"
                        className={styles.attachmentThumbBtn}
                        aria-label={`查看大图：${item.originalFilename}`}
                        onClick={(e) => openPreview(item, e.currentTarget)}
                      >
                        {isBroken ? (
                          <span
                            role="status"
                            className={styles.attachmentBroken}
                          >
                            图片加载失败，请点击「刷新附件」重试
                          </span>
                        ) : (
                          <img
                            src={safeUrl}
                            alt={`${isWorker ? '维修结果图' : '报修现场图'}：${item.originalFilename}`}
                            loading="lazy"
                            className={styles.attachmentThumb}
                            onError={() =>
                              setBrokenIds((prev) => ({
                                ...prev,
                                [item.id]: true,
                              }))
                            }
                          />
                        )}
                      </button>
                    )}
                    <div className={styles.attachmentMeta}>
                      <span
                        className={
                          isWorker
                            ? `${styles.attachmentBadge} ${styles.attachmentBadgeWorker}`
                            : styles.attachmentBadge
                        }
                      >
                        {isWorker ? '维修结果图' : '报修现场图'}
                      </span>
                      <span className={styles.attachmentFilename}>
                        {item.originalFilename}
                      </span>
                      <span>
                        {item.width}×{item.height} px ·{' '}
                        {formatBytes(item.sizeBytes)}
                      </span>
                      <span>
                        {item.uploaderName} · {formatDate(item.createdAt)}
                      </span>
                    </div>
                    <div className={styles.attachmentActions}>
                      {safeUrl && (
                        <Button
                          variant="secondary"
                          onClick={(e) => openPreview(item, e.currentTarget)}
                        >
                          查看大图
                        </Button>
                      )}
                      {canDeleteItem && confirmDeleteId !== item.id && (
                        <Button
                          variant="secondary"
                          disabled={busy || deleteBlocked}
                          onClick={() => setConfirmDeleteId(item.id)}
                        >
                          删除附件
                        </Button>
                      )}
                      {canDeleteItem && confirmDeleteId === item.id && (
                        <>
                          <Button
                            variant="secondary"
                            disabled={busy}
                            onClick={() => setConfirmDeleteId(null)}
                          >
                            取消
                          </Button>
                          <Button
                            disabled={busy || deleteBlocked}
                            onClick={() => void handleDelete(item.id)}
                          >
                            确认删除
                          </Button>
                        </>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}

          {preview && isSafeAttachmentUrl(preview.contentUrl) && (
            <div
              ref={previewRef}
              tabIndex={-1}
              role="region"
              aria-label={`附件大图预览：${preview.originalFilename}`}
              className={styles.attachmentPreview}
              onKeyDown={(e) => {
                if (e.key === 'Escape') {
                  e.preventDefault();
                  closePreview();
                }
              }}
            >
              <div className={styles.attachmentPreviewHeader}>
                <h4>
                  {preview.originalFilename}（{preview.width}×{preview.height}{' '}
                  px · {formatBytes(preview.sizeBytes)}）
                </h4>
                <Button variant="secondary" onClick={closePreview}>
                  关闭预览
                </Button>
              </div>
              {brokenIds[preview.id] ? (
                <Notice>
                  大图加载失败，请点击「刷新附件」或重新确认登录状态。
                </Notice>
              ) : (
                <img
                  src={preview.contentUrl}
                  alt={`大图预览：${preview.originalFilename}`}
                  className={styles.attachmentPreviewImg}
                  onError={() =>
                    setBrokenIds((prev) => ({
                      ...prev,
                      [preview.id]: true,
                    }))
                  }
                />
              )}
            </div>
          )}
        </>
      )}
    </section>
  );
}
