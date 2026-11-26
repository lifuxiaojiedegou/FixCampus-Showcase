import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import type {
  NotificationEventType,
  NotificationPageResponse,
} from '../shared/api/contracts';
import {
  RestProblem,
  type StudentClient,
} from '../shared/api/rest/studentClient';
import { Button, Notice, Surface } from '../shared/components/ui';
import { formatDate } from '../shared/utils/formatDate';
import { RealErrorNotice } from './RealErrorNotice';
import styles from './pages.module.css';

const EVENT_LABELS: Record<NotificationEventType, string> = {
  ORDER_SUBMITTED: '新报修',
  ORDER_ASSIGNED: '已派单',
  REPAIR_STARTED: '开始维修',
  REPAIR_COMPLETED: '维修完工',
  ORDER_CONFIRMED: '已确认',
  ORDER_CANCELLED: '已取消',
};

export function RealNotifications({
  client,
  unreadCount,
  confirmSeq = 0,
  getUnreadWriteVersion,
  onReadUnreadCount,
  onWriteUnreadCount,
}: {
  client: StudentClient;
  unreadCount: number | null;
  confirmSeq?: number;
  getUnreadWriteVersion: () => number;
  onReadUnreadCount: (count: number, startedWriteVersion: number) => void;
  onWriteUnreadCount: (count: number) => void;
}) {
  void confirmSeq;
  const [page, setPage] = useState(0);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [data, setData] = useState<NotificationPageResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [outcomeUnknown, setOutcomeUnknown] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [markingAll, setMarkingAll] = useState(false);
  const [reloadSeq, setReloadSeq] = useState(0);
  const writesBlocked = client.writeConfirmation() === null || outcomeUnknown;
  const seqRef = useRef(0);
  const unknownOutcomeSeqRef = useRef(0);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const restoreFocusRef = useRef(false);

  useEffect(() => {
    const seq = ++seqRef.current;
    const startedWriteVersion = getUnreadWriteVersion();
    let active = true;
    void client
      .listNotifications(page, unreadOnly)
      .then((res) => {
        if (
          !active ||
          seq !== seqRef.current ||
          startedWriteVersion < getUnreadWriteVersion()
        ) {
          return;
        }
        onReadUnreadCount(res.unreadCount, startedWriteVersion);
        if (
          res.items.length === 0 &&
          res.totalPages > 0 &&
          page >= res.totalPages
        ) {
          setPage(res.totalPages - 1);
          return;
        }
        setData(res);
        setLoadError(null);
        if (seq > unknownOutcomeSeqRef.current) {
          setOutcomeUnknown(false);
        }
      })
      .catch((e) => {
        if (
          !active ||
          seq !== seqRef.current ||
          startedWriteVersion < getUnreadWriteVersion()
        ) {
          return;
        }
        setLoadError(e);
      })
      .finally(() => {
        if (active && seq === seqRef.current) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [
    client,
    getUnreadWriteVersion,
    onReadUnreadCount,
    page,
    unreadOnly,
    reloadSeq,
  ]);

  useEffect(() => {
    if (!restoreFocusRef.current || loading) return;
    restoreFocusRef.current = false;
    if (document.activeElement && document.activeElement !== document.body) {
      return;
    }
    const nextFocusable = listRef.current?.querySelector<HTMLElement>(
      'a, button:not(:disabled)',
    );
    (nextFocusable ?? headingRef.current)?.focus();
  }, [data, loading]);

  const switchFilter = (nextUnreadOnly: boolean) => {
    if (nextUnreadOnly === unreadOnly) return;
    setActionError(null);
    setLoadError(null);
    setFeedback(null);
    setLoading(true);
    setPage(0);
    setUnreadOnly(nextUnreadOnly);
  };

  const markRead = async (id: string) => {
    if (busyId || markingAll || writesBlocked) return;
    setBusyId(id);
    setActionError(null);
    setFeedback(null);
    try {
      const res = await client.markNotificationRead(id);
      onWriteUnreadCount(res.unreadCount);
      restoreFocusRef.current = true;
      setOutcomeUnknown(false);
      setFeedback('已标为已读。');
      setLoading(true);
      setReloadSeq((n) => n + 1);
    } catch (e) {
      setActionError(e);
      if (!(e instanceof RestProblem)) {
        unknownOutcomeSeqRef.current = seqRef.current;
        setOutcomeUnknown(true);
      }
    } finally {
      setBusyId(null);
    }
  };

  const markAllRead = async () => {
    if (busyId || markingAll || writesBlocked) return;
    setMarkingAll(true);
    setActionError(null);
    setFeedback(null);
    try {
      const res = await client.markAllNotificationsRead();
      onWriteUnreadCount(res.unreadCount);
      restoreFocusRef.current = true;
      setOutcomeUnknown(false);
      setFeedback(
        res.markedCount > 0
          ? `已将 ${res.markedCount} 条通知标为已读。`
          : '当前没有未读通知。',
      );
      setLoading(true);
      setPage(0);
      setReloadSeq((n) => n + 1);
    } catch (e) {
      setActionError(e);
      if (!(e instanceof RestProblem)) {
        unknownOutcomeSeqRef.current = seqRef.current;
        setOutcomeUnknown(true);
      }
    } finally {
      setMarkingAll(false);
    }
  };

  const totalPages = data?.totalPages ?? 0;
  const effectiveUnreadCount = unreadCount ?? data?.unreadCount ?? 0;

  return (
    <Surface>
      <div className={styles.notificationHeaderBar}>
        <div>
          <h2 ref={headingRef} tabIndex={-1}>
            站内通知
          </h2>
          <p className={styles.note}>
            当前未读：{effectiveUnreadCount}{' '}
            条（仅展示当前登录账号的工单流转通知）
          </p>
        </div>
        <div className={styles.notificationToolbar}>
          <Button
            variant={!unreadOnly ? 'primary' : 'secondary'}
            aria-pressed={!unreadOnly}
            onClick={() => switchFilter(false)}
          >
            全部通知
          </Button>
          <Button
            variant={unreadOnly ? 'primary' : 'secondary'}
            aria-pressed={unreadOnly}
            onClick={() => switchFilter(true)}
          >
            仅看未读
          </Button>
          <Button
            variant="secondary"
            disabled={loading || markingAll || busyId !== null}
            onClick={() => {
              setActionError(null);
              setLoadError(null);
              setFeedback(null);
              setLoading(true);
              setReloadSeq((n) => n + 1);
            }}
          >
            刷新列表
          </Button>
          <Button
            variant="secondary"
            disabled={
              effectiveUnreadCount === 0 ||
              markingAll ||
              busyId !== null ||
              writesBlocked
            }
            onClick={() => void markAllRead()}
          >
            {markingAll ? '处理中…' : '全部标为已读'}
          </Button>
        </div>
      </div>

      {feedback && <Notice>{feedback}</Notice>}
      {outcomeUnknown && (
        <Notice>
          通知状态结果未知，未自动重试。请点击「刷新列表」核对最新未读状态。
        </Notice>
      )}
      {actionError !== null && (
        <RealErrorNotice error={actionError} scope="notification" />
      )}
      {loadError !== null && (
        <RealErrorNotice error={loadError} scope="notification" />
      )}

      {loading && !data ? (
        <Notice>正在加载站内通知…</Notice>
      ) : data && data.items.length === 0 ? (
        <>
          <Notice>
            {unreadOnly ? '当前没有未读通知。' : '当前暂无站内通知。'}
          </Notice>
          {page > 0 && (
            <div className={styles.realPagination}>
              <Button
                variant="secondary"
                disabled={loading}
                onClick={() => {
                  setLoading(true);
                  setPage(0);
                }}
              >
                返回第一页
              </Button>
            </div>
          )}
        </>
      ) : data ? (
        <>
          <ul ref={listRef} className={styles.notificationList}>
            {data.items.map((item) => (
              <li
                key={item.id}
                className={
                  item.read
                    ? styles.notificationItem
                    : `${styles.notificationItem} ${styles.notificationUnread}`
                }
              >
                <div className={styles.notificationItemHead}>
                  <div className={styles.notificationTitleGroup}>
                    <span
                      className={
                        item.read
                          ? styles.notificationBadgeRead
                          : styles.notificationBadgeUnread
                      }
                    >
                      {item.read ? '已读' : '未读'}
                    </span>
                    <span className={styles.notificationEventTag}>
                      {EVENT_LABELS[item.eventType]}
                    </span>
                    <strong className={styles.notificationTitle}>
                      {item.title}
                    </strong>
                  </div>
                  <time className={styles.notificationTime}>
                    {formatDate(item.createdAt)}
                  </time>
                </div>
                <p className={styles.notificationBody}>{item.body}</p>
                <div className={styles.notificationActions}>
                  <Link
                    to={`/orders/${item.orderId}`}
                    className={styles.notificationOrderLink}
                  >
                    查看工单 #{item.orderId}
                  </Link>
                  {!item.read ? (
                    <Button
                      variant="secondary"
                      aria-label={`将「${item.title}」（工单 #${item.orderId}）标为已读`}
                      disabled={
                        busyId === item.id || markingAll || writesBlocked
                      }
                      onClick={() => void markRead(item.id)}
                    >
                      {busyId === item.id ? '标记中…' : '标为已读'}
                    </Button>
                  ) : (
                    <span className={styles.notificationReadMeta}>
                      已读于 {formatDate(item.readAt)}
                    </span>
                  )}
                </div>
              </li>
            ))}
          </ul>
          {totalPages > 1 && (
            <div className={styles.realPagination}>
              <Button
                variant="secondary"
                disabled={page === 0 || loading}
                onClick={() => {
                  setLoading(true);
                  setPage((p) => Math.max(0, p - 1));
                }}
              >
                上一页
              </Button>
              <span>
                第 {page + 1} / {totalPages} 页（共 {data.totalElements} 条）
              </span>
              <Button
                variant="secondary"
                disabled={page + 1 >= totalPages || loading}
                onClick={() => {
                  setLoading(true);
                  setPage((p) => p + 1);
                }}
              >
                下一页
              </Button>
            </div>
          )}
        </>
      ) : null}
    </Surface>
  );
}
