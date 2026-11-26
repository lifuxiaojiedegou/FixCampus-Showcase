import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import type {
  MeResponse,
  OrderPageResponse,
  OrderStatus,
} from '../shared/api/contracts';
import {
  StudentClient,
  RestProblem,
  type ListFilter,
  type RealOrderDetail,
  type WorkerOption,
  type OrderOperation,
} from '../shared/api/rest/studentClient';
import {
  Button,
  FormField,
  Notice,
  PageHeading,
  StatusTag,
  Surface,
} from '../shared/components/ui';
import {
  categories,
  completeSchema,
  searchSchema,
} from '../shared/forms/schemas';
import { historyActionLabels, statuses } from '../shared/utils/orderStatus';
import { formatDate } from '../shared/utils/formatDate';
import { RealErrorNotice } from './RealErrorNotice';
import { RealOrderAttachments } from './RealOrderAttachments';
import styles from './pages.module.css';

const defaultFilter: ListFilter = { status: '', q: '', sort: 'createdAt,desc' };

export function RealList({
  client,
  role,
}: {
  client: StudentClient;
  role: MeResponse['role'];
}) {
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState(defaultFilter);
  const [draft, setDraft] = useState(defaultFilter);
  const [reload, setReload] = useState(0);
  const [error, setError] = useState('');
  const [loaded, setLoaded] = useState<{
    key: string;
    data: OrderPageResponse | null;
    error: unknown;
  } | null>(null);
  const requestKey = `${page}:${JSON.stringify(filter)}:${reload}`;

  useEffect(() => {
    let active = true;
    void client
      .list(page, filter)
      .then((d) => {
        if (active) setLoaded({ key: requestKey, data: d, error: null });
      })
      .catch((e) => {
        if (active) setLoaded({ key: requestKey, data: null, error: e });
      });
    return () => {
      active = false;
    };
  }, [client, page, filter, requestKey]);

  const data = loaded?.key === requestKey ? loaded.data : null;
  const loadError = loaded?.key === requestKey ? loaded.error : null;

  const pageTitle =
    role === 'ADMIN'
      ? '工单管理'
      : role === 'WORKER'
        ? '维修工作台'
        : '我的报修';
  const pageDescription =
    role === 'ADMIN'
      ? '查看全校报修工单并安排维修人员，跟进处理进度。'
      : role === 'WORKER'
        ? '查看分配给本人的维修任务，记录维修结果并上传现场照片。'
        : '查看我的报修记录，跟进处理进度并确认维修结果。';
  const listHeading =
    role === 'ADMIN'
      ? '全部工单'
      : role === 'WORKER'
        ? '我的维修任务'
        : '我的工单';
  const todoLabel =
    role === 'ADMIN' ? '待派单' : role === 'WORKER' ? '待处理' : '待我确认';
  const todoCount =
    data?.items.filter((item) =>
      role === 'ADMIN'
        ? item.status === 'PENDING_ASSIGNMENT'
        : role === 'WORKER'
          ? item.status === 'PENDING_REPAIR' || item.status === 'IN_PROGRESS'
          : item.status === 'PENDING_CONFIRMATION',
    ).length ?? 0;

  return (
    <>
      <PageHeading
        eyebrow="FIXCAMPUS / 校园报修"
        title={pageTitle}
        description={pageDescription}
      >
        {role === 'STUDENT' && (
          <Link className={styles.realPrimaryCta} to="/new">
            ＋ 提交报修
          </Link>
        )}
      </PageHeading>
      {data && (
        <Notice>
          本页{todoLabel} <strong>{todoCount}</strong> 项 · 共{' '}
          <strong>{data.totalElements}</strong>{' '}
          条记录，状态以服务端实时结果为准。
        </Notice>
      )}
      <Surface>
        <form
          aria-label="工单筛选"
          className={styles.filters}
          onSubmit={(e) => {
            e.preventDefault();
            const q = searchSchema.safeParse(draft.q);
            if (!q.success) {
              setError(q.error.issues[0].message);
              document.getElementById('real-search')?.focus();
              return;
            }
            setError('');
            setPage(0);
            setFilter({ ...draft, q: q.data });
            setReload((n) => n + 1);
          }}
        >
          <div>
            <label htmlFor="real-status">工单状态</label>
            <select
              id="real-status"
              value={draft.status}
              onChange={(e) =>
                setDraft({
                  ...draft,
                  status: e.target.value as OrderStatus | '',
                })
              }
            >
              <option value="">全部状态</option>
              {Object.entries(statuses).map(([key, value]) => (
                <option key={key} value={key}>
                  {value.label}
                </option>
              ))}
            </select>
          </div>
          <div className={styles.search}>
            <label htmlFor="real-search">工单编号或地点</label>
            <input
              id="real-search"
              value={draft.q}
              placeholder="搜索地点或 #1001"
              aria-invalid={!!error}
              aria-describedby={error ? 'real-search-error' : undefined}
              onChange={(e) => setDraft({ ...draft, q: e.target.value })}
            />
          </div>
          <div>
            <label htmlFor="real-sort">排序</label>
            <select
              id="real-sort"
              value={draft.sort}
              onChange={(e) =>
                setDraft({
                  ...draft,
                  sort: e.target.value as ListFilter['sort'],
                })
              }
            >
              <option value="createdAt,desc">最新在前</option>
              <option value="createdAt,asc">最早在前</option>
            </select>
          </div>
          <Button type="submit">应用筛选</Button>
          <Button
            variant="secondary"
            onClick={() => {
              setError('');
              setDraft(defaultFilter);
              setFilter(defaultFilter);
              setPage(0);
              setReload((n) => n + 1);
            }}
          >
            重置
          </Button>
          {error && (
            <p
              id="real-search-error"
              className={styles.filterError}
              role="alert"
            >
              {error}
            </p>
          )}
        </form>
      </Surface>
      <Surface>
        <div className={styles.listHeader}>
          <h2 tabIndex={-1}>{listHeading}</h2>
          {data && (
            <span>
              共 {data.totalElements} 条 ·{' '}
              {filter.sort === 'createdAt,asc'
                ? '按创建时间正序'
                : '按创建时间倒序'}{' '}
              ·{' '}
              {role === 'ADMIN'
                ? '管理员可见'
                : role === 'WORKER'
                  ? '仅本人分配任务'
                  : '仅本人可见'}
            </span>
          )}
        </div>
        {loadError !== null && <RealErrorNotice error={loadError} />}
        {!data && loadError === null && <Notice>正在读取…</Notice>}
        {data && (
          <>
            {data.items.length > 0 && (
              <div
                className={`${styles.orderRow} ${styles.columns}`}
                aria-hidden="true"
              >
                <span>编号</span>
                <span>报修地点</span>
                <span className={styles.category}>故障类型</span>
                <span>状态</span>
                <span className={styles.date}>创建时间</span>
                <span>操作</span>
              </div>
            )}
            <ul className={styles.orders}>
              {data.items.map((o) => (
                <li key={o.id} className={styles.orderRow}>
                  <small className={styles.orderNumber}>#{o.id}</small>
                  <h3>{o.location}</h3>
                  <span className={styles.category}>
                    <span className={styles.mobileLabel}>类型：</span>
                    {categories[o.category] ?? '其他'}
                  </span>
                  <StatusTag status={o.status} />
                  <span className={styles.date}>
                    <span className={styles.mobileLabel}>创建：</span>
                    {formatDate(o.createdAt)}
                  </span>
                  <Link
                    to={`/orders/${o.id}`}
                    className={styles.rowLink}
                    aria-label={`查看详情：#${o.id} · ${o.location}`}
                  >
                    查看详情 →
                  </Link>
                </li>
              ))}
            </ul>
            {data.items.length === 0 && <Notice>暂无工单</Notice>}
          </>
        )}
        {(data !== null || loaded?.data !== null) && (
          <nav aria-label="工单分页" className={styles.realPagination}>
            <Button
              variant="secondary"
              disabled={!data || data.page === 0}
              onClick={() => data && setPage(data.page - 1)}
            >
              上一页
            </Button>
            <span aria-live="polite">
              第{' '}
              {(data ?? loaded?.data)?.totalPages
                ? (data ?? loaded?.data)!.page + 1
                : 0}{' '}
              页 / 共 {(data ?? loaded?.data)?.totalPages ?? 0} 页
            </span>
            <Button
              variant="secondary"
              disabled={!data || data.page + 1 >= data.totalPages}
              onClick={() => data && setPage(data.page + 1)}
            >
              下一页
            </Button>
          </nav>
        )}
      </Surface>
    </>
  );
}

export function RealDetail({
  client,
  role,
  confirmSeq = 0,
}: {
  client: StudentClient;
  role: MeResponse['role'];
  confirmSeq?: number;
}) {
  const { id = '' } = useParams();
  return (
    <Detail
      key={id}
      client={client}
      id={id}
      role={role}
      confirmSeq={confirmSeq}
    />
  );
}
function Detail({
  client,
  id,
  role,
  confirmSeq,
}: {
  client: StudentClient;
  id: string;
  role: MeResponse['role'];
  confirmSeq: number;
}) {
  const [data, setData] = useState<RealOrderDetail | null>(null);
  const [workers, setWorkers] = useState<WorkerOption[] | null>(null);
  const [workerError, setWorkerError] = useState('');
  const [workerId, setWorkerId] = useState('');
  const [result, setResult] = useState('');
  const [resultError, setResultError] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [success, setSuccess] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const cancelArea = useRef<HTMLDivElement>(null);
  const cancelPrompt = useRef<HTMLDivElement>(null);
  const feedback = useRef<HTMLDivElement>(null);
  const detailHeadingRef = useRef<HTMLHeadingElement>(null);
  const [blocked, setBlocked] = useState<{ csrfAt: number | null } | null>(
    null,
  );
  const [version, setVersion] = useState(0);
  const lock = useRef(false);
  void confirmSeq;
  const confirmation = client.writeConfirmation();
  const writesBlocked =
    confirmation === null ||
    (blocked !== null &&
      (blocked.csrfAt === null || confirmation <= blocked.csrfAt));
  const alive = useRef(true);
  useEffect(() => {
    if (confirmCancel) cancelPrompt.current?.querySelector('button')?.focus();
  }, [confirmCancel]);
  useEffect(() => {
    if (error !== null || success) feedback.current?.focus();
  }, [error, success]);
  useEffect(() => {
    if (data && document.activeElement === document.body) {
      detailHeadingRef.current?.focus();
    }
  }, [data]);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  useEffect(() => {
    let active = true;
    void client
      .detail(id)
      .then((d) => {
        if (!active) return;
        setData(d);
        setBlocked((current) =>
          current && current.csrfAt !== null ? current : null,
        );
        setError(null);
        if (role === 'ADMIN' && d.availableActions?.includes('ASSIGN')) {
          void client
            .workers()
            .then((options) => {
              if (active) setWorkers(options);
            })
            .catch((e) => {
              if (active) {
                setWorkers(null);
                setError(e);
              }
            });
        }
      })
      .catch((e) => {
        if (active) setError(e);
      });
    return () => {
      active = false;
    };
  }, [client, id, role, version]);
  async function act(action: OrderOperation) {
    if (lock.current || writesBlocked) return;
    if (action === 'assign' && !workers?.some((w) => w.id === workerId)) {
      setWorkerError('请选择有效的维修人员');
      document.getElementById('real-worker')?.focus();
      return;
    }
    const parsed =
      action === 'complete'
        ? completeSchema.safeParse({ repairResult: result })
        : null;
    if (parsed && !parsed.success) {
      setResultError(parsed.error.issues[0].message);
      document.getElementById('real-result')?.focus();
      return;
    }
    lock.current = true;
    const confirmationBefore = client.writeConfirmation() ?? 0;
    setBlocked(null);
    setBusy(true);
    setError(null);
    setSuccess('');
    setResultError('');
    try {
      await client.operate(
        id,
        action,
        action === 'assign'
          ? { workerId }
          : action === 'complete'
            ? parsed!.data
            : undefined,
      );
      const latest = await client.detail(id);
      if (alive.current) {
        setData(latest);
        setWorkers(null);
        setSuccess('操作完成，已读取最新工单。');
      }
    } catch (e) {
      if (alive.current) {
        setError(e);
        const csrfAt =
          (e instanceof RestProblem && e.problem.code === 'CSRF_REJECTED') ||
          client.writeConfirmation() === null
            ? confirmationBefore
            : null;
        setBlocked({ csrfAt });
      }
    } finally {
      lock.current = false;
      if (alive.current) {
        setBusy(false);
        setConfirmCancel(false);
      }
    }
  }
  function keepOrder() {
    setConfirmCancel(false);
    cancelArea.current?.querySelector('button')?.focus();
  }
  const can = (action: string) => data?.availableActions?.includes(action);
  const hasActions =
    (role === 'ADMIN' && can('ASSIGN')) ||
    (role === 'WORKER' && (can('START') || can('COMPLETE'))) ||
    (role === 'STUDENT' && (can('CONFIRM') || can('CANCEL')));
  return (
    <>
      <div className={styles.detailTopBar}>
        <Link to="/" className={styles.back}>
          ← 返回工单列表
        </Link>
        <Button
          variant="secondary"
          disabled={busy}
          onClick={() => {
            setData(null);
            setWorkers(null);
            setWorkerId('');
            setWorkerError('');
            setSuccess('');
            setVersion((n) => n + 1);
          }}
        >
          重新读取工单状态
        </Button>
      </div>
      <PageHeading
        eyebrow="FIXCAMPUS / 校园报修"
        title={data ? `#${data.id} · ${data.location}` : '工单详情'}
        description={
          role === 'ADMIN'
            ? '核对工单信息，安排当前状态允许的派单处理。'
            : '查看报修详情、现场附件与服务端处理记录。'
        }
      >
        {data && <StatusTag status={data.status} />}
      </PageHeading>
      {(error !== null || success) && (
        <div ref={feedback} tabIndex={-1}>
          {error !== null && <RealErrorNotice error={error} />}
          {success && <Notice>{success}</Notice>}
        </div>
      )}
      {busy && <Notice>正在提交并读取最新工单…</Notice>}
      {writesBlocked && (
        <Notice>
          操作没有自动重试。重新读取状态前暂停写操作；CSRF
          失败请先重新确认身份／CSRF。
        </Notice>
      )}
      {!data && error === null && (
        <Surface>
          <h2 tabIndex={-1}>报修详情</h2>
          <Notice>正在读取…</Notice>
        </Surface>
      )}
      {data && (
        <div className={styles.formLayout}>
          <div>
            <Surface>
              <h2 ref={detailHeadingRef} tabIndex={-1}>
                报修详情
              </h2>
              <dl className={styles.details}>
                <div>
                  <dt>报修地点</dt>
                  <dd>{data.location}</dd>
                </div>
                <div>
                  <dt>故障类型</dt>
                  <dd>{categories[data.category] ?? '其他'}</dd>
                </div>
                <div>
                  <dt>创建时间</dt>
                  <dd>{formatDate(data.createdAt)}</dd>
                </div>
                <div>
                  <dt>确认完成时间</dt>
                  <dd>
                    {data.completedAt
                      ? `确认完成：${formatDate(data.completedAt)}`
                      : '未确认'}
                  </dd>
                </div>
                {data.workerName && (
                  <div>
                    <dt>维修人员</dt>
                    <dd>{data.workerName}</dd>
                  </div>
                )}
                {data.studentName && (
                  <div>
                    <dt>报修学生</dt>
                    <dd>{data.studentName}</dd>
                  </div>
                )}
                {data.studentPhone && (
                  <div>
                    <dt>联系方式</dt>
                    <dd>{data.studentPhone}</dd>
                  </div>
                )}
                <div className={styles.full}>
                  <dt>问题描述</dt>
                  <dd>{data.description}</dd>
                </div>
                <div className={styles.full}>
                  <dt>维修结果</dt>
                  <dd>
                    {data.repairResult ??
                      (data.status === 'PENDING_CONFIRMATION' ||
                      data.status === 'COMPLETED'
                        ? '已提交维修结果，未填写说明'
                        : '尚未提交维修结果')}
                  </dd>
                </div>
              </dl>
            </Surface>
            {hasActions && (
              <Surface>
                <h2>{role === 'ADMIN' ? '派单处理' : '状态操作'}</h2>
                {role === 'ADMIN' && can('ASSIGN') && (
                  <form
                    aria-label="派单"
                    onSubmit={(e) => {
                      e.preventDefault();
                      void act('assign');
                    }}
                  >
                    <FormField
                      id="real-worker"
                      label="维修人员"
                      required
                      error={workerError}
                    >
                      <select
                        id="real-worker"
                        value={workerId}
                        aria-invalid={!!workerError}
                        aria-describedby={
                          workerError ? 'real-worker-error' : undefined
                        }
                        disabled={busy || writesBlocked || workers === null}
                        onChange={(e) => setWorkerId(e.target.value)}
                      >
                        <option value="">请选择</option>
                        {workers?.map((w) => (
                          <option key={w.id} value={w.id}>
                            {w.displayName} · {w.username}
                          </option>
                        ))}
                      </select>
                    </FormField>
                    {workers === null && !workerError && error === null && (
                      <Notice>正在读取维修人员名录…</Notice>
                    )}
                    {workers?.length === 0 && (
                      <Notice>暂无可分配维修人员</Notice>
                    )}
                    <Button
                      type="submit"
                      disabled={
                        busy ||
                        writesBlocked ||
                        !workers?.some((w) => w.id === workerId)
                      }
                    >
                      派单
                    </Button>
                  </form>
                )}
                {role === 'WORKER' && can('START') && (
                  <Button
                    disabled={busy || writesBlocked}
                    onClick={() => void act('start')}
                  >
                    开始维修
                  </Button>
                )}
                {role === 'WORKER' && can('COMPLETE') && (
                  <form
                    aria-label="维修结果"
                    onSubmit={(e) => {
                      e.preventDefault();
                      void act('complete');
                    }}
                  >
                    <FormField
                      id="real-result"
                      label="维修说明"
                      hint="最多 1000 个字符，可留空。"
                      error={resultError}
                    >
                      <textarea
                        id="real-result"
                        value={result}
                        aria-invalid={!!resultError}
                        aria-describedby={
                          resultError
                            ? 'real-result-hint real-result-error'
                            : 'real-result-hint'
                        }
                        disabled={busy || writesBlocked}
                        onChange={(e) => setResult(e.target.value)}
                      />
                    </FormField>
                    <Button type="submit" disabled={busy || writesBlocked}>
                      提交维修结果
                    </Button>
                  </form>
                )}
                {role === 'STUDENT' && can('CONFIRM') && (
                  <Button
                    disabled={busy || writesBlocked}
                    onClick={() => void act('confirm')}
                  >
                    确认维修完成
                  </Button>
                )}
                {role === 'STUDENT' && can('CANCEL') && (
                  <div ref={cancelArea} className={styles.cancelArea}>
                    <Button
                      variant="secondary"
                      disabled={busy || writesBlocked}
                      aria-expanded={confirmCancel}
                      aria-controls={
                        confirmCancel ? 'real-cancel-prompt' : undefined
                      }
                      onClick={() => setConfirmCancel(true)}
                    >
                      撤销报修
                    </Button>
                    {confirmCancel && (
                      <div
                        id="real-cancel-prompt"
                        ref={cancelPrompt}
                        role="group"
                        aria-labelledby="real-cancel-heading"
                        className={styles.cancelPrompt}
                        onKeyDown={(e) => {
                          if (e.key === 'Escape' && !busy) {
                            e.preventDefault();
                            keepOrder();
                          }
                        }}
                      >
                        <h4 id="real-cancel-heading">确认撤销这条报修？</h4>
                        <p>
                          撤销后无法恢复。如已派单，系统会拒绝撤销并提示重新读取状态。
                        </p>
                        <div className={styles.formActions}>
                          <Button
                            variant="secondary"
                            disabled={busy}
                            onClick={keepOrder}
                          >
                            保留工单
                          </Button>
                          <Button
                            disabled={busy || writesBlocked}
                            onClick={() => void act('cancel')}
                          >
                            确认撤销
                          </Button>
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </Surface>
            )}
            <Surface>
              <RealOrderAttachments
                client={client}
                orderId={data.id}
                role={role}
                orderStatus={data.status}
                reloadSeq={version}
                writesBlocked={writesBlocked || busy}
                onAuthBlocked={(csrfAt) => setBlocked({ csrfAt })}
              />
            </Surface>
          </div>
          <Surface>
            <h2>处理记录</h2>
            <p className={styles.currentStatus}>
              当前状态：{statuses[data.status].label}
            </p>
            {data.history.incomplete && (
              <p className={styles.note}>
                历史记录不完整；不补造时间或操作者。
              </p>
            )}
            <ol className={styles.timeline}>
              {data.history.entries.map((entry, i) => (
                <li
                  key={i}
                  className={entry.recorded ? undefined : styles.missingEvent}
                >
                  <strong>
                    {historyActionLabels[entry.action] ?? '未知操作'}
                    {!entry.recorded && '（未记录）'}
                  </strong>
                  <p>{entry.actorName ?? '操作者未记录'}</p>
                  <small>
                    {entry.occurredAt
                      ? formatDate(entry.occurredAt)
                      : '时间未记录'}
                    {!entry.recorded && ' · 未记录'}
                  </small>
                  {entry.detail && <p>{entry.detail}</p>}
                </li>
              ))}
            </ol>
          </Surface>
        </div>
      )}
    </>
  );
}
