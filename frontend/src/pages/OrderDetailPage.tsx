import { Link, useLocation, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useDemo } from '../app/context';
import type { Action, OrderDetailResponse } from '../shared/api/contracts';
import { workers } from '../shared/api/mock/fixtures/users';
import {
  assignSchema,
  categories,
  completeSchema,
} from '../shared/forms/schemas';
import { actionLabels, statuses } from '../shared/utils/orderStatus';
import { formatDate } from '../shared/utils/formatDate';
import {
  Button,
  CharacterCount,
  ErrorState,
  FormField,
  LoadingState,
  Notice,
  PageHeading,
  StatusTag,
  Surface,
} from '../shared/components/ui';
import styles from './pages.module.css';

export function OrderDetailPage() {
  const { orderId = '' } = useParams();
  const { user, repository } = useDemo();
  const location = useLocation();
  const result = useQuery({
    queryKey: ['detail', user?.id, orderId],
    queryFn: ({ signal }) => repository.getOrder(user!, orderId, signal),
    enabled: !!user,
  });
  return (
    <>
      <Link to={`/${user!.role.toLowerCase()}/orders`} className={styles.back}>
        ← 返回工单列表
      </Link>
      {result.isSuccess && location.state?.notice && (
        <Notice>{String(location.state.notice)}</Notice>
      )}
      {result.isPending ? (
        <LoadingState />
      ) : result.isError ? (
        <ErrorState
          error={result.error}
          onRetry={() => void result.refetch()}
        />
      ) : (
        <Detail key={orderId} order={result.data} />
      )}
    </>
  );
}
function Detail({ order }: { order: OrderDetailResponse }) {
  const { user, repository } = useDemo();
  const client = useQueryClient();
  const assign = useForm<{ workerId: string }>({
    resolver: zodResolver(assignSchema),
    defaultValues: { workerId: '' },
  });
  const complete = useForm<{ repairResult: string }>({
    resolver: zodResolver(completeSchema),
    defaultValues: { repairResult: '' },
  });
  const repairResult = useWatch({
    control: complete.control,
    name: 'repairResult',
  });
  const mutation = useMutation({
    mutationFn: ({
      action,
      input,
    }: {
      action: Action;
      input?: { workerId?: string; repairResult?: string | null };
    }) => repository.updateOrder(user!, order.id, action, input),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['detail'] });
      await client.invalidateQueries({ queryKey: ['orders'] });
    },
  });
  return (
    <>
      <PageHeading
        title={`模拟工单 #${order.id}`}
        description={
          user?.role === 'ADMIN'
            ? '核对工单信息，安排当前状态允许的模拟处理。'
            : '查看虚构报修详情与已记录的模拟处理过程。'
        }
      >
        <StatusTag status={order.status} />
      </PageHeading>
      {mutation.isSuccess && (
        <Notice>
          模拟操作成功，仅更新当前内存；刷新会重置，未写入真实系统。
        </Notice>
      )}
      {mutation.isError && <ErrorState error={mutation.error} />}
      <div className={styles.formLayout}>
        <div>
          <Surface>
            <h2>报修详情</h2>
            <dl className={styles.details}>
              <div>
                <dt>报修地点</dt>
                <dd>{order.location}</dd>
              </div>
              <div>
                <dt>故障类型</dt>
                <dd>{categories[order.category]}</dd>
              </div>
              <div>
                <dt>创建时间</dt>
                <dd>{formatDate(order.createdAt)}</dd>
              </div>
              <div>
                <dt>确认完成时间</dt>
                <dd>{formatDate(order.completedAt)}</dd>
              </div>
              <div className={styles.full}>
                <dt>问题描述</dt>
                <dd>{order.description}</dd>
              </div>
              <div className={styles.full}>
                <dt>维修说明</dt>
                <dd>{order.repairResult ?? '暂无维修说明'}</dd>
              </div>
              {order.viewRole === 'STUDENT' && (
                <div>
                  <dt>虚构维修人员</dt>
                  <dd>{order.workerName ?? '待分配'}</dd>
                </div>
              )}
              {order.viewRole === 'ADMIN' && (
                <>
                  <div>
                    <dt>虚构报修人</dt>
                    <dd>{order.student.displayName}</dd>
                  </div>
                  <div>
                    <dt>虚构维修人员</dt>
                    <dd>{order.worker?.displayName ?? '待分配'}</dd>
                  </div>
                </>
              )}
              {order.viewRole === 'WORKER' && (
                <div>
                  <dt>虚构报修人</dt>
                  <dd>{order.studentName}</dd>
                </div>
              )}
            </dl>
          </Surface>
          {order.availableActions.length > 0 && (
            <Surface>
              <h2>{user?.role === 'ADMIN' ? '模拟派单处理' : '模拟处理'}</h2>
              <p className={styles.note}>
                仅演示当前角色和状态的可用操作，不构成真实权限控制。不会自动重试写操作。
              </p>
              {order.availableActions.includes('ASSIGN') && (
                <form
                  onSubmit={assign.handleSubmit((input) =>
                    mutation.mutate({ action: 'ASSIGN', input }),
                  )}
                  noValidate
                >
                  <FormField
                    required
                    label="虚构维修人员"
                    id="workerId"
                    hint="仅列出可通过现有入口进入的虚构维修身份。"
                    error={assign.formState.errors.workerId?.message}
                  >
                    <select
                      id="workerId"
                      aria-required="true"
                      {...assign.register('workerId')}
                      aria-invalid={!!assign.formState.errors.workerId}
                      aria-describedby={
                        assign.formState.errors.workerId
                          ? 'workerId-hint workerId-error'
                          : 'workerId-hint'
                      }
                    >
                      <option value="">请选择虚构维修人员</option>
                      {workers.map((w) => (
                        <option key={w.id} value={w.id}>
                          {w.displayName}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <Button type="submit" disabled={mutation.isPending}>
                    模拟派单
                  </Button>
                </form>
              )}
              {order.availableActions.includes('COMPLETE') && (
                <form
                  onSubmit={complete.handleSubmit((input) =>
                    mutation.mutate({ action: 'COMPLETE', input }),
                  )}
                  noValidate
                >
                  <FormField
                    label="维修说明（可选）"
                    id="repairResult"
                    hint="最多 1000 个 Unicode 字符"
                    error={complete.formState.errors.repairResult?.message}
                  >
                    <textarea
                      id="repairResult"
                      {...complete.register('repairResult')}
                      aria-invalid={!!complete.formState.errors.repairResult}
                      aria-describedby={`repairResult-hint${complete.formState.errors.repairResult ? ' repairResult-error' : ''}`}
                    />
                    <CharacterCount value={repairResult} max={1000} />
                  </FormField>
                  <Button type="submit" disabled={mutation.isPending}>
                    模拟提交维修结果
                  </Button>
                </form>
              )}
              <div className={styles.formActions}>
                {order.availableActions
                  .filter((a) => a !== 'ASSIGN' && a !== 'COMPLETE')
                  .map((action) => (
                    <Button
                      key={action}
                      disabled={mutation.isPending}
                      aria-busy={mutation.isPending}
                      onClick={() => mutation.mutate({ action })}
                    >
                      {actionLabels[action]}
                    </Button>
                  ))}
              </div>
            </Surface>
          )}
        </div>
        <Surface>
          <h2>模拟处理记录</h2>
          <p className={styles.currentStatus}>
            当前状态：{statuses[order.status].label}
          </p>
          {order.history.incomplete && (
            <p className={styles.note}>部分事件未记录；不补造时间或操作者。</p>
          )}
          <ol className={styles.timeline}>
            {order.history.entries.map((event, i) => (
              <li
                key={i}
                className={
                  event.recorded ? styles.recordedEvent : styles.missingEvent
                }
              >
                <strong>
                  {event.recorded
                    ? event.action === 'SUBMITTED'
                      ? '模拟提交'
                      : (actionLabels[event.action as Action] ?? event.action)
                    : '历史事件未记录'}
                </strong>
                <p>{event.actorName ?? '操作者未记录'}</p>
                <small>
                  {formatDate(event.occurredAt)} ·{' '}
                  {event.recorded ? '虚构记录' : '未记录'}
                </small>
                {event.detail && <p>{event.detail}</p>}
              </li>
            ))}
          </ol>
        </Surface>
      </div>
    </>
  );
}
