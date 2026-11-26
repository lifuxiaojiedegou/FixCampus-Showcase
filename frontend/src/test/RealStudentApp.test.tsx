import { expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import App, { AppRouter } from '../app/App';
import { getDemoDisplayConfig } from '../app/demoConfig';
import { RealStudentApp } from '../pages/RealStudentApp';

it('creates through the real adapter and shows server detail without a Mock identity', async () => {
  const reply = (data: unknown, status = 200) =>
    new Response(JSON.stringify(data), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  vi.mocked(fetch)
    .mockReset()
    .mockResolvedValueOnce(reply({ headerName: 'X-CSRF-TOKEN', token: 'test' }))
    .mockResolvedValueOnce(
      reply({
        id: '9',
        username: 'test_student',
        displayName: '虚构测试',
        role: 'STUDENT',
      }),
    )
    .mockResolvedValueOnce(reply({ unreadCount: 0 }))
    .mockResolvedValueOnce(
      reply({ id: '910', status: 'PENDING_ASSIGNMENT' }, 201),
    )
    .mockResolvedValueOnce(
      reply({
        id: '910',
        location: '虚构实验室',
        category: 'OTHER',
        description: '测试描述',
        status: 'PENDING_ASSIGNMENT',
        createdAt: '2026-11-07T10:00:00+08:00',
        completedAt: null,
        repairResult: null,
        workerName: null,
        availableActions: ['CANCEL'],
        history: {
          incomplete: false,
          entries: [
            {
              action: 'SUBMITTED',
              occurredAt: '2026-11-07T10:00:00+08:00',
              actorRole: 'STUDENT',
              actorName: '虚构测试',
              detail: null,
              recorded: true,
            },
          ],
        },
      }),
    );
  render(
    <MemoryRouter initialEntries={['/new']}>
      <App />
    </MemoryRouter>,
  );
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText('报修地点', { exact: false }),
    '虚构实验室',
  );
  await user.selectOptions(
    screen.getByLabelText('故障类型', { exact: false }),
    'OTHER',
  );
  await user.type(
    screen.getByLabelText('问题描述', { exact: false }),
    '测试描述',
  );
  await user.click(
    screen.getByRole('button', { name: '提交报修／恢复原提交' }),
  );
  expect(await screen.findByText('#910 · 虚构实验室')).toBeInTheDocument();
  expect(screen.queryByText('选择一个虚构身份')).not.toBeInTheDocument();
  expect(vi.mocked(fetch).mock.calls[3][1]?.method).toBe('POST');
});

const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type':
        status >= 400 ? 'application/problem+json' : 'application/json',
    },
  });
const tokenReply = () => reply({ headerName: 'X-CSRF-TOKEN', token: 'test' });
const student = {
  id: '9',
  username: 'test_student',
  displayName: '旧学生',
  role: 'STUDENT',
};
const expired = () =>
  reply(
    {
      type: 'about:blank',
      title: '请登录',
      status: 401,
      code: 'AUTH_REQUIRED',
      message: '请登录',
      requestId: 'test',
    },
    401,
  );
const listReply = (location: string, totalPages = 1) =>
  reply({
    items: [
      {
        id: '910',
        location,
        category: 'OTHER',
        status: 'PENDING_ASSIGNMENT',
        createdAt: '2026-11-07T10:00:00+08:00',
      },
    ],
    page: 0,
    size: 20,
    totalElements: totalPages,
    totalPages,
    sort: 'createdAt,desc',
  });
function mount(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

it('session expiry while paging revokes identity and removes the previous visible list', async () => {
  const transport = vi
    .mocked(fetch)
    .mockReset()
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(listReply('旧身份私有地点', 2))
    .mockResolvedValueOnce(reply({ unreadCount: 0 }))
    .mockResolvedValueOnce(expired());
  mount('/');
  const user = userEvent.setup();
  expect(
    await screen.findByRole('link', {
      name: '查看详情：#910 · 旧身份私有地点',
    }),
  ).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '下一页' }));
  expect(
    await screen.findByRole('form', { name: '账号登录' }),
  ).toBeInTheDocument();
  expect(screen.queryByText(/旧学生/)).not.toBeInTheDocument();
  expect(
    screen.queryByRole('link', { name: '查看详情：#910 · 旧身份私有地点' }),
  ).not.toBeInTheDocument();
  expect(
    transport.mock.calls.filter((c) => c[1]?.method === 'POST'),
  ).toHaveLength(0);
});
it('another tab logout is detected on focus and clears the old detail without a write retry', async () => {
  const transport = vi
    .mocked(fetch)
    .mockReset()
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(
      reply({
        id: '910',
        location: '旧详情地点',
        status: 'PENDING_ASSIGNMENT',
        description: '旧身份私有描述',
        createdAt: '2026-11-07T10:00:00+08:00',
        history: { incomplete: false, entries: [] },
      }),
    )
    .mockResolvedValueOnce(reply({ unreadCount: 0 }))
    .mockResolvedValueOnce(
      reply({
        orderId: '910',
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
        canUpload: true,
      }),
    )
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(expired())
    .mockResolvedValueOnce(tokenReply());
  mount('/orders/910');
  expect(await screen.findByText('旧身份私有描述')).toBeInTheDocument();
  expect(await screen.findByText('暂无图片附件')).toBeInTheDocument();
  fireEvent(window, new Event('focus'));
  expect(
    await screen.findByRole('form', { name: '账号登录' }),
  ).toBeInTheDocument();
  expect(screen.queryByText('旧身份私有描述')).not.toBeInTheDocument();
  expect(screen.queryByText('暂无图片附件')).not.toBeInTheDocument();
  await waitFor(() => expect(transport).toHaveBeenCalledTimes(8));
  expect(
    transport.mock.calls.filter((c) => c[1]?.method === 'POST'),
  ).toHaveLength(0);
});
it('identity change on focus remounts the list so the previous account data cannot remain visible', async () => {
  vi.mocked(fetch)
    .mockReset()
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(listReply('A私有地点'))
    .mockResolvedValueOnce(reply({ unreadCount: 0 }))
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(
      reply({
        ...student,
        id: '10',
        displayName: '新学生',
        username: 'new_student',
      }),
    )
    .mockResolvedValueOnce(listReply('B私有地点'))
    .mockResolvedValueOnce(reply({ unreadCount: 0 }));
  mount('/');
  await screen.findByRole('link', { name: '查看详情：#910 · A私有地点' });
  fireEvent(window, new Event('focus'));
  expect(
    await screen.findByRole('link', { name: '查看详情：#910 · B私有地点' }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole('link', { name: '查看详情：#910 · A私有地点' }),
  ).not.toBeInTheDocument();
  expect(screen.queryByText(/旧学生/)).not.toBeInTheDocument();
});
it('a failed write keeps its original body and key through re-login and only recovers on explicit submit', async () => {
  const transport = vi
    .mocked(fetch)
    .mockReset()
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(reply({ unreadCount: 0 }))
    .mockResolvedValueOnce(expired())
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(reply({ unreadCount: 0 }));
  mount('/new');
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText('报修地点', { exact: false }),
    '保留原地点',
  );
  await user.selectOptions(
    screen.getByLabelText('故障类型', { exact: false }),
    'OTHER',
  );
  await user.type(
    screen.getByLabelText('问题描述', { exact: false }),
    '保留原描述',
  );
  await user.click(
    screen.getByRole('button', { name: '提交报修／恢复原提交' }),
  );
  await screen.findByRole('form', { name: '账号登录' });
  const original = transport.mock.calls[3][1];
  await user.type(
    screen.getByLabelText('用户名', { exact: false }),
    'test_student',
  );
  await user.type(screen.getByLabelText('密码', { exact: false }), 'test-only');
  await user.click(screen.getByRole('button', { name: /^登录$/ }));
  expect(
    await screen.findByLabelText('报修地点', { exact: false }),
  ).toHaveValue('保留原地点');
  expect(screen.getByLabelText('问题描述', { exact: false })).toHaveValue(
    '保留原描述',
  );
  expect(transport).toHaveBeenCalledTimes(9);
  transport
    .mockResolvedValueOnce(
      reply({ id: '910', status: 'PENDING_ASSIGNMENT' }, 201),
    )
    .mockResolvedValueOnce(
      reply({
        id: '910',
        location: '保留原地点',
        description: '保留原描述',
        status: 'PENDING_ASSIGNMENT',
        createdAt: '2026-11-07T10:00:00+08:00',
        history: { incomplete: false, entries: [] },
      }),
    );
  await user.click(
    screen.getByRole('button', { name: '提交报修／恢复原提交' }),
  );
  await screen.findByRole('heading', { name: '#910 · 保留原地点' });
  const recovered = transport.mock.calls[9][1];
  expect(recovered?.body).toBe(original?.body);
  expect(
    (recovered?.headers as Record<string, string>)['Idempotency-Key'],
  ).toBe((original?.headers as Record<string, string>)['Idempotency-Key']);
});

it('keyboard navigation focuses the route heading and marks the current page', async () => {
  vi.mocked(fetch)
    .mockReset()
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(listReply('虚构导航地点'))
    .mockResolvedValueOnce(reply({ unreadCount: 0 }));
  mount('/');
  const user = userEvent.setup();
  const heading = await screen.findByRole('heading', { name: '我的工单' });
  expect(heading).toHaveFocus();
  expect(screen.getByRole('link', { name: '工单工作台' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  screen.getByRole('link', { name: '提交报修' }).focus();
  await user.keyboard('{Enter}');
  expect(
    await screen.findByRole('heading', { name: '提交报修' }),
  ).toHaveFocus();
  expect(screen.getByRole('link', { name: '提交报修' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  expect(screen.getByRole('link', { name: '跳到工作内容' })).toHaveAttribute(
    'href',
    '#real-content',
  );
});

it('create errors describe every invalid field and focus the first without a write', async () => {
  const transport = vi
    .mocked(fetch)
    .mockReset()
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(reply({ unreadCount: 0 }));
  mount('/new');
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole('button', { name: '提交报修／恢复原提交' }),
  );
  const location = screen.getByLabelText('报修地点', { exact: false });
  await waitFor(() => expect(location).toHaveFocus());
  for (const label of ['报修地点', '故障类型', '问题描述']) {
    const input = screen.getByLabelText(label, { exact: false });
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAttribute('aria-required', 'true');
    expect(input).toHaveAccessibleDescription();
  }
  expect(transport).toHaveBeenCalledTimes(3);
  expect(
    transport.mock.calls.filter((c) => c[1]?.method === 'POST'),
  ).toHaveLength(0);
});

it('login blocks repeated submits, then shows a copyable request number after rejection', async () => {
  let finish!: (response: Response) => void;
  const transport = vi
    .mocked(fetch)
    .mockReset()
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(expired())
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(tokenReply())
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
  mount('/');
  const user = userEvent.setup();
  const copy = vi.spyOn(navigator.clipboard, 'writeText');
  const form = await screen.findByRole('form', { name: '账号登录' });
  await user.type(screen.getByLabelText('用户名'), 'test_student');
  await user.type(screen.getByLabelText('密码'), 'test-only');
  await user.click(screen.getByRole('button', { name: '登录' }));
  await waitFor(() => expect(transport).toHaveBeenCalledTimes(5));
  expect(form).toHaveAttribute('aria-busy', 'true');
  expect(screen.getByLabelText('用户名')).toBeDisabled();
  fireEvent.submit(form);
  expect(transport).toHaveBeenCalledTimes(5);
  const rejected = reply(
    {
      status: 401,
      code: 'INVALID_CREDENTIALS',
      message: '测试拒绝',
      requestId: 'body-request',
    },
    401,
  );
  rejected.headers.set('X-Request-Id', 'login-request-42');
  finish(rejected);
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'login-request-42',
  );
  await user.click(screen.getByRole('button', { name: '复制请求编号' }));
  expect(copy).toHaveBeenCalledWith('login-request-42');
  expect(await screen.findByText('已复制请求编号')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '登录' })).toBeEnabled();
  expect(screen.getByLabelText('用户名')).toBeEnabled();
  expect(form).toHaveAttribute('aria-busy', 'false');
  expect(
    transport.mock.calls.filter((call) => call[1]?.method === 'POST'),
  ).toHaveLength(1);
});

it.each(['/app/real', '/app/real/orders/910'])(
  'redirects %s to /app/ and shows the real login',
  async (path) => {
    vi.mocked(fetch)
      .mockReset()
      .mockResolvedValueOnce(tokenReply())
      .mockResolvedValueOnce(expired())
      .mockResolvedValueOnce(tokenReply());
    const originalUrl = window.location.href;
    const originalState = window.history.state;
    window.history.replaceState(null, '', path);
    const view = render(<AppRouter />);
    try {
      expect(
        await screen.findByRole('form', { name: '账号登录' }),
      ).toBeInTheDocument();
      expect(window.location.pathname).toBe('/app/');
      expect(screen.queryByText(/返回 Mock/)).not.toBeInTheDocument();
      expect(screen.queryByText(/选择一个虚构身份/)).not.toBeInTheDocument();
    } finally {
      view.unmount();
      window.history.replaceState(originalState, '', originalUrl);
    }
  },
);

it('hides demo account details in prod mode and shows them only in demo mode', async () => {
  vi.mocked(fetch)
    .mockReset()
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(expired())
    .mockResolvedValueOnce(tokenReply());
  const prodView = render(
    <MemoryRouter initialEntries={['/']}>
      <RealStudentApp demoConfig={getDemoDisplayConfig('production', {})} />
    </MemoryRouter>,
  );
  await screen.findByRole('form', { name: '账号登录' });
  expect(screen.queryByText(/演示数据会定期清空/)).not.toBeInTheDocument();
  expect(screen.queryByText('student01')).not.toBeInTheDocument();
  expect(screen.queryByText('admin01')).not.toBeInTheDocument();
  expect(screen.queryByText('worker01')).not.toBeInTheDocument();
  prodView.unmount();

  vi.mocked(fetch)
    .mockReset()
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(expired())
    .mockResolvedValueOnce(tokenReply());
  const demo = getDemoDisplayConfig('demo', {
    VITE_DEMO_ACCESS_HINT: '演示密码请向面试联系人获取',
  });
  render(
    <MemoryRouter initialEntries={['/']}>
      <RealStudentApp demoConfig={demo} />
    </MemoryRouter>,
  );
  expect(
    await screen.findByRole('form', { name: '账号登录' }),
  ).toBeInTheDocument();
  expect(screen.getByText(/演示数据会定期清空/)).toBeInTheDocument();
  expect(screen.getByText('student01')).toBeInTheDocument();
  expect(screen.getByText('admin01')).toBeInTheDocument();
  expect(screen.getByText('worker01')).toBeInTheDocument();
  expect(screen.getByText('演示密码请向面试联系人获取')).toBeInTheDocument();
});

it('surfaces identity/CSRF recovery banner after unknown create response and replays same Idempotency-Key', async () => {
  const transport = vi
    .mocked(fetch)
    .mockReset()
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(reply({ unreadCount: 0 }))
    .mockRejectedValueOnce(new TypeError('Failed to fetch'))
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(reply({ id: '912' }, 201))
    .mockResolvedValueOnce(
      reply({
        id: '912',
        location: '未知恢复地点',
        category: 'NETWORK',
        description: '未知恢复描述',
        status: 'PENDING_ASSIGNMENT',
        createdAt: '2026-11-07T10:00:00+08:00',
        completedAt: null,
        workerName: null,
        repairResult: null,
        availableActions: ['CANCEL'],
        history: { incomplete: false, entries: [] },
      }),
    )
    .mockResolvedValueOnce(
      reply({
        orderId: '912',
        items: [],
        limits: {
          maxFileBytes: 2097152,
          maxWidth: 4096,
          maxHeight: 4096,
          maxPixels: 16777216,
          maxStudentAttachments: 3,
          maxWorkerAttachments: 2,
        },
        canUpload: true,
      }),
    );
  mount('/new');
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText('报修地点', { exact: false }),
    '未知恢复地点',
  );
  await user.selectOptions(
    screen.getByLabelText('故障类型', { exact: false }),
    'NETWORK',
  );
  await user.type(
    screen.getByLabelText('问题描述', { exact: false }),
    '未知恢复描述',
  );
  await user.click(
    screen.getByRole('button', { name: '提交报修／恢复原提交' }),
  );
  expect(await screen.findByText(/网络响应未知/)).toBeInTheDocument();
  const submitBtn = screen.getByRole('button', {
    name: '提交报修／恢复原提交',
  });
  expect(submitBtn).toBeDisabled();
  fireEvent.click(submitBtn);
  expect(
    transport.mock.calls.filter(
      ([url, init]) =>
        String(url) === '/api/v1/orders' && init?.method === 'POST',
    ),
  ).toHaveLength(1);
  const recoveryBtn = await screen.findByRole('button', {
    name: '重新确认身份／CSRF',
  });
  await user.click(recoveryBtn);
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: '重新确认身份／CSRF' }),
    ).not.toBeInTheDocument(),
  );
  expect(submitBtn).toBeEnabled();
  await user.click(submitBtn);
  await screen.findByRole('heading', { name: '#912 · 未知恢复地点' });
  const orderPosts = transport.mock.calls.filter(
    ([url, init]) =>
      String(url) === '/api/v1/orders' && init?.method === 'POST',
  );
  expect(orderPosts).toHaveLength(2);
  expect(
    (orderPosts[1][1]?.headers as Record<string, string>)['Idempotency-Key'],
  ).toBe(
    (orderPosts[0][1]?.headers as Record<string, string>)['Idempotency-Key'],
  );
  expect(orderPosts[1][1]?.body).toBe(orderPosts[0][1]?.body);
});

it('preserves unsaved /new draft across 401 expiry for the same student', async () => {
  vi.mocked(fetch)
    .mockReset()
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(reply({ unreadCount: 0 }))
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(expired())
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(tokenReply())
    .mockResolvedValueOnce(reply(student))
    .mockResolvedValueOnce(reply({ unreadCount: 0 }));
  mount('/new');
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText('报修地点', { exact: false }),
    '未提交草稿地点',
  );
  await user.type(
    screen.getByLabelText('问题描述', { exact: false }),
    '未提交草稿说明',
  );
  fireEvent(window, new Event('focus'));
  await screen.findByRole('form', { name: '账号登录' });
  await user.type(screen.getByLabelText('用户名'), 'test_student');
  await user.type(screen.getByLabelText('密码'), 'test-only');
  await user.click(screen.getByRole('button', { name: '登录' }));
  expect(
    await screen.findByLabelText('报修地点', { exact: false }),
  ).toHaveValue('未提交草稿地点');
  expect(screen.getByLabelText('问题描述', { exact: false })).toHaveValue(
    '未提交草稿说明',
  );
});

it('holds unconfirmed list results during delayed /me and discards them even when list resolves before /me', async () => {
  let releaseMe!: (value: Response) => void;
  let releaseInFlightList!: (value: Response) => void;
  let phase: 'initial' | 'inflight' | 'switching' | 'switched' = 'initial';
  let listCallsDuringSwitch = 0;
  vi.mocked(fetch)
    .mockReset()
    .mockImplementation(async (input) => {
      const u = String(input);
      if (u.endsWith('/auth/csrf')) return tokenReply();
      if (u.endsWith('/me')) {
        if (phase === 'switching') {
          return new Promise<Response>((resolve) => {
            releaseMe = resolve;
          });
        }
        return reply(
          phase === 'switched'
            ? {
                ...student,
                id: '29',
                username: 'student_b',
                displayName: '学生B',
              }
            : student,
        );
      }
      if (u.endsWith('/notifications/unread-count')) {
        return reply({ unreadCount: phase === 'initial' ? 2 : 0 });
      }
      if (u.includes('/orders?')) {
        if (phase === 'inflight') {
          return new Promise<Response>((resolve) => {
            releaseInFlightList = resolve;
          });
        }
        if (phase === 'switching') {
          listCallsDuringSwitch += 1;
        }
        return listReply(phase === 'switched' ? '学生B工单' : '学生A工单');
      }
      throw new Error(`Unexpected request: ${u}`);
    });
  mount('/');
  expect(await screen.findByText('学生A工单')).toBeInTheDocument();
  phase = 'inflight';
  await userEvent
    .setup()
    .click(screen.getByRole('button', { name: '应用筛选' }));
  await waitFor(() => expect(releaseInFlightList).toBeDefined());

  // Cross-tab account switch starts delayed /me while in-flight list has not returned yet
  phase = 'switching';
  fireEvent(window, new Event('focus'));
  await waitFor(() => expect(releaseMe).toBeDefined());

  // List resolves BEFORE /me with another account's leaked payload; must NOT render into A's workbench
  releaseInFlightList(listReply('未确认归属列表'));
  await Promise.resolve();
  await Promise.resolve();
  expect(screen.queryByText('未确认归属列表')).not.toBeInTheDocument();
  expect(listCallsDuringSwitch).toBe(0);

  // Completing /me switches identity to Student B and cleanly mounts B's workbench
  phase = 'switched';
  releaseMe(
    reply({
      ...student,
      id: '29',
      username: 'student_b',
      displayName: '学生B',
    }),
  );
  expect(await screen.findByText('学生B工单')).toBeInTheDocument();
  expect(screen.queryByText('学生A工单')).not.toBeInTheDocument();
  expect(screen.queryByText('未确认归属列表')).not.toBeInTheDocument();
});

it('ignores stale unknown-response create failure after account switch without opening new account recovery banner', async () => {
  let failStaleCreate!: (reason: Error) => void;
  let switched = false;
  vi.mocked(fetch)
    .mockReset()
    .mockImplementation(async (input, init) => {
      const u = String(input);
      if (u.endsWith('/auth/csrf')) return tokenReply();
      if (u.endsWith('/me')) {
        return reply(
          switched
            ? {
                ...student,
                id: '29',
                username: 'student_b',
                displayName: '学生B',
              }
            : student,
        );
      }
      if (u.endsWith('/notifications/unread-count')) {
        return reply({ unreadCount: 0 });
      }
      if (u.endsWith('/orders') && init?.method === 'POST') {
        return new Promise<Response>((_, reject) => {
          failStaleCreate = reject;
        });
      }
      throw new Error(`Unexpected request: ${u}`);
    });
  mount('/new');
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText('报修地点', { exact: false }),
    '旧账号地点',
  );
  await user.selectOptions(
    screen.getByLabelText('故障类型', { exact: false }),
    'NETWORK',
  );
  await user.type(
    screen.getByLabelText('问题描述', { exact: false }),
    '旧账号故障',
  );
  await user.click(
    screen.getByRole('button', { name: '提交报修／恢复原提交' }),
  );
  await waitFor(() => expect(failStaleCreate).toBeDefined());

  switched = true;
  fireEvent(window, new Event('focus'));
  expect(await screen.findByText(/学生B/)).toBeInTheDocument();

  failStaleCreate(new TypeError('Failed to fetch'));
  await Promise.resolve();
  await Promise.resolve();
  expect(
    screen.queryByRole('button', { name: '重新确认身份／CSRF' }),
  ).not.toBeInTheDocument();
});

it('blocks unverified business GET results when /me fails with 503 until identity refresh succeeds', async () => {
  let releaseMe!: (value: Response) => void;
  let releaseInFlightList!: (value: Response) => void;
  let phase: 'initial' | 'inflight' | 'failing' | 'recovered' = 'initial';
  vi.mocked(fetch)
    .mockReset()
    .mockImplementation(async (input) => {
      const u = String(input);
      if (u.endsWith('/auth/csrf')) return tokenReply();
      if (u.endsWith('/me')) {
        if (phase === 'failing') {
          return new Promise<Response>((resolve) => {
            releaseMe = resolve;
          });
        }
        return reply(student);
      }
      if (u.endsWith('/notifications/unread-count')) {
        return reply({ unreadCount: 0 });
      }
      if (u.includes('/orders?')) {
        if (phase === 'inflight') {
          return new Promise<Response>((resolve) => {
            releaseInFlightList = resolve;
          });
        }
        return listReply(phase === 'recovered' ? '确认后工单' : '初始工单');
      }
      throw new Error(`Unexpected request: ${u}`);
    });
  mount('/');
  const user = userEvent.setup();
  expect(await screen.findByText('初始工单')).toBeInTheDocument();

  phase = 'inflight';
  await user.click(screen.getByRole('button', { name: '应用筛选' }));
  await waitFor(() => expect(releaseInFlightList).toBeDefined());

  phase = 'failing';
  fireEvent(window, new Event('focus'));
  await waitFor(() => expect(releaseMe).toBeDefined());

  // List resolves BEFORE /me fails with 503; must NOT render unverified payload
  releaseInFlightList(listReply('未验证503泄漏列表'));
  releaseMe(
    new Response(
      JSON.stringify({
        status: 503,
        code: 'TEMPORARILY_UNAVAILABLE',
        message: '身份服务暂时不可用',
      }),
      { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  );
  expect(
    (await screen.findAllByText(/TEMPORARILY_UNAVAILABLE/)).length,
  ).toBeGreaterThanOrEqual(1);
  expect(screen.queryByText('未验证503泄漏列表')).not.toBeInTheDocument();

  // Subsequent filter read while verification is failed must stay blocked
  await user.click(screen.getByRole('button', { name: '应用筛选' }));
  expect(
    await screen.findByText(
      /身份重新确认失败，请先重新确认身份／CSRF 后再读取数据/,
    ),
  ).toBeInTheDocument();

  // Recovering identity via 重新确认身份／CSRF re-enables normal list reads
  phase = 'recovered';
  await user.click(
    await screen.findByRole('button', { name: '重新确认身份／CSRF' }),
  );
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: '重新确认身份／CSRF' }),
    ).not.toBeInTheDocument(),
  );
  await user.click(screen.getByRole('button', { name: '应用筛选' }));
  expect(await screen.findByText('确认后工单')).toBeInTheDocument();
});

it('ignores late /me response from logged-out session A so logged-in session B stays unlocked and readable', async () => {
  let releaseStaleMe!: (value: Response) => void;
  let holdNextMe = false;
  let activeAccount: 'A' | 'B' | null = 'A';
  let listSeq = 0;
  vi.mocked(fetch)
    .mockReset()
    .mockImplementation(async (input, init) => {
      const u = String(input);
      const method = init?.method ?? 'GET';
      if (u.endsWith('/auth/csrf')) return tokenReply();
      if (u.endsWith('/auth/logout') && method === 'POST') {
        activeAccount = null;
        return new Response(null, { status: 204 });
      }
      if (u.endsWith('/auth/login') && method === 'POST') {
        activeAccount = 'B';
        return reply({
          ...student,
          id: '29',
          username: 'student_b',
          displayName: '学生B',
        });
      }
      if (u.endsWith('/me')) {
        if (holdNextMe) {
          holdNextMe = false;
          return new Promise<Response>((resolve) => {
            releaseStaleMe = resolve;
          });
        }
        if (activeAccount === 'B') {
          return reply({
            ...student,
            id: '29',
            username: 'student_b',
            displayName: '学生B',
          });
        }
        if (activeAccount === 'A') return reply(student);
        return expired();
      }
      if (u.endsWith('/notifications/unread-count')) {
        return reply({ unreadCount: 0 });
      }
      if (u.includes('/orders?')) {
        listSeq += 1;
        return listReply(
          activeAccount === 'B' ? `学生B工单-${listSeq}` : '学生A工单',
        );
      }
      throw new Error(`Unexpected request: ${method} ${u}`);
    });

  mount('/');
  const user = userEvent.setup();
  expect(await screen.findByText('学生A工单')).toBeInTheDocument();

  // Trigger a delayed /me for session A, then explicitly log out and sign in as B
  holdNextMe = true;
  fireEvent(window, new Event('focus'));
  await waitFor(() => expect(releaseStaleMe).toBeDefined());

  await user.click(screen.getByRole('button', { name: '退出登录' }));
  await screen.findByRole('form', { name: '账号登录' });
  await user.type(screen.getByLabelText('用户名'), 'student_b');
  await user.type(screen.getByLabelText('密码'), 'test-only');
  await user.click(screen.getByRole('button', { name: '登录' }));
  expect(await screen.findByText(/学生B工单-/)).toBeInTheDocument();

  // Session A's late /me finally resolves -> must NOT lock or show error on B
  releaseStaleMe(reply(student));
  await Promise.resolve();
  await Promise.resolve();
  expect(
    screen.queryByRole('button', { name: '重新确认身份／CSRF' }),
  ).not.toBeInTheDocument();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();

  // Session B's next GET read succeeds normally
  await user.click(screen.getByRole('button', { name: '应用筛选' }));
  expect(await screen.findByText(`学生B工单-${listSeq}`)).toBeInTheDocument();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});
