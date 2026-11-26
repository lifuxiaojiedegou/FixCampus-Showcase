import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useDemo } from '../app/context';
import type { OrderQuery, OrderStatus } from '../shared/api/contracts';
import { categories, searchSchema } from '../shared/forms/schemas';
import { statuses } from '../shared/utils/orderStatus';
import { formatDate } from '../shared/utils/formatDate';
import {
  Button,
  EmptyState,
  ErrorState,
  LoadingState,
  Notice,
  PageHeading,
  Pagination,
  StatusTag,
  Surface,
} from '../shared/components/ui';
import styles from './pages.module.css';

const initialQuery: OrderQuery = {
  page: 0,
  size: 20,
  sort: 'createdAt,desc',
  status: '',
  q: '',
};
export function OrdersPage() {
  const { user, repository } = useDemo();
  const [query, setQuery] = useState(initialQuery);
  const [searchError, setSearchError] = useState('');
  const result = useQuery({
    queryKey: ['orders', user?.id, query],
    queryFn: ({ signal }) => repository.listOrders(user!, query, signal),
    enabled: !!user,
  });
  if (!user) return null;
  const rolePath = user.role.toLowerCase();
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const q = searchSchema.safeParse(form.get('q'));
    if (!q.success) {
      setSearchError(q.error.issues[0].message);
      return;
    }
    setSearchError('');
    setQuery({
      page: 0,
      size: Number(form.get('size')),
      sort: 'createdAt,desc',
      status: form.get('status') as OrderStatus | '',
      q: q.data,
    });
  };
  const title =
    user.role === 'ADMIN'
      ? '工单管理'
      : user.role === 'WORKER'
        ? '我的维修任务'
        : '我的报修';
  const entries = Object.entries(statuses).filter(
    ([s]) =>
      user.role !== 'WORKER' ||
      [
        'PENDING_REPAIR',
        'IN_PROGRESS',
        'PENDING_CONFIRMATION',
        'COMPLETED',
      ].includes(s),
  );
  return (
    <>
      <PageHeading
        title={title}
        description={
          user.role === 'ADMIN'
            ? '查看虚构工单并安排维修人员，跟进模拟处理进度。'
            : user.role === 'WORKER'
              ? '查看分配给当前虚构身份的任务，记录模拟维修结果。'
              : '查看我的虚构报修记录，跟进进度并确认模拟结果。'
        }
      >
        {user.role === 'STUDENT' && (
          <Link className={styles.linkButton} to="/student/new">
            ＋ 新建模拟报修
          </Link>
        )}
      </PageHeading>
      <Notice>
        {user.role === 'ADMIN'
          ? '待派单'
          : user.role === 'WORKER'
            ? '待处理'
            : '待我确认'}{' '}
        <strong>{repository.todoCount(user)}</strong> 项 ·
        基于当前虚构身份的完整模拟范围，不受列表筛选影响。
      </Notice>
      <Surface>
        <form
          key={JSON.stringify(query)}
          className={styles.filters}
          onSubmit={submit}
          aria-label="筛选模拟工单"
        >
          <div>
            <label htmlFor="status">工单状态</label>
            <select id="status" name="status" defaultValue={query.status}>
              <option value="">全部状态</option>
              {entries.map(([value, state]) => (
                <option key={value} value={value}>
                  {state.label}
                </option>
              ))}
            </select>
          </div>
          <div className={styles.search}>
            <label htmlFor="q">地点或工单编号</label>
            <input
              id="q"
              name="q"
              defaultValue={query.q}
              placeholder="搜索虚构地点或 #1001"
              aria-invalid={!!searchError}
              aria-describedby={searchError ? 'q-error' : undefined}
            />
          </div>
          <div>
            <label htmlFor="size">每页条数</label>
            <select id="size" name="size" defaultValue={query.size}>
              {[10, 20, 50].map((n) => (
                <option key={n} value={n}>
                  {n} 条
                </option>
              ))}
            </select>
          </div>
          <Button type="submit">筛选</Button>
          <Button
            variant="secondary"
            onClick={() => {
              setQuery({ ...initialQuery });
              setSearchError('');
            }}
          >
            重置
          </Button>
          {searchError && (
            <p id="q-error" className={styles.filterError} role="alert">
              {searchError}
            </p>
          )}
        </form>
      </Surface>
      {result.isPending ? (
        <LoadingState />
      ) : result.isError ? (
        <ErrorState
          error={result.error}
          onRetry={() => void result.refetch()}
        />
      ) : (
        <Surface>
          <div className={styles.listHeader}>
            <h2>模拟工单列表</h2>
            <span>
              共 {result.data.totalElements} 条 · 按创建时间倒序 · 仅内存分页
            </span>
          </div>
          {result.data.items.length === 0 ? (
            <EmptyState
              message={
                query.page > 0
                  ? '当前页为空，请返回上一页或重置筛选。'
                  : undefined
              }
            />
          ) : (
            <>
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
              <ul className={styles.orders}>
                {result.data.items.map((o) => (
                  <li key={o.id} className={styles.orderRow}>
                    <small className={styles.orderNumber}>#{o.id}</small>
                    <h3>{o.location}</h3>
                    <span className={styles.category}>
                      <span className={styles.mobileLabel}>类型：</span>
                      {categories[o.category]}
                    </span>
                    <StatusTag status={o.status} />
                    <span className={styles.date}>
                      <span className={styles.mobileLabel}>创建：</span>
                      {formatDate(o.createdAt)}
                    </span>
                    <Link
                      className={styles.rowLink}
                      aria-label={`查看模拟工单 ${o.id}`}
                      to={`/${rolePath}/orders/${o.id}`}
                    >
                      查看详情 →
                    </Link>
                  </li>
                ))}
              </ul>
            </>
          )}
          {(result.data.totalPages > 0 || query.page > 0) && (
            <Pagination
              page={query.page}
              totalPages={result.data.totalPages}
              onPage={(page) => setQuery({ ...query, page })}
            />
          )}
        </Surface>
      )}
    </>
  );
}
