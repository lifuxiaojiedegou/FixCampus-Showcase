import type {
  Action,
  CreateOrderRequest,
  CreateOrderResponse,
  MeResponse,
  OrderDetailResponse,
  OrderItem,
  OrderQuery,
} from '../contracts';
import type { OrderRepository } from '../orderRepository';
import { MockProblem } from '../errors';
import {
  assignSchema,
  completeSchema,
  createSchema,
  searchSchema,
} from '../../forms/schemas';
import { makeFixtures, type MockOrder } from './fixtures/orders';
import { otherStudent, users, workers } from './fixtures/users';
import { latency } from './latency';
import type { Scenario } from './scenarios';

export const actionsFor = (user: MeResponse, order: MockOrder): Action[] => {
  if (user.role === 'STUDENT' && order.studentId === user.id)
    return order.status === 'PENDING_ASSIGNMENT'
      ? ['CANCEL']
      : order.status === 'PENDING_CONFIRMATION'
        ? ['CONFIRM']
        : [];
  if (user.role === 'ADMIN')
    return order.status === 'PENDING_ASSIGNMENT' ? ['ASSIGN'] : [];
  if (user.role === 'WORKER' && order.workerId === user.id)
    return order.status === 'PENDING_REPAIR'
      ? ['START']
      : order.status === 'IN_PROGRESS'
        ? ['COMPLETE']
        : [];
  return [];
};
const visible = (user: MeResponse, order: MockOrder) =>
  user.role === 'ADMIN' ||
  (user.role === 'STUDENT'
    ? order.studentId === user.id
    : order.workerId === user.id);
const item = (o: MockOrder): OrderItem => ({
  id: o.id,
  location: o.location,
  category: o.category,
  status: o.status,
  createdAt: o.createdAt,
});
export class MockRepository implements OrderRepository {
  private orders = makeFixtures();
  private nextId = 1025;
  constructor(
    private readonly scenario: Scenario = 'normal',
    private readonly delay = 180,
  ) {}
  private async wait(signal?: AbortSignal) {
    await latency(signal, this.delay);
    if (this.scenario === 'unavailable')
      throw new MockProblem(
        503,
        'TEMPORARILY_UNAVAILABLE',
        '模拟服务暂不可用；没有发送真实请求',
      );
    if (this.scenario === 'forbidden')
      throw new MockProblem(403, 'ROLE_FORBIDDEN', '模拟角色无权执行此操作');
    if (this.scenario === 'expired')
      throw new MockProblem(
        401,
        'AUTH_REQUIRED',
        '模拟身份已失效，请重新选择虚构身份',
      );
  }
  private find(user: MeResponse, id: string) {
    const order = this.orders.find((o) => o.id === id && visible(user, o));
    if (!order)
      throw new MockProblem(
        404,
        'ORDER_NOT_FOUND',
        '工单不存在或在当前虚构身份下不可见',
      );
    return order;
  }
  todoCount(user: MeResponse) {
    return this.orders.filter(
      (o) =>
        visible(user, o) &&
        (user.role === 'ADMIN'
          ? o.status === 'PENDING_ASSIGNMENT'
          : user.role === 'STUDENT'
            ? o.status === 'PENDING_CONFIRMATION'
            : ['PENDING_REPAIR', 'IN_PROGRESS'].includes(o.status)),
    ).length;
  }
  async listOrders(user: MeResponse, query: OrderQuery, signal?: AbortSignal) {
    await this.wait(signal);
    const q = searchSchema.safeParse(query.q);
    if (
      !q.success ||
      !Number.isInteger(query.page) ||
      query.page < 0 ||
      !Number.isInteger(query.size) ||
      query.size < 1 ||
      query.size > 50 ||
      !['createdAt,desc', 'createdAt,asc'].includes(query.sort) ||
      (query.status &&
        ![
          'PENDING_ASSIGNMENT',
          'PENDING_REPAIR',
          'IN_PROGRESS',
          'PENDING_CONFIRMATION',
          'COMPLETED',
          'CANCELLED',
        ].includes(query.status)) ||
      (user.role === 'WORKER' &&
        query.status &&
        ![
          'PENDING_REPAIR',
          'IN_PROGRESS',
          'PENDING_CONFIRMATION',
          'COMPLETED',
        ].includes(query.status))
    )
      throw new MockProblem(400, 'INVALID_QUERY', '模拟查询参数无效');
    const search = q.data.replace(/^#(?=\d+$)/, '');
    const matches =
      this.scenario === 'empty'
        ? []
        : this.orders.filter(
            (o) =>
              visible(user, o) &&
              (!query.status || o.status === query.status) &&
              (!search ||
                o.location.includes(search) ||
                (/^[1-9]\d*$/.test(search) && o.id === search)),
          );
    matches.sort(
      (a, b) =>
        (Date.parse(a.createdAt) - Date.parse(b.createdAt) ||
          Number(a.id) - Number(b.id)) * (query.sort.endsWith('desc') ? -1 : 1),
    );
    return {
      items: matches
        .slice(query.page * query.size, (query.page + 1) * query.size)
        .map(item),
      page: query.page,
      size: query.size,
      totalElements: matches.length,
      totalPages: Math.ceil(matches.length / query.size),
      sort: query.sort,
    };
  }
  async getOrder(
    user: MeResponse,
    id: string,
    signal?: AbortSignal,
  ): Promise<OrderDetailResponse> {
    await this.wait(signal);
    const o = this.find(user, id);
    const worker = workers.find((w) => w.id === o.workerId);
    const base = {
      ...item(o),
      description: o.description,
      repairResult: o.repairResult,
      completedAt: o.completedAt,
      history: {
        entries: structuredClone(o.entries),
        incomplete: o.historyIncomplete || o.entries.some((e) => !e.recorded),
      },
      availableActions: actionsFor(user, o),
    };
    const student =
      o.studentId === users.student.id ? users.student : otherStudent;
    if (user.role === 'ADMIN')
      return {
        ...base,
        viewRole: 'ADMIN',
        student: { ...student },
        worker: worker ? { ...worker } : null,
      };
    if (user.role === 'WORKER')
      return { ...base, viewRole: 'WORKER', studentName: student.displayName };
    return {
      ...base,
      viewRole: 'STUDENT',
      ...(worker ? { workerName: worker.displayName } : {}),
    };
  }
  async createOrder(
    user: MeResponse,
    input: CreateOrderRequest,
  ): Promise<CreateOrderResponse> {
    await this.wait();
    if (user.role !== 'STUDENT')
      throw new MockProblem(403, 'ROLE_FORBIDDEN', '仅虚构学生可模拟报修');
    const validated = createSchema.safeParse(input);
    if (!validated.success)
      throw new MockProblem(400, 'VALIDATION_ERROR', '请检查模拟表单输入');
    if (this.scenario === 'conflict')
      throw new MockProblem(
        409,
        'ORDER_STATE_CONFLICT',
        '模拟操作冲突；未创建工单',
      );
    const id = String(this.nextId++);
    const createdAt = new Date().toISOString();
    this.orders.push({
      ...validated.data,
      id,
      studentId: user.id,
      workerId: null,
      status: 'PENDING_ASSIGNMENT',
      createdAt,
      completedAt: null,
      repairResult: null,
      historyIncomplete: false,
      entries: [
        {
          action: 'SUBMITTED',
          occurredAt: createdAt,
          actorRole: user.role,
          actorName: user.displayName,
          detail: null,
          recorded: true,
        },
      ],
    });
    return { id, status: 'PENDING_ASSIGNMENT' };
  }
  async updateOrder(
    user: MeResponse,
    id: string,
    action: Action,
    input: { workerId?: string; repairResult?: string | null } = {},
  ) {
    await this.wait();
    const o = this.find(user, id);
    const roleActions =
      user.role === 'ADMIN'
        ? ['ASSIGN']
        : user.role === 'WORKER'
          ? ['START', 'COMPLETE']
          : ['CANCEL', 'CONFIRM'];
    if (!roleActions.includes(action))
      throw new MockProblem(403, 'ROLE_FORBIDDEN', '模拟角色无权执行该操作');
    if (this.scenario === 'conflict' || !actionsFor(user, o).includes(action))
      throw new MockProblem(
        409,
        'ORDER_STATE_CONFLICT',
        '模拟状态已变化，请刷新后决定下一步；不会自动重试',
      );
    if (action === 'ASSIGN') {
      if (
        !assignSchema.safeParse(input).success ||
        !workers.some((w) => w.id === input.workerId)
      )
        throw new MockProblem(
          400,
          'VALIDATION_ERROR',
          '请选择有效的虚构维修人员',
        );
      o.workerId = input.workerId!;
    }
    if (action === 'COMPLETE') {
      const parsed = completeSchema.safeParse({
        repairResult: input.repairResult ?? '',
      });
      if (!parsed.success)
        throw new MockProblem(400, 'VALIDATION_ERROR', '维修说明超出长度限制');
      o.repairResult = parsed.data.repairResult || null;
    }
    const next = {
      ASSIGN: 'PENDING_REPAIR',
      START: 'IN_PROGRESS',
      COMPLETE: 'PENDING_CONFIRMATION',
      CONFIRM: 'COMPLETED',
      CANCEL: 'CANCELLED',
    } as const;
    o.status = next[action];
    const now = new Date().toISOString();
    if (action === 'CONFIRM') o.completedAt = now;
    o.entries.push({
      action,
      occurredAt: now,
      actorRole: user.role,
      actorName: user.displayName,
      detail: action === 'COMPLETE' ? o.repairResult : null,
      recorded: true,
    });
  }
}
