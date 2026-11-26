import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import App from '../app/App';
import type { Role, OrderStatus } from '../shared/api/contracts';
const transport = vi.mocked(fetch);
const reply = (body: unknown, status = 200) =>
  new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: {
      'Content-Type':
        status >= 400 ? 'application/problem+json' : 'application/json',
    },
  });
const problem = (status: number, code: string) =>
  reply({ status, code, message: code, requestId: `order-${status}` }, status);
let role: Role, orderStatus: OrderStatus, writeFailure: number | undefined;
let pageCount: number, holdWrite: ((response: Response) => void) | undefined;
let blockedWrite = false;
const actions = () =>
  orderStatus === 'PENDING_ASSIGNMENT'
    ? role === 'STUDENT'
      ? ['CANCEL']
      : ['ASSIGN']
    : orderStatus === 'PENDING_REPAIR'
      ? ['START']
      : orderStatus === 'IN_PROGRESS'
        ? ['COMPLETE']
        : orderStatus === 'PENDING_CONFIRMATION'
          ? ['CONFIRM']
          : [];
const detail = () => ({
  id: '101',
  location: '真实联调测试地点',
  category: 'OTHER',
  description: '服务器工单内容',
  status: orderStatus,
  createdAt: '2026-11-08T12:00:00+08:00',
  repairResult:
    orderStatus === 'PENDING_CONFIRMATION' || orderStatus === 'COMPLETED'
      ? '已更换零件'
      : null,
  completedAt: orderStatus === 'COMPLETED' ? '2026-11-08T13:00:00+08:00' : null,
  availableActions: actions(),
  history: {
    incomplete: false,
    entries: [
      {
        action:
          orderStatus === 'COMPLETED'
            ? 'CONFIRMED'
            : orderStatus === 'PENDING_CONFIRMATION'
              ? 'REPAIR_COMPLETED'
              : orderStatus === 'IN_PROGRESS'
                ? 'REPAIR_STARTED'
                : orderStatus === 'PENDING_REPAIR'
                  ? 'ASSIGNED'
                  : orderStatus === 'CANCELLED'
                    ? 'CANCELLED'
                    : 'SUBMITTED',
        occurredAt: '2026-11-08T12:00:00+08:00',
        actorName: '虚构处理人',
        recorded: true,
        detail: null,
      },
    ],
  },
});
beforeEach(() => {
  role = 'ADMIN';
  orderStatus = 'PENDING_ASSIGNMENT';
  writeFailure = undefined;
  pageCount = 1;
  holdWrite = undefined;
  blockedWrite = false;
  transport.mockReset().mockImplementation(async (input, options) => {
    const u = new URL(String(input), 'https://local.invalid');
    const method = options?.method ?? 'GET';
    if (u.pathname === '/api/v1/auth/csrf')
      return reply({ headerName: 'X-CSRF-TOKEN', token: 'test' });
    if (u.pathname === '/api/v1/me' || u.pathname === '/api/v1/auth/login')
      return reply({
        id: '9',
        username: 'fictional',
        displayName: '虚构' + role,
        role,
      });
    if (u.pathname === '/api/v1/admin/workers')
      return reply([
        { id: '32', username: 'worker_test', displayName: '数据库维修员' },
      ]);
    if (u.pathname === '/api/v1/notifications/unread-count')
      return reply({ unreadCount: 0 });
    if (method === 'POST') {
      if (writeFailure)
        return problem(
          writeFailure,
          writeFailure === 401
            ? 'AUTH_REQUIRED'
            : writeFailure === 403
              ? 'CSRF_REJECTED'
              : writeFailure === 404
                ? 'ORDER_NOT_FOUND'
                : 'ORDER_CONFLICT',
        );
      const transition = () => {
        orderStatus = u.pathname.endsWith('/cancel')
          ? 'CANCELLED'
          : u.pathname.endsWith('/assign')
            ? 'PENDING_REPAIR'
            : u.pathname.endsWith('/start')
              ? 'IN_PROGRESS'
              : u.pathname.endsWith('/complete')
                ? 'PENDING_CONFIRMATION'
                : 'COMPLETED';
        return reply(null, 204);
      };
      if (blockedWrite)
        return new Promise((resolve) => {
          holdWrite = () => resolve(transition());
        });
      return transition();
    }
    if (u.pathname.endsWith('/101/attachments'))
      return reply({
        orderId: '101',
        items: [],
        limits: {
          maxFileBytes: 2097152,
          maxWidth: 4096,
          maxHeight: 4096,
          maxPixels: 16000000,
          maxStudentAttachments: 3,
          maxWorkerAttachments: 2,
          allowedContentTypes: ['image/png', 'image/jpeg'],
        },
        canUpload: false,
      });
    if (u.pathname.endsWith('/101')) return reply(detail());
    if (u.pathname.endsWith('/orders'))
      return reply({
        items: [{ ...detail(), location: role + '列表地点' }],
        page: Number(u.searchParams.get('page') ?? 0),
        size: 20,
        totalElements: pageCount,
        totalPages: pageCount,
        sort: 'createdAt,desc',
      });
    throw Error('Unexpected test request: ' + u.pathname);
  });
});
function mount(path = '/orders/101') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}
const writes = () =>
  transport.mock.calls.filter((c) => c[1]?.method === 'POST');

it('admin uses real directory and sends a single assignment before reading latest state', async () => {
  blockedWrite = true;
  mount();
  const user = userEvent.setup();
  await user.selectOptions(
    await screen.findByLabelText('维修人员', { exact: false }),
    '32',
  );
  await user.click(screen.getByRole('button', { name: /^派单$/ }));
  expect(screen.getByRole('button', { name: /^派单$/ })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: /^派单$/ }));
  expect(writes()).toHaveLength(1);
  expect(writes()[0][1]?.body).toBe('{"workerId":"32"}');
  holdWrite!(reply(null, 204));
  expect(
    await screen.findByText('操作完成，已读取最新工单。'),
  ).toBeInTheDocument();
  expect(screen.getByText('待维修')).toBeInTheDocument();
  expect(
    screen.queryByRole('button', { name: /^派单$/ }),
  ).not.toBeInTheDocument();
});
it('worker starts and submits a result using server capabilities and history', async () => {
  role = 'WORKER';
  orderStatus = 'PENDING_REPAIR';
  mount();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: '开始维修' }));
  await user.type(await screen.findByLabelText('维修说明'), '已更换零件');
  await user.click(screen.getByRole('button', { name: '提交维修结果' }));
  expect(await screen.findByText('待确认')).toBeInTheDocument();
  expect(screen.getByText('已更换零件')).toBeInTheDocument();
  expect(writes().map((c) => c[0])).toEqual([
    '/api/v1/orders/101/start',
    '/api/v1/orders/101/complete',
  ]);
});
it('student confirms the server repair result and reloads the real timeline', async () => {
  role = 'STUDENT';
  orderStatus = 'PENDING_CONFIRMATION';
  mount();
  const user = userEvent.setup();
  expect(await screen.findByText('已更换零件')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '确认维修完成' }));
  expect(await screen.findByText('已完成')).toBeInTheDocument();
  expect(screen.getByText(/确认完成：/)).toBeInTheDocument();
  const timelineHeading = screen.getByRole('heading', { name: '处理记录' });
  expect(timelineHeading.parentElement).toHaveTextContent('确认维修完成');
  expect(writes()).toHaveLength(1);
});
it.each([403, 409])(
  'a %s failed write is not success or a retry and requires an explicit read',
  async (status) => {
    role = 'WORKER';
    orderStatus = 'PENDING_REPAIR';
    writeFailure = status;
    mount();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '开始维修' }));
    expect(
      await screen.findByText(new RegExp(String(status) + ' ·')),
    ).toBeInTheDocument();
    expect(
      screen.queryByText('操作完成，已读取最新工单。'),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '开始维修' })).toBeDisabled();
    expect(writes()).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: '重新读取工单状态' }));
    if (status === 403) {
      await waitFor(() =>
        expect(screen.getByRole('button', { name: '开始维修' })).toBeDisabled(),
      );
      await user.click(
        screen.getByRole('button', { name: '重新确认身份／CSRF' }),
      );
    }
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '开始维修' })).toBeEnabled(),
    );
    expect(writes()).toHaveLength(1);
  },
);
it('CSRF block survives detail reads and partial refresh until both identity and token succeed', async () => {
  role = 'WORKER';
  orderStatus = 'PENDING_REPAIR';
  writeFailure = 403;
  mount();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: '开始维修' }));
  await screen.findByText(/403 · CSRF_REJECTED/);
  await user.click(screen.getByRole('button', { name: '重新读取工单状态' }));
  await screen.findByText('服务器工单内容');
  expect(screen.getByRole('button', { name: '开始维修' })).toBeDisabled();
  const handler = transport.getMockImplementation()!;
  let failIdentity = true;
  transport.mockImplementation(async (input, options) =>
    failIdentity && String(input).endsWith('/me')
      ? problem(503, 'TEMPORARILY_UNAVAILABLE')
      : handler(input, options),
  );
  await user.click(screen.getByRole('button', { name: '重新确认身份／CSRF' }));
  await screen.findByText(/503 · TEMPORARILY_UNAVAILABLE/);
  expect(screen.getByRole('button', { name: '开始维修' })).toBeDisabled();
  expect(writes()).toHaveLength(1);
  failIdentity = false;
  writeFailure = undefined;
  await user.click(screen.getByRole('button', { name: '重新确认身份／CSRF' }));
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '开始维修' })).toBeEnabled(),
  );
  expect(writes()).toHaveLength(1);
  await user.click(screen.getByRole('button', { name: '开始维修' }));
  await screen.findByLabelText('维修说明');
  expect(writes()).toHaveLength(2);
});
it('admin detail remains visible while the directory is pending or failed and selection is mandatory', async () => {
  const handler = transport.getMockImplementation()!;
  let directoryReady = false;
  let deliver!: (response: Response) => void;
  transport.mockImplementation(async (input, options) => {
    if (String(input).endsWith('/admin/workers') && !directoryReady)
      return new Promise((resolve) => {
        deliver = resolve;
      });
    return handler(input, options);
  });
  mount();
  const user = userEvent.setup();
  expect(await screen.findByText('服务器工单内容')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /^派单$/ })).toBeDisabled();
  deliver(problem(503, 'DIRECTORY_UNAVAILABLE'));
  await screen.findByText(/503 · DIRECTORY_UNAVAILABLE/);
  expect(screen.getByText('服务器工单内容')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /^派单$/ })).toBeDisabled();
  expect(writes()).toHaveLength(0);
  directoryReady = true;
  await user.click(screen.getByRole('button', { name: '重新读取工单状态' }));
  await screen.findByRole('option', { name: '数据库维修员 · worker_test' });
  expect(screen.getByRole('button', { name: /^派单$/ })).toBeDisabled();
  await user.selectOptions(
    screen.getByLabelText('维修人员', { exact: false }),
    '32',
  );
  expect(screen.getByRole('button', { name: /^派单$/ })).toBeEnabled();
  await user.click(screen.getByRole('button', { name: /^派单$/ }));
  await screen.findByText('待维修');
  expect(writes()).toHaveLength(1);
});
it('401 on a worker write clears private data and shows login', async () => {
  role = 'WORKER';
  orderStatus = 'PENDING_REPAIR';
  writeFailure = 401;
  mount();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: '开始维修' }));
  expect(
    await screen.findByRole('form', { name: '账号登录' }),
  ).toBeInTheDocument();
  expect(screen.queryByText('服务器工单内容')).not.toBeInTheDocument();
  expect(writes()).toHaveLength(1);
});
it('role change for the same account remounts the workspace instead of keeping admin data', async () => {
  mount('/');
  await screen.findByRole('link', { name: '查看详情：#101 · ADMIN列表地点' });
  role = 'WORKER';
  fireEvent(window, new Event('focus'));
  expect(
    await screen.findByRole('link', {
      name: '查看详情：#101 · WORKER列表地点',
    }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole('link', { name: '查看详情：#101 · ADMIN列表地点' }),
  ).not.toBeInTheDocument();
  expect(
    screen.getByRole('heading', { name: '维修工作台' }),
  ).toBeInTheDocument();
});

it('keeps keyboard focus inside detail page instead of dropping to body after delayed detail load', async () => {
  let releaseDetail!: (res: Response) => void;
  const baseImpl = transport.getMockImplementation()!;
  transport.mockImplementation(async (input, options) => {
    const u = new URL(String(input), 'https://local.invalid');
    if (
      (options?.method ?? 'GET') === 'GET' &&
      /\/orders\/101$/.test(u.pathname)
    ) {
      return new Promise((resolve) => {
        releaseDetail = resolve;
      });
    }
    return baseImpl(input, options);
  });
  mount('/');
  const user = userEvent.setup();
  const link = await screen.findByRole('link', {
    name: '查看详情：#101 · ADMIN列表地点',
  });
  link.focus();
  await user.keyboard('{Enter}');
  expect(await screen.findByText('正在读取…')).toBeInTheDocument();
  await waitFor(() => expect(releaseDetail).toBeDefined());
  releaseDetail(reply(detail()));
  expect(await screen.findByText('服务器工单内容')).toBeInTheDocument();
  const detailHeading = screen.getByRole('heading', { name: '报修详情' });
  await waitFor(() => expect(detailHeading).toHaveFocus());
  expect(document.activeElement).not.toBe(document.body);
});
it('initial identity refresh and pagination/filters use backend parameters', async () => {
  pageCount = 2;
  mount('/');
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: '下一页' }));
  await waitFor(() =>
    expect(screen.getByText('第 2 页 / 共 2 页')).toBeInTheDocument(),
  );
  await user.type(screen.getByLabelText('工单编号或地点'), 'A&B');
  await user.selectOptions(screen.getByLabelText('工单状态'), 'PENDING_REPAIR');
  await user.click(screen.getByRole('button', { name: '应用筛选' }));
  await waitFor(() => {
    const c = transport.mock.calls.at(-1);
    const u = new URL(String(c?.[0]), 'https://local.invalid');
    expect(u.pathname).toBe('/api/v1/admin/orders');
    expect(u.searchParams.get('page')).toBe('0');
    expect(u.searchParams.get('q')).toBe('A&B');
    expect(u.searchParams.get('status')).toBe('PENDING_REPAIR');
  });
  expect(writes()).toHaveLength(0);
});
it('a server 404 is visible without an operation button', async () => {
  transport.mockImplementation(async (input) =>
    String(input).endsWith('/auth/csrf')
      ? reply({ headerName: 'X-CSRF-TOKEN', token: 'test' })
      : String(input).endsWith('/me')
        ? reply({
            id: '9',
            username: 'fictional',
            displayName: '虚构',
            role: 'WORKER',
          })
        : problem(404, 'ORDER_NOT_FOUND'),
  );
  mount();
  expect(await screen.findByText(/404 · ORDER_NOT_FOUND/)).toBeInTheDocument();
  expect(
    screen.queryByRole('button', { name: '开始维修' }),
  ).not.toBeInTheDocument();
});
it('network failure leaves writes blocked and never fabricates success', async () => {
  role = 'WORKER';
  orderStatus = 'PENDING_REPAIR';
  const handler = transport.getMockImplementation()!;
  transport.mockImplementation(async (input, options) => {
    if (options?.method === 'POST') throw Error('network down');
    return handler(input, options);
  });
  mount();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: '开始维修' }));
  expect(await screen.findByText(/网络响应未知/)).toBeInTheDocument();
  expect(writes()).toHaveLength(1);
  expect(screen.getByRole('button', { name: '开始维修' })).toBeDisabled();
});

it('student cancellation confirms by keyboard, sends once, and reads the terminal state', async () => {
  role = 'STUDENT';
  blockedWrite = true;
  mount();
  const user = userEvent.setup();
  const cancel = await screen.findByRole('button', { name: '撤销报修' });
  await user.click(cancel);
  expect(screen.getByRole('button', { name: '保留工单' })).toHaveFocus();
  expect(writes()).toHaveLength(0);
  await user.keyboard('{Escape}');
  expect(cancel).toHaveFocus();
  expect(
    screen.queryByRole('group', { name: '确认撤销这条报修？' }),
  ).not.toBeInTheDocument();
  await user.keyboard('{Enter}');
  await user.tab();
  expect(screen.getByRole('button', { name: '确认撤销' })).toHaveFocus();
  await user.keyboard('{Enter}');
  expect(cancel).toBeDisabled();
  const submit = screen.getByRole('button', { name: '确认撤销' });
  expect(submit).toBeDisabled();
  fireEvent.click(submit);
  expect(writes()).toHaveLength(1);
  expect(writes()[0][0]).toBe('/api/v1/orders/101/cancel');
  expect(writes()[0][1]?.body).toBe('{}');
  holdWrite!(reply(null, 204));
  await screen.findByText('已取消');
  const timelineHeading = screen.getByRole('heading', { name: '处理记录' });
  expect(timelineHeading.parentElement).toHaveTextContent('撤销工单');
  expect(
    screen.queryByRole('button', { name: '撤销报修' }),
  ).not.toBeInTheDocument();
  await waitFor(() =>
    expect(
      screen.getByText('操作完成，已读取最新工单。').parentElement,
    ).toHaveFocus(),
  );
});

it.each([401, 403, 404, 409])(
  'student cancellation handles %s without success or automatic replay',
  async (status) => {
    role = 'STUDENT';
    writeFailure = status;
    mount();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '撤销报修' }));
    await user.click(screen.getByRole('button', { name: '确认撤销' }));
    if (status === 401) {
      await screen.findByRole('form', { name: '账号登录' });
      expect(screen.queryByText('服务器工单内容')).not.toBeInTheDocument();
      expect(await screen.findByRole('alert')).toHaveTextContent('order-401');
      await user.type(screen.getByLabelText('用户名'), 'fictional');
      await user.type(screen.getByLabelText('密码'), 'test-only');
      await user.click(screen.getByRole('button', { name: '登录' }));
      expect(await screen.findByText('服务器工单内容')).toBeInTheDocument();
      expect(screen.queryByText('order-401')).not.toBeInTheDocument();
    } else {
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent(String(status) + ' ·');
      expect(alert).toHaveTextContent(`order-${status}`);
      expect(alert.parentElement).toHaveFocus();
      expect(screen.getByRole('button', { name: '撤销报修' })).toBeDisabled();
    }
    expect(
      screen.queryByText('操作完成，已读取最新工单。'),
    ).not.toBeInTheDocument();
    expect(writes().map(([route]) => route)).toEqual(
      status === 401
        ? ['/api/v1/orders/101/cancel', '/api/v1/auth/login']
        : ['/api/v1/orders/101/cancel'],
    );
  },
);

it('an assignment conflict rereads capabilities and removes cancellation without retrying', async () => {
  role = 'STUDENT';
  writeFailure = 409;
  mount();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: '撤销报修' }));
  await user.click(screen.getByRole('button', { name: '确认撤销' }));
  await screen.findByText(/409 · ORDER_CONFLICT/);
  orderStatus = 'PENDING_REPAIR';
  await user.click(screen.getByRole('button', { name: '重新读取工单状态' }));
  await screen.findByText('待维修');
  expect(
    screen.queryByRole('button', { name: '撤销报修' }),
  ).not.toBeInTheDocument();
  expect(writes()).toHaveLength(1);
});

it('an unknown cancellation result keeps writes blocked until a manual state read', async () => {
  role = 'STUDENT';
  const handler = transport.getMockImplementation()!;
  transport.mockImplementation(async (input, options) => {
    if (options?.method === 'POST') {
      orderStatus = 'CANCELLED';
      throw Error('response lost after commit');
    }
    return handler(input, options);
  });
  mount();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: '撤销报修' }));
  await user.click(screen.getByRole('button', { name: '确认撤销' }));
  await screen.findByText(/网络响应未知/);
  expect(screen.getByRole('button', { name: '撤销报修' })).toBeDisabled();
  expect(
    screen.queryByText('操作完成，已读取最新工单。'),
  ).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '重新读取工单状态' }));
  await screen.findByText('已取消');
  expect(
    screen.queryByRole('button', { name: '撤销报修' }),
  ).not.toBeInTheDocument();
  expect(writes()).toHaveLength(1);
});

it('a successful cancellation with a failed detail read stays unresolved until manual reread', async () => {
  role = 'STUDENT';
  const handler = transport.getMockImplementation()!;
  let failedRead = false;
  transport.mockImplementation(async (input, options) => {
    if (
      orderStatus === 'CANCELLED' &&
      !failedRead &&
      String(input).endsWith('/101')
    ) {
      failedRead = true;
      return problem(503, 'TEMPORARILY_UNAVAILABLE');
    }
    return handler(input, options);
  });
  mount();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: '撤销报修' }));
  await user.click(screen.getByRole('button', { name: '确认撤销' }));
  await screen.findByText(/503 · TEMPORARILY_UNAVAILABLE/);
  expect(screen.getByRole('button', { name: '撤销报修' })).toBeDisabled();
  expect(
    screen.queryByText('操作完成，已读取最新工单。'),
  ).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '重新读取工单状态' }));
  await screen.findByText('已取消');
  expect(writes()).toHaveLength(1);
});

it.each(['ADMIN', 'WORKER'] as const)(
  'a forged CANCEL capability is hidden from %s',
  async (actor) => {
    role = actor;
    const handler = transport.getMockImplementation()!;
    transport.mockImplementation(async (input, options) =>
      String(input).endsWith('/101')
        ? reply({ ...detail(), availableActions: ['CANCEL'] })
        : handler(input, options),
    );
    mount();
    await screen.findByText('服务器工单内容');
    expect(
      screen.queryByRole('button', { name: '撤销报修' }),
    ).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  },
);

it('search errors are associated with the field and issue no request', async () => {
  mount('/');
  const user = userEvent.setup();
  await screen.findByRole('link', { name: '查看详情：#101 · ADMIN列表地点' });
  const before = transport.mock.calls.length;
  const search = screen.getByLabelText('工单编号或地点');
  await user.type(search, 'x'.repeat(121));
  await user.click(screen.getByRole('button', { name: '应用筛选' }));
  expect(search).toHaveAttribute('aria-invalid', 'true');
  expect(search).toHaveFocus();
  expect(search).toHaveAccessibleDescription(
    screen.getByRole('alert').textContent!,
  );
  expect(transport).toHaveBeenCalledTimes(before);
});

it('repair result validation focuses and describes the invalid field without a write', async () => {
  role = 'WORKER';
  orderStatus = 'IN_PROGRESS';
  mount();
  const user = userEvent.setup();
  const result = await screen.findByLabelText('维修说明');
  fireEvent.change(result, { target: { value: 'x'.repeat(1001) } });
  await user.click(screen.getByRole('button', { name: '提交维修结果' }));
  expect(result).toHaveFocus();
  expect(result).toHaveAttribute('aria-invalid', 'true');
  expect(result).toHaveAccessibleDescription(
    expect.stringContaining(screen.getByRole('alert').textContent!),
  );
  expect(writes()).toHaveLength(0);
});
