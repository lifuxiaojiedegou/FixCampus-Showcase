import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import App from '../app/App';
import type { NotificationItem, Role } from '../shared/api/contracts';

const transport = vi.mocked(fetch);

const reply = (body: unknown, status = 200) =>
  new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: {
      'Content-Type':
        status >= 400 ? 'application/problem+json' : 'application/json',
    },
  });

const problem = (status: number, code: string, message = code) =>
  reply({ status, code, message, requestId: `notif-${status}` }, status);

let currentUser: {
  id: string;
  username: string;
  displayName: string;
  role: Role;
};
let notifications: NotificationItem[];
let markFailure: { status: number; code: string; message: string } | null;

function makeItem(
  id: string,
  orderId: string,
  overrides: Partial<NotificationItem> = {},
): NotificationItem {
  return {
    id,
    orderId,
    eventType: 'ORDER_ASSIGNED',
    title: `通知标题 ${id}`,
    body: `工单 #${orderId} 状态已更新`,
    read: false,
    readAt: null,
    createdAt: '2026-11-26T15:00:00+08:00',
    ...overrides,
  };
}

function setupTransport() {
  transport.mockReset().mockImplementation(async (input, options) => {
    const u = new URL(String(input), 'https://local.invalid');
    const method = options?.method ?? 'GET';
    if (u.pathname === '/api/v1/auth/csrf') {
      return reply({ headerName: 'X-CSRF-TOKEN', token: 'notif-csrf' });
    }
    if (u.pathname === '/api/v1/me') {
      return reply(currentUser);
    }
    if (u.pathname === '/api/v1/notifications/unread-count') {
      const unreadCount = notifications.filter((n) => !n.read).length;
      return reply({ unreadCount });
    }
    if (method === 'GET' && u.pathname === '/api/v1/notifications') {
      const unreadOnly = u.searchParams.get('unreadOnly') === 'true';
      const page = Number(u.searchParams.get('page') ?? 0);
      const size = Number(u.searchParams.get('size') ?? 20);
      const filtered = unreadOnly
        ? notifications.filter((n) => !n.read)
        : notifications;
      const start = page * size;
      const items = filtered.slice(start, start + size);
      const totalElements = filtered.length;
      const totalPages =
        totalElements === 0 ? 0 : Math.ceil(totalElements / size);
      return reply({
        items,
        page,
        size,
        totalElements,
        totalPages,
        unreadCount: notifications.filter((n) => !n.read).length,
      });
    }
    if (method === 'POST' && u.pathname === '/api/v1/notifications/read-all') {
      if (markFailure) {
        return problem(
          markFailure.status,
          markFailure.code,
          markFailure.message,
        );
      }
      let markedCount = 0;
      notifications = notifications.map((n) => {
        if (n.read) return n;
        markedCount++;
        return { ...n, read: true, readAt: '2026-11-26T15:10:00+08:00' };
      });
      return reply({ markedCount, unreadCount: 0 });
    }
    if (
      method === 'POST' &&
      /^\/api\/v1\/notifications\/\d+\/read$/.test(u.pathname)
    ) {
      if (markFailure) {
        return problem(
          markFailure.status,
          markFailure.code,
          markFailure.message,
        );
      }
      const id = u.pathname.split('/')[4];
      const existing = notifications.find((n) => n.id === id);
      if (!existing) {
        return problem(404, 'NOTIFICATION_NOT_FOUND', '通知不存在或无权访问');
      }
      const readAt = existing.readAt ?? '2026-11-26T15:05:00+08:00';
      notifications = notifications.map((n) =>
        n.id === id ? { ...n, read: true, readAt } : n,
      );
      return reply({
        id,
        read: true,
        readAt,
        unreadCount: notifications.filter((n) => !n.read).length,
      });
    }
    if (u.pathname.endsWith('/orders')) {
      return reply({
        items: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
        sort: 'createdAt,desc',
      });
    }
    throw new Error(`Unexpected test request: ${method} ${u.pathname}`);
  });
}

beforeEach(() => {
  currentUser = {
    id: '20',
    username: 'student01',
    displayName: '演示学生',
    role: 'STUDENT',
  };
  notifications = [
    makeItem('101', '12', {
      eventType: 'REPAIR_COMPLETED',
      title: '维修已完成，请确认',
      body: '工单 #12（演示实验楼 D404）已完成维修，请核对结果并确认完成。',
    }),
    makeItem('100', '11', {
      eventType: 'ORDER_ASSIGNED',
      title: '报修单已派单',
      body: '工单 #11（演示宿舍 A101）已分配给维修人员。',
      read: true,
      readAt: '2026-11-26T14:30:00+08:00',
    }),
  ];
  markFailure = null;
  setupTransport();
});

afterEach(() => {
  vi.useRealTimers();
});

function mount(path = '/notifications') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

it('displays unread badge in navigation, filters unread items, and marks single item read', async () => {
  mount('/');
  const user = userEvent.setup();
  const navLink = await screen.findByRole('link', { name: /站内通知/ });
  expect(await screen.findByLabelText('有 1 条未读通知')).toHaveTextContent(
    '1',
  );

  await user.click(navLink);
  expect(
    await screen.findByRole('heading', { name: '站内通知' }),
  ).toBeInTheDocument();
  expect(await screen.findByText('维修已完成，请确认')).toBeInTheDocument();
  expect(screen.getByText('报修单已派单')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: '仅看未读' }));
  await waitFor(() =>
    expect(screen.queryByText('报修单已派单')).not.toBeInTheDocument(),
  );
  expect(screen.getByText('维修已完成，请确认')).toBeInTheDocument();

  await user.click(
    screen.getByRole('button', {
      name: '将「维修已完成，请确认」（工单 #12）标为已读',
    }),
  );
  expect(await screen.findByText('已标为已读。')).toBeInTheDocument();
  expect(await screen.findByText('当前没有未读通知。')).toBeInTheDocument();
  expect(screen.queryByLabelText(/未读通知/)).not.toBeInTheDocument();
});

it('marks all notifications read and links directly to the referenced order', async () => {
  notifications = [
    makeItem('201', '55', { title: '新维修任务待处理' }),
    makeItem('202', '56', {
      title: '学生已确认完成',
      eventType: 'ORDER_CONFIRMED',
    }),
  ];
  mount('/notifications');
  const user = userEvent.setup();
  expect(await screen.findByText('新维修任务待处理')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '查看工单 #55' })).toHaveAttribute(
    'href',
    '/orders/55',
  );
  expect(await screen.findByLabelText('有 2 条未读通知')).toHaveTextContent(
    '2',
  );

  await user.click(screen.getByRole('button', { name: '全部标为已读' }));
  expect(
    await screen.findByText('已将 2 条通知标为已读。'),
  ).toBeInTheDocument();
  expect(screen.queryByLabelText(/未读通知/)).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '全部标为已读' })).toBeDisabled();
});

it('surfaces 404 NOTIFICATION_NOT_FOUND when marking read fails without faking success', async () => {
  markFailure = {
    status: 404,
    code: 'NOTIFICATION_NOT_FOUND',
    message: '通知不存在或无权访问',
  };
  mount('/notifications');
  const user = userEvent.setup();
  await screen.findByText('维修已完成，请确认');
  await user.click(
    screen.getByRole('button', {
      name: '将「维修已完成，请确认」（工单 #12）标为已读',
    }),
  );
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'NOTIFICATION_NOT_FOUND',
  );
  expect(screen.getByLabelText('有 1 条未读通知')).toBeInTheDocument();
});

it('polls unread count every 30s while visible and pauses when document is hidden', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  mount('/');
  await waitFor(() =>
    expect(screen.getByLabelText('有 1 条未读通知')).toHaveTextContent('1'),
  );

  notifications = [
    ...notifications,
    makeItem('105', '15', { title: '新派单提醒' }),
  ];
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30_000);
  });
  await waitFor(() =>
    expect(screen.getByLabelText('有 2 条未读通知')).toHaveTextContent('2'),
  );

  const pollCallsBeforeHide = transport.mock.calls.filter((c) =>
    String(c[0]).includes('/notifications/unread-count'),
  ).length;
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    value: 'hidden',
  });
  try {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    const pollCallsWhileHidden = transport.mock.calls.filter((c) =>
      String(c[0]).includes('/notifications/unread-count'),
    ).length;
    expect(pollCallsWhileHidden).toBe(pollCallsBeforeHide);
  } finally {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      value: 'visible',
    });
  }
});

it('clears previous account unread badge when identity switches on window focus', async () => {
  mount('/');
  expect(await screen.findByLabelText('有 1 条未读通知')).toHaveTextContent(
    '1',
  );

  currentUser = {
    id: '21',
    username: 'worker01',
    displayName: '演示维修员',
    role: 'WORKER',
  };
  notifications = [];
  fireEvent(window, new Event('focus'));
  await waitFor(() =>
    expect(screen.getByText(/演示维修员/)).toBeInTheDocument(),
  );
  await waitFor(() =>
    expect(screen.queryByLabelText(/未读通知/)).not.toBeInTheDocument(),
  );
});

it('keeps old CSRF and pauses unread polling while /me is pending during cross-tab switch', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  let releaseMe: ((res: Response) => void) | null = null;
  let csrfSeq = 0;
  let phase: 'initial' | 'switching' = 'initial';

  transport.mockReset().mockImplementation(async (input, options) => {
    const u = new URL(String(input), 'https://local.invalid');
    const method = options?.method ?? 'GET';
    if (u.pathname === '/api/v1/auth/csrf') {
      csrfSeq++;
      return reply({
        headerName: 'X-CSRF-TOKEN',
        token: phase === 'initial' ? 'csrf-user-a' : `csrf-user-b-${csrfSeq}`,
      });
    }
    if (u.pathname === '/api/v1/me') {
      if (phase === 'switching') {
        return new Promise((resolve) => {
          releaseMe = resolve;
        });
      }
      return reply(currentUser);
    }
    if (u.pathname === '/api/v1/notifications/unread-count') {
      return reply({
        unreadCount: notifications.filter((n) => !n.read).length,
      });
    }
    if (method === 'GET' && u.pathname === '/api/v1/notifications') {
      return reply({
        items: notifications,
        page: 0,
        size: 20,
        totalElements: notifications.length,
        totalPages: notifications.length > 0 ? 1 : 0,
        unreadCount: notifications.filter((n) => !n.read).length,
      });
    }
    if (method === 'POST' && u.pathname === '/api/v1/notifications/read-all') {
      const sentCsrf = (options?.headers as Record<string, string>)[
        'X-CSRF-TOKEN'
      ];
      if (sentCsrf !== 'csrf-user-b-2') {
        return problem(403, 'CSRF_REJECTED', '请求被拒绝');
      }
      return reply({ markedCount: 1, unreadCount: 0 });
    }
    throw new Error(`Unexpected test request: ${method} ${u.pathname}`);
  });

  mount('/notifications');
  const user = userEvent.setup({
    advanceTimers: (ms) => vi.advanceTimersByTime(ms),
  });
  expect(await screen.findByText('维修已完成，请确认')).toBeInTheDocument();

  // Cross-tab login switched session to user B; focus starts refresh() whose /me is still pending.
  phase = 'switching';
  const pollsBeforeSwitch = transport.mock.calls.filter((c) =>
    String(c[0]).includes('/notifications/unread-count'),
  ).length;
  fireEvent(window, new Event('focus'));
  await waitFor(() => expect(releaseMe).not.toBeNull());

  // Timer tick while /me is pending must not poll unread-count.
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30_000);
  });
  const pollsWhilePending = transport.mock.calls.filter((c) =>
    String(c[0]).includes('/notifications/unread-count'),
  ).length;
  expect(pollsWhilePending).toBe(pollsBeforeSwitch);

  // Clicking mark-all while /me is pending still carries A's old CSRF (rejected 403), never B's new CSRF.
  await user.click(screen.getByRole('button', { name: '全部标为已读' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('CSRF_REJECTED');
  const readAllCall = transport.mock.calls.find((c) =>
    String(c[0]).endsWith('/notifications/read-all'),
  );
  expect(
    (readAllCall?.[1]?.headers as Record<string, string>)['X-CSRF-TOKEN'],
  ).toBe('csrf-user-a');

  // Completing /me with user B remounts with B's state cleanly.
  notifications = [];
  releaseMe!(
    reply({
      id: '21',
      username: 'worker01',
      displayName: '演示维修员',
      role: 'WORKER',
    }),
  );
  await waitFor(() =>
    expect(screen.getByText(/演示维修员/)).toBeInTheDocument(),
  );
  await waitFor(() =>
    expect(screen.queryByLabelText(/未读通知/)).not.toBeInTheDocument(),
  );
});

it('discards a stale unread poll that resolves after mark-as-read succeeds', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  let releaseDelayedPoll: ((res: Response) => void) | null = null;
  let pollCalls = 0;

  transport.mockReset().mockImplementation(async (input, options) => {
    const u = new URL(String(input), 'https://local.invalid');
    const method = options?.method ?? 'GET';
    if (u.pathname === '/api/v1/auth/csrf') {
      return reply({ headerName: 'X-CSRF-TOKEN', token: 'notif-csrf' });
    }
    if (u.pathname === '/api/v1/me') {
      return reply(currentUser);
    }
    if (u.pathname === '/api/v1/notifications/unread-count') {
      pollCalls++;
      if (pollCalls === 2) {
        return new Promise((resolve) => {
          releaseDelayedPoll = resolve;
        });
      }
      return reply({
        unreadCount: notifications.filter((n) => !n.read).length,
      });
    }
    if (method === 'GET' && u.pathname === '/api/v1/notifications') {
      return reply({
        items: notifications,
        page: 0,
        size: 20,
        totalElements: notifications.length,
        totalPages: 1,
        unreadCount: notifications.filter((n) => !n.read).length,
      });
    }
    if (method === 'POST' && u.pathname === '/api/v1/notifications/101/read') {
      notifications = notifications.map((n) =>
        n.id === '101'
          ? { ...n, read: true, readAt: '2026-11-26T15:05:00+08:00' }
          : n,
      );
      return reply({
        id: '101',
        read: true,
        readAt: '2026-11-26T15:05:00+08:00',
        unreadCount: 0,
      });
    }
    throw new Error(`Unexpected test request: ${method} ${u.pathname}`);
  });

  mount('/notifications');
  const user = userEvent.setup({
    advanceTimers: (ms) => vi.advanceTimersByTime(ms),
  });
  expect(await screen.findByLabelText('有 1 条未读通知')).toHaveTextContent(
    '1',
  );

  // Trigger a second poll and keep it in-flight while the user marks the item read.
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30_000);
  });
  await waitFor(() => expect(releaseDelayedPoll).not.toBeNull());

  await user.click(
    screen.getByRole('button', {
      name: '将「维修已完成，请确认」（工单 #12）标为已读',
    }),
  );
  expect(await screen.findByText('已标为已读。')).toBeInTheDocument();
  expect(screen.queryByLabelText(/未读通知/)).not.toBeInTheDocument();

  // Resolving the stale pre-write poll must NOT overwrite the unread count back to 1.
  releaseDelayedPoll!(reply({ unreadCount: 1 }));
  await act(async () => {
    await Promise.resolve();
  });
  expect(screen.queryByLabelText(/未读通知/)).not.toBeInTheDocument();
  expect(screen.getByText(/当前未读：0 条/)).toBeInTheDocument();
});

it('recovers from emptying the last unread page and keeps notification errors scoped to notifications', async () => {
  notifications = Array.from({ length: 21 }, (_, idx) =>
    makeItem(String(300 - idx), String(50 + idx), {
      title: `未读通知 ${idx + 1}`,
    }),
  );
  mount('/notifications');
  const user = userEvent.setup();
  expect(await screen.findByText('未读通知 1')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '仅看未读' }));
  await user.click(screen.getByRole('button', { name: '下一页' }));
  expect(await screen.findByText('未读通知 21')).toBeInTheDocument();

  await user.click(
    screen.getByRole('button', {
      name: '将「未读通知 21」（工单 #70）标为已读',
    }),
  );
  // Automatically steps back to page 1 with the remaining 20 unread items.
  expect(await screen.findByText('未读通知 1')).toBeInTheDocument();
  expect(screen.getByText(/当前未读：20 条/)).toBeInTheDocument();

  // Notification 503 error points user to refresh notifications, not orders.
  markFailure = {
    status: 503,
    code: 'TEMPORARILY_UNAVAILABLE',
    message: '服务暂不可用',
  };
  await user.click(
    screen.getByRole('button', {
      name: '将「未读通知 1」（工单 #50）标为已读',
    }),
  );
  expect(await screen.findByRole('alert')).toHaveTextContent('刷新通知列表');
});

it('does not stall in loading if filter resets to page 0 while mark-all-read from page 2 is in flight', async () => {
  notifications = Array.from({ length: 21 }, (_, idx) =>
    makeItem(String(400 - idx), String(80 + idx), {
      title: `分页通知 ${idx + 1}`,
    }),
  );
  let releaseMarkAll: ((res: Response) => void) | null = null;
  const baseImpl = transport.getMockImplementation()!;
  transport.mockImplementation(async (input, options) => {
    const u = new URL(String(input), 'https://local.invalid');
    if (
      (options?.method ?? 'GET') === 'POST' &&
      u.pathname === '/api/v1/notifications/read-all'
    ) {
      return new Promise((resolve) => {
        releaseMarkAll = resolve;
      });
    }
    return baseImpl(input, options);
  });

  mount('/notifications');
  const user = userEvent.setup();
  expect(await screen.findByText('分页通知 1')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '下一页' }));
  expect(await screen.findByText('分页通知 21')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: '全部标为已读' }));
  await waitFor(() => expect(releaseMarkAll).not.toBeNull());
  // Switch filter to unread-only (resets page to 0) before POST resolves.
  await user.click(screen.getByRole('button', { name: '仅看未读' }));
  notifications = notifications.map((n) => ({
    ...n,
    read: true,
    readAt: '2026-11-26T15:10:00+08:00',
  }));
  releaseMarkAll!(reply({ markedCount: 21, unreadCount: 0 }));

  expect(
    await screen.findByText('已将 21 条通知标为已读。'),
  ).toBeInTheDocument();
  expect(await screen.findByText('当前没有未读通知。')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '刷新列表' })).toBeEnabled();
});

it('surfaces identity/CSRF recovery banner and blocks notification writes after 403 CSRF_REJECTED until refreshed', async () => {
  let rejectCsrf = true;
  const baseImpl = transport.getMockImplementation()!;
  transport.mockImplementation(async (input, options) => {
    const u = new URL(String(input), 'https://local.invalid');
    if (
      (options?.method ?? 'GET') === 'POST' &&
      u.pathname === '/api/v1/notifications/read-all' &&
      rejectCsrf
    ) {
      return problem(403, 'CSRF_REJECTED', '安全令牌失效');
    }
    return baseImpl(input, options);
  });

  mount('/notifications');
  const user = userEvent.setup();
  expect(await screen.findByText('维修已完成，请确认')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: '全部标为已读' }));
  expect(await screen.findByText(/CSRF_REJECTED/)).toBeInTheDocument();
  const recoverBtn = await screen.findByRole('button', {
    name: '重新确认身份／CSRF',
  });
  expect(screen.getByRole('button', { name: '全部标为已读' })).toBeDisabled();

  rejectCsrf = false;
  await user.click(recoverBtn);
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: '重新确认身份／CSRF' }),
    ).not.toBeInTheDocument(),
  );
  expect(screen.getByRole('button', { name: '全部标为已读' })).toBeEnabled();
});

it('keeps notification writes locked after unknown network error until list refresh succeeds', async () => {
  let failReadAll = true;
  let holdRefresh: ((res: Response) => void) | null = null;
  let failRefresh = false;
  let shouldHoldRefresh = false;
  const baseImpl = transport.getMockImplementation()!;
  transport.mockImplementation(async (input, options) => {
    const u = new URL(String(input), 'https://local.invalid');
    const method = options?.method ?? 'GET';
    if (
      method === 'POST' &&
      u.pathname === '/api/v1/notifications/read-all' &&
      failReadAll
    ) {
      throw new TypeError('Failed to fetch');
    }
    if (method === 'GET' && u.pathname === '/api/v1/notifications') {
      if (failRefresh) {
        return problem(503, 'TEMPORARILY_UNAVAILABLE', '服务繁忙');
      }
      if (shouldHoldRefresh) {
        shouldHoldRefresh = false;
        return new Promise((resolve) => {
          holdRefresh = resolve;
        });
      }
    }
    return baseImpl(input, options);
  });

  mount('/notifications');
  const user = userEvent.setup();
  expect(await screen.findByText('维修已完成，请确认')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: '全部标为已读' }));
  expect(await screen.findByText(/通知状态结果未知/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '全部标为已读' })).toBeDisabled();

  // Clicking refresh while GET is still pending must keep writes disabled
  shouldHoldRefresh = true;
  await user.click(screen.getByRole('button', { name: '刷新列表' }));
  await waitFor(() => expect(holdRefresh).not.toBeNull());
  expect(screen.getByRole('button', { name: '全部标为已读' })).toBeDisabled();

  // Failed refresh must also keep writes disabled
  failRefresh = true;
  holdRefresh!(problem(503, 'TEMPORARILY_UNAVAILABLE', '服务繁忙'));
  expect(
    await screen.findByText(/TEMPORARILY_UNAVAILABLE/),
  ).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '全部标为已读' })).toBeDisabled();

  // Only a successful list refresh clears the unknown-outcome lock
  failRefresh = false;
  failReadAll = false;
  await user.click(screen.getByRole('button', { name: '刷新列表' }));
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '全部标为已读' })).toBeEnabled(),
  );
  expect(screen.queryByText(/通知状态结果未知/)).not.toBeInTheDocument();
});

it('does not clear unknown-outcome lock when an earlier in-flight list GET resolves after the failed write', async () => {
  let holdEarlierGet: ((res: Response) => void) | null = null;
  let holdNextGet = false;
  let failReadAll = true;
  const baseImpl = transport.getMockImplementation()!;
  transport.mockImplementation(async (input, options) => {
    const u = new URL(String(input), 'https://local.invalid');
    const method = options?.method ?? 'GET';
    if (
      method === 'GET' &&
      u.pathname === '/api/v1/notifications' &&
      holdNextGet
    ) {
      holdNextGet = false;
      return new Promise((resolve) => {
        holdEarlierGet = resolve;
      });
    }
    if (
      method === 'POST' &&
      u.pathname === '/api/v1/notifications/read-all' &&
      failReadAll
    ) {
      throw new TypeError('Failed to fetch');
    }
    return baseImpl(input, options);
  });

  mount('/notifications');
  const user = userEvent.setup();
  expect(await screen.findByText('维修已完成，请确认')).toBeInTheDocument();

  // Start a list GET that stays in flight while user triggers mark-all-read
  holdNextGet = true;
  await user.click(screen.getByRole('button', { name: '刷新列表' }));
  await waitFor(() => expect(holdEarlierGet).not.toBeNull());

  await user.click(screen.getByRole('button', { name: '全部标为已读' }));
  expect(await screen.findByText(/通知状态结果未知/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '全部标为已读' })).toBeDisabled();

  // Earlier GET (started BEFORE the unknown-outcome write) now resolves -> must NOT unlock
  holdEarlierGet!(
    reply({
      items: notifications,
      page: 0,
      size: 20,
      totalElements: notifications.length,
      totalPages: 1,
      unreadCount: 1,
    }),
  );
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '刷新列表' })).toBeEnabled(),
  );
  expect(screen.getByText(/通知状态结果未知/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '全部标为已读' })).toBeDisabled();

  // Only a NEW refresh started AFTER the unknown-outcome write clears the lock
  failReadAll = false;
  await user.click(screen.getByRole('button', { name: '刷新列表' }));
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '全部标为已读' })).toBeEnabled(),
  );
  expect(screen.queryByText(/通知状态结果未知/)).not.toBeInTheDocument();
});
