import type {
  AdminStatisticsResponse,
  CreateOrderRequest,
  CreateOrderResponse,
  MeResponse,
  NotificationPageResponse,
  NotificationReadAllResponse,
  NotificationReadResponse,
  OrderAttachmentItem,
  OrderAttachmentListResponse,
  OrderItem,
  OrderPageResponse,
  ProblemDetail,
  HistoryEntry,
  Role,
  OrderStatus,
  StatsWindowDays,
  UnreadCountResponse,
} from '../contracts';

export interface RealOrderDetail extends OrderItem {
  description: string;
  workerName?: string | null;
  studentName?: string | null;
  studentPhone?: string | null;
  repairResult: string | null;
  completedAt: string | null;
  history: { entries: HistoryEntry[]; incomplete: boolean };
  availableActions: string[];
}
export interface StudentDetail extends RealOrderDetail {
  workerName: string | null;
}
export interface WorkerOption {
  id: string;
  username: string;
  displayName: string;
}
export interface ListFilter {
  status: OrderStatus | '';
  q: string;
  sort: 'createdAt,desc' | 'createdAt,asc';
}
export type OrderOperation =
  'assign' | 'start' | 'complete' | 'confirm' | 'cancel';

function requireOnline() {
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    throw new Error('当前离线，本次请求未发送。请联网后手动操作。');
  }
}

function unknownResponse(
  key?: string,
  scope: 'order' | 'notification' = 'order',
) {
  return new Error(
    key
      ? '网络响应未知；没有自动重试。原提交键已保留，请保持页面并重新确认身份。'
      : scope === 'notification'
        ? '网络响应未知；没有自动重试。请刷新通知列表，核对已读状态后再操作。'
        : '网络响应未知；没有自动重试。请重新读取工单，核对状态后再手动操作。',
  );
}

function validRequestId(value: unknown): string | null {
  return typeof value === 'string' && /^[A-Za-z0-9._-]{1,64}$/.test(value)
    ? value
    : null;
}

export class RestResponseError extends Error {
  readonly requestId: string | null;

  constructor(message: string, requestId: string | null) {
    super(message);
    this.requestId = validRequestId(requestId);
  }
}

export class RestProblem extends RestResponseError {
  constructor(
    public readonly problem: ProblemDetail,
    public readonly authEpoch?: number,
    requestId?: string | null,
  ) {
    super(problem.message, validRequestId(requestId) ?? problem.requestId);
  }
}
export function realError(
  error: unknown,
  scope: 'order' | 'notification' = 'order',
) {
  if (error instanceof RestProblem) {
    const p = error.problem;
    const hint =
      p.code === 'CSRF_REJECTED'
        ? '请重新确认身份／CSRF，再手动操作。'
        : p.status === 409
          ? '请重新读取最新状态，核对操作结果。'
          : p.status === 413
            ? '图片文件超过大小限制，请压缩后重试。'
            : p.status === 429
              ? '请求过于频繁，请稍候再试，不要连续重复提交。'
              : p.status === 503
                ? scope === 'notification'
                  ? '服务当前繁忙或暂不可用，请稍后刷新通知列表核对状态。'
                  : '服务当前繁忙或暂不可用，请稍后重新核对工单状态。'
                : p.status === 403
                  ? '当前账号无此操作权限。'
                  : p.status === 404
                    ? p.code === 'NOTIFICATION_NOT_FOUND' ||
                      scope === 'notification'
                      ? '通知不存在，或当前账号无权访问。'
                      : '工单或附件不存在，或当前账号无权访问。'
                    : '';
    return `${p.status} · ${p.code}：${p.message} ${hint}`;
  }
  return error instanceof Error ? error.message : '操作失败';
}
// Transport exists only here. MockRepository never delegates to this client.
export class StudentClient {
  private epoch = 0;
  private refreshing: Promise<MeResponse | null> | null = null;
  constructor(
    private readonly identityChanged: (
      user: MeResponse | null,
    ) => void = () => {},
    private readonly protectedRequestRejected: (
      error: RestProblem,
    ) => void = () => {},
  ) {}
  private csrf: { headerName: string; token: string } | null = null;
  private identity: string | null = null;
  private role: Role | null = null;
  private operating = false;
  private confirmedIdentity = 0;
  private confirmedCsrf = false;
  private identityVerificationFailed = false;
  private recoveryRequired = false;
  private readonly recoveryListeners = new Set<(required: boolean) => void>();
  private readonly drafts = new Map<string, CreateOrderRequest>();
  private pending: {
    key: string;
    body: string;
    owner: string;
  } | null = null;

  private setRecoveryRequired(next: boolean) {
    if (this.recoveryRequired === next) return;
    this.recoveryRequired = next;
    for (const listener of this.recoveryListeners) {
      listener(next);
    }
  }
  needsRecovery(): boolean {
    return this.recoveryRequired;
  }
  authEpoch(): number {
    return this.epoch;
  }
  subscribeRecovery(listener: (required: boolean) => void): () => void {
    this.recoveryListeners.add(listener);
    return () => {
      this.recoveryListeners.delete(listener);
    };
  }
  private revokeIdentity() {
    this.epoch++;
    this.identity = null;
    this.role = null;
    this.csrf = null;
    this.confirmedCsrf = false;
    this.identityVerificationFailed = false;
    this.refreshing = null;
    this.setRecoveryRequired(false);
    // Pending key/body/owner are deliberately independent of the authenticated view.
    this.identityChanged(null);
  }
  private assertCurrent(epoch: number) {
    if (epoch !== this.epoch)
      throw new Error('身份状态已更新，已丢弃旧响应；未自动重试');
  }
  pendingInput(): CreateOrderRequest | undefined {
    return this.pending?.owner === this.identity
      ? (JSON.parse(this.pending.body) as CreateOrderRequest)
      : undefined;
  }
  saveDraft(input: CreateOrderRequest) {
    if (!this.identity) return;
    if (!input.location && !input.category && !input.description) {
      this.drafts.delete(this.identity);
      return;
    }
    this.drafts.set(this.identity, { ...input });
  }
  draftInput(): CreateOrderRequest | undefined {
    return (
      this.pendingInput() ??
      (this.identity ? this.drafts.get(this.identity) : undefined)
    );
  }
  isRefreshing(): boolean {
    return this.refreshing !== null;
  }
  writeConfirmation(): number | null {
    return this.confirmedCsrf &&
      this.identity &&
      this.csrf?.headerName === 'X-CSRF-TOKEN' &&
      typeof this.csrf.token === 'string' &&
      this.csrf.token.length > 0
      ? this.confirmedIdentity
      : null;
  }

  private async request<T>(
    path: string,
    method = 'GET',
    body?: string | FormData,
    key?: string,
  ): Promise<T> {
    requireOnline();
    const epoch = this.epoch;
    const [route, query] = path.split('?');
    if (
      path.split('?').length > 2 ||
      !/^\/(auth\/(csrf|login|logout)|me|admin\/(workers|statistics)|notifications(?:\/(?:unread-count|read-all|[1-9]\d*\/read))?|(?:(?:admin|worker)\/)?orders(?:\/[1-9]\d*(?:\/attachments)?)?|orders\/[1-9]\d*\/attachments\/[1-9]\d*|orders\/[1-9]\d*\/(assign|start|complete|confirm|cancel))$/.test(
        route,
      )
    )
      throw new Error('请求路径不在真实工单范围内');
    if (
      query !== undefined &&
      !(
        (/^\/(?:(?:admin|worker)\/)?orders$/.test(route) &&
          [...new URLSearchParams(query).keys()].every((k) =>
            ['page', 'size', 'sort', 'status', 'q'].includes(k),
          )) ||
        (route === '/admin/statistics' &&
          [...new URLSearchParams(query).keys()].every((k) => k === 'days')) ||
        (route === '/notifications' &&
          [...new URLSearchParams(query).keys()].every((k) =>
            ['page', 'size', 'unreadOnly'].includes(k),
          ))
      )
    )
      throw new Error('查询路径不在真实工单范围内');
    const isBusinessRead =
      method === 'GET' && !route.startsWith('/auth/') && route !== '/me';
    if (isBusinessRead) {
      if (this.refreshing) {
        await this.refreshing;
        this.assertCurrent(epoch);
      }
      if (this.identityVerificationFailed) {
        throw new Error(
          '身份重新确认失败，请先重新确认身份／CSRF 后再读取数据',
        );
      }
    }
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (typeof body === 'string') headers['Content-Type'] = 'application/json';
    if (method !== 'GET') {
      if (!this.csrf || (route !== '/auth/login' && !this.confirmedCsrf))
        throw new Error('请重新确认登录状态后手动提交');
      if (this.csrf.headerName !== 'X-CSRF-TOKEN')
        throw new Error('不支持的 CSRF 协议');
      headers[this.csrf.headerName] = this.csrf.token;
    }
    if (key) headers['Idempotency-Key'] = key;
    const scope: 'order' | 'notification' = route.startsWith('/notifications')
      ? 'notification'
      : 'order';
    let response: Response;
    try {
      response = await fetch(`/api/v1${path}`, {
        method,
        headers,
        body,
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        signal: AbortSignal.timeout(15000),
      });
    } catch {
      this.assertCurrent(epoch);
      if (key) this.setRecoveryRequired(true);
      throw unknownResponse(key, scope);
    }
    if (isBusinessRead) {
      if (this.refreshing) {
        await this.refreshing;
      }
      this.assertCurrent(epoch);
      if (this.identityVerificationFailed) {
        throw new Error(
          '身份重新确认失败，请先重新确认身份／CSRF 后再读取数据',
        );
      }
    } else {
      this.assertCurrent(epoch);
    }
    if (!response.ok) {
      const requestId = response.headers.get('X-Request-Id');
      const revokedEpoch =
        response.status === 401
          ? (this.revokeIdentity(), this.epoch)
          : undefined;
      const contentType = response.headers.get('Content-Type') ?? '';
      if (!contentType.includes('application/problem+json'))
        throw new RestResponseError(
          `服务返回 ${response.status}，请核对接口状态`,
          requestId,
        );
      const problem = (await response.json().catch(() => {
        throw new RestResponseError(
          `服务返回 ${response.status}，错误响应无法读取`,
          requestId,
        );
      })) as ProblemDetail;
      if (response.status !== 401) {
        this.assertCurrent(epoch);
        if (response.status === 403 && problem.code !== 'ROLE_FORBIDDEN') {
          this.csrf = null;
          this.confirmedCsrf = false;
          this.setRecoveryRequired(true);
        }
      }
      const error = new RestProblem(problem, revokedEpoch, requestId);
      if (
        revokedEpoch === this.epoch &&
        route !== '/me' &&
        !route.startsWith('/auth/')
      ) {
        this.protectedRequestRejected(error);
      }
      throw error;
    }
    if (response.status === 204) return undefined as T;
    if (
      !(response.headers.get('Content-Type') ?? '').includes('application/json')
    )
      throw new Error('接口响应不是 JSON');
    const data = (await response.json().catch(() => {
      this.assertCurrent(epoch);
      if (key) this.setRecoveryRequired(true);
      throw unknownResponse(key, scope);
    })) as T;
    if (isBusinessRead) {
      if (this.refreshing) {
        await this.refreshing;
      }
      this.assertCurrent(epoch);
      if (this.identityVerificationFailed) {
        throw new Error(
          '身份重新确认失败，请先重新确认身份／CSRF 后再读取数据',
        );
      }
    } else {
      this.assertCurrent(epoch);
    }
    return data;
  }
  async refresh() {
    if (this.refreshing) return this.refreshing;
    const work = this.checkIdentity();
    this.refreshing = work;
    try {
      return await work;
    } finally {
      if (this.refreshing === work) this.refreshing = null;
    }
  }
  private async checkIdentity() {
    const startEpoch = this.epoch;
    // Keep the fetched CSRF payload local until /me confirms the active account,
    // so cross-tab account switches never leak the new session's CSRF to the old UI.
    try {
      const nextCsrf = await this.request<{
        headerName: string;
        token: string;
      }>('/auth/csrf');
      const me = await this.request<MeResponse>('/me');
      this.assertCurrent(startEpoch);
      if (this.identity !== me.id || this.role !== me.role) this.epoch++;
      this.csrf = nextCsrf;
      this.identity = me.id;
      this.role = me.role;
      this.confirmedIdentity++;
      this.confirmedCsrf = true;
      this.identityVerificationFailed = false;
      this.setRecoveryRequired(false);
      this.identityChanged(me);
      return me;
    } catch (error) {
      if (error instanceof RestProblem && error.problem.status === 401) {
        if (error.authEpoch !== this.epoch)
          throw new Error('身份状态已更新，已丢弃旧身份查询', { cause: error });
        this.identityVerificationFailed = false;
        // The anonymous me query must not discard the freshly obtained login token.
        this.csrf = await this.request('/auth/csrf');
        return null;
      }
      if (startEpoch !== this.epoch) {
        throw new Error('身份状态已更新，已丢弃旧身份查询', { cause: error });
      }
      if (this.identity !== null) {
        this.identityVerificationFailed = true;
        this.setRecoveryRequired(true);
      }
      throw error;
    }
  }
  async login(...[username, password]: [username: string, credential: string]) {
    requireOnline();
    this.revokeIdentity();
    this.csrf = await this.request('/auth/csrf');
    await this.request<MeResponse>(
      '/auth/login',
      'POST',
      JSON.stringify({ username, password }),
    );
    return this.refresh();
  }
  async logout() {
    const owner = this.identity;
    await this.request('/auth/logout', 'POST');
    if (owner) this.drafts.delete(owner);
    this.revokeIdentity();
    // Pending writes are retained in memory until their outcome is resolved.
  }
  private orderPrefix() {
    return this.role === 'ADMIN'
      ? '/admin/orders'
      : this.role === 'WORKER'
        ? '/worker/orders'
        : '/orders';
  }
  list(page = 0, filter?: ListFilter) {
    const query = new URLSearchParams({ page: String(page), size: '20' });
    if (filter) {
      query.set('sort', filter.sort);
      query.set('status', filter.status);
      query.set('q', filter.q);
    }
    return this.request<OrderPageResponse>(`${this.orderPrefix()}?${query}`);
  }
  detail(id: string) {
    return this.request<RealOrderDetail>(`${this.orderPrefix()}/${id}`);
  }
  workers() {
    return this.request<WorkerOption[]>('/admin/workers');
  }
  adminStatistics(days: StatsWindowDays = 14) {
    return this.request<AdminStatisticsResponse>(
      `/admin/statistics?days=${days}`,
    );
  }
  unreadNotificationCount() {
    return this.request<UnreadCountResponse>('/notifications/unread-count');
  }
  listNotifications(page = 0, unreadOnly = false, size = 20) {
    const query = new URLSearchParams({
      page: String(page),
      size: String(size),
      unreadOnly: String(unreadOnly),
    });
    return this.request<NotificationPageResponse>(`/notifications?${query}`);
  }
  markNotificationRead(id: string) {
    return this.request<NotificationReadResponse>(
      `/notifications/${id}/read`,
      'POST',
      JSON.stringify({}),
    );
  }
  markAllNotificationsRead() {
    return this.request<NotificationReadAllResponse>(
      '/notifications/read-all',
      'POST',
      JSON.stringify({}),
    );
  }
  listAttachments(orderId: string) {
    return this.request<OrderAttachmentListResponse>(
      `${this.orderPrefix()}/${orderId}/attachments`,
    );
  }
  async uploadAttachment(orderId: string, file: File) {
    if (this.operating) throw new Error('操作正在提交，请勿重复操作');
    if (this.role !== 'STUDENT' && this.role !== 'WORKER') {
      throw new Error('当前身份不支持上传图片附件');
    }
    const route =
      this.role === 'WORKER'
        ? `/worker/orders/${orderId}/attachments`
        : `/orders/${orderId}/attachments`;
    const form = new FormData();
    form.append('file', file);
    this.operating = true;
    try {
      return await this.request<OrderAttachmentItem>(route, 'POST', form);
    } finally {
      this.operating = false;
    }
  }
  async deleteAttachment(orderId: string, attachmentId: string) {
    if (this.operating) throw new Error('操作正在提交，请勿重复操作');
    if (this.role !== 'STUDENT') {
      throw new Error('仅报修学生可删除附件');
    }
    this.operating = true;
    try {
      await this.request<void>(
        `/orders/${orderId}/attachments/${attachmentId}`,
        'DELETE',
      );
    } finally {
      this.operating = false;
    }
  }
  async operate(
    id: string,
    action: OrderOperation,
    input?: { workerId?: string; repairResult?: string },
  ) {
    if (this.operating) throw new Error('操作正在提交，请勿重复操作');
    this.operating = true;
    try {
      await this.request<void>(
        `/orders/${id}/${action}`,
        'POST',
        JSON.stringify(input ?? {}),
      );
    } finally {
      this.operating = false;
    }
  }
  async create(input: CreateOrderRequest) {
    requireOnline();
    if (!this.identity) throw new Error('请先确认登录身份');
    const body = JSON.stringify({
      location: input.location.trim(),
      category: input.category,
      description: input.description.replace(/\r\n?/g, '\n').trim(),
    });
    if (
      this.pending &&
      (this.pending.body !== body || this.pending.owner !== this.identity)
    )
      throw new Error('有尚未确认的提交；请使用原身份和原内容恢复，不生成新键');
    this.pending ??= {
      key: crypto.randomUUID(),
      body,
      owner: this.identity,
    };
    const result = await this.request<CreateOrderResponse>(
      '/orders',
      'POST',
      body,
      this.pending.key,
    );
    this.pending = null;
    this.drafts.delete(this.identity);
    return result;
  }
}
