import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import App from '../app/App';
import type {
  AdminStatisticsResponse,
  Role,
  StatsWindowDays,
} from '../shared/api/contracts';

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
  reply(
    { status, code, message: `${code} 失败`, requestId: `stats-${status}` },
    status,
  );

function makeStats(
  days: StatsWindowDays,
  overrides: Partial<AdminStatisticsResponse> = {},
): AdminStatisticsResponse {
  const dailyTrend = Array.from({ length: days }, (_, idx) => {
    const dayNum = String(idx + 1).padStart(2, '0');
    return {
      date: `2026-11-${dayNum}`,
      createdCount: idx === days - 1 ? 3 : 0,
      repairCompletedCount: idx === days - 1 ? 2 : 0,
      confirmedCount: idx === days - 1 ? 1 : 0,
    };
  });
  return {
    generatedAt: '2026-11-26T14:00:00+08:00',
    timezone: 'Asia/Hong_Kong',
    windowDays: days,
    windowStartDate: dailyTrend[0].date,
    windowEndDate: dailyTrend[dailyTrend.length - 1].date,
    totalOrders: 6,
    statusCounts: [
      { status: 'PENDING_ASSIGNMENT', count: 1 },
      { status: 'PENDING_REPAIR', count: 1 },
      { status: 'IN_PROGRESS', count: 1 },
      { status: 'PENDING_CONFIRMATION', count: 1 },
      { status: 'COMPLETED', count: 1 },
      { status: 'CANCELLED', count: 1 },
    ],
    categoryCounts: [
      { category: 'WATER_ELECTRICITY', count: 2 },
      { category: 'DOORS_FURNITURE', count: 1 },
      { category: 'NETWORK', count: 1 },
      { category: 'AIR_CONDITIONING', count: 1 },
      { category: 'OTHER', count: 1 },
    ],
    dailyTrend,
    durations: {
      repairTurnaround: {
        sampleCount: 2,
        missingEventCount: 0,
        averageMinutes: 15.5,
        minMinutes: 10.0,
        maxMinutes: 21.0,
      },
      confirmationTurnaround: {
        sampleCount: 2,
        missingEventCount: 0,
        averageMinutes: 90.0,
        minMinutes: 60.0,
        maxMinutes: 120.0,
      },
    },
    locationAggregation: {
      supported: false,
      reason:
        '报修地点当前为自由文本字段，缺少可验证的结构化区域来源，暂不提供区域聚合。',
    },
    ...overrides,
  };
}

let currentRole: Role;
let statsFailure: { status: number; code: string } | null;
let statsOverride: Partial<AdminStatisticsResponse>;

beforeEach(() => {
  let loggedIn = true;
  currentRole = 'ADMIN';
  statsFailure = null;
  statsOverride = {};
  transport.mockReset().mockImplementation(async (input) => {
    const u = new URL(String(input), 'https://local.invalid');
    if (u.pathname === '/api/v1/auth/csrf') {
      return reply({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' });
    }
    if (u.pathname === '/api/v1/auth/logout') {
      loggedIn = false;
      return reply(null, 204);
    }
    if (u.pathname === '/api/v1/auth/login') {
      loggedIn = true;
      return reply({
        id:
          currentRole === 'WORKER'
            ? '2'
            : currentRole === 'STUDENT'
              ? '3'
              : '1',
        username:
          currentRole === 'WORKER'
            ? 'worker01'
            : currentRole === 'STUDENT'
              ? 'student01'
              : 'admin01',
        displayName: `测试${currentRole}`,
        role: currentRole,
      });
    }
    if (u.pathname === '/api/v1/me') {
      if (!loggedIn) {
        return problem(401, 'AUTH_REQUIRED');
      }
      return reply({
        id:
          currentRole === 'WORKER'
            ? '2'
            : currentRole === 'STUDENT'
              ? '3'
              : '1',
        username:
          currentRole === 'WORKER'
            ? 'worker01'
            : currentRole === 'STUDENT'
              ? 'student01'
              : 'admin01',
        displayName: `测试${currentRole}`,
        role: currentRole,
      });
    }
    if (u.pathname === '/api/v1/notifications/unread-count') {
      return reply({ unreadCount: 0 });
    }
    if (u.pathname === '/api/v1/notifications') {
      return reply({
        items: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
        unreadCount: 0,
      });
    }
    if (u.pathname === '/api/v1/admin/statistics') {
      if (statsFailure) {
        return problem(statsFailure.status, statsFailure.code);
      }
      const days = Number(u.searchParams.get('days') ?? 14) as StatsWindowDays;
      return reply(makeStats(days, statsOverride));
    }
    if (u.pathname.endsWith('/attachments')) {
      return reply({
        orderId: '101',
        items: [],
        limits: {
          maxFileBytes: 2097152,
          maxWidth: 4096,
          maxHeight: 4096,
          maxPixels: 16777216,
          maxStudentAttachments: 3,
          maxWorkerAttachments: 2,
        },
        canUpload: false,
      });
    }
    if (/\/orders\/101$/.test(u.pathname)) {
      return reply({
        id: '101',
        location: '旧角色详情地点',
        category: 'WATER_ELECTRICITY',
        description: '测试详情',
        status: 'PENDING_ASSIGNMENT',
        createdAt: '2026-11-26T10:00:00+08:00',
        completedAt: null,
        workerName: null,
        repairResult: null,
        availableActions: [],
        history: { incomplete: false, entries: [] },
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
    throw new Error(`Unexpected test request: ${u.pathname}${u.search}`);
  });
});

function mount(path = '/') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

it('admin navigates to statistics, views all metrics, and switches trend window', async () => {
  mount('/');
  const user = userEvent.setup();
  const statsLink = await screen.findByRole('link', { name: '统计概览' });
  await user.click(statsLink);

  expect(
    await screen.findByRole('heading', { name: '管理员统计概览' }),
  ).toBeInTheDocument();
  expect(await screen.findByText('15.5 分钟')).toBeInTheDocument();
  expect(screen.getByText('90.0 分钟（约 1.5 小时）')).toBeInTheDocument();
  expect(screen.getByText('水电设施')).toBeInTheDocument();
  expect(screen.queryByText('区域聚合说明')).not.toBeInTheDocument();

  const table = screen.getByRole('table', {
    name: '近 14 天每日工单趋势明细',
  });
  expect(within(table).getAllByRole('row')).toHaveLength(15);

  await user.click(screen.getByRole('button', { name: '近 7 天' }));
  expect(
    await screen.findByRole('table', { name: '近 7 天每日工单趋势明细' }),
  ).toBeInTheDocument();
  expect(
    transport.mock.calls.some(
      (call) => String(call[0]) === '/api/v1/admin/statistics?days=7',
    ),
  ).toBe(true);
});

it('renders null turnaround samples honestly and discloses missing events', async () => {
  statsOverride = {
    durations: {
      repairTurnaround: {
        sampleCount: 0,
        missingEventCount: 2,
        averageMinutes: null,
        minMinutes: null,
        maxMinutes: null,
      },
      confirmationTurnaround: {
        sampleCount: 1,
        missingEventCount: 1,
        averageMinutes: 12.0,
        minMinutes: 12.0,
        maxMinutes: 12.0,
      },
    },
  };
  mount('/stats');

  expect(
    await screen.findByRole('heading', { name: '管理员统计概览' }),
  ).toBeInTheDocument();
  expect(await screen.findByText('暂无有效样本')).toBeInTheDocument();
  expect(screen.queryByText('0.0 分钟')).not.toBeInTheDocument();
  expect(
    screen.getByText(/存在 2 单缺失或异常事件记录，已如实排除在耗时均值之外/),
  ).toBeInTheDocument();
  expect(
    screen.getByText(/存在 1 单缺失或异常事件记录，已如实排除在耗时均值之外/),
  ).toBeInTheDocument();
});

it('announces peak 0 on empty trend data without duplicate chart ticks', async () => {
  statsOverride = {
    totalOrders: 0,
    dailyTrend: Array.from({ length: 14 }, (_, idx) => ({
      date: `2026-11-${String(idx + 1).padStart(2, '0')}`,
      createdCount: 0,
      repairCompletedCount: 0,
      confirmedCount: 0,
    })),
  };
  mount('/stats');

  expect(
    await screen.findByRole('img', {
      name: /最高单日 0 单/,
    }),
  ).toBeInTheDocument();
});

it.each(['STUDENT', 'WORKER'] as const)(
  'hides statistics nav and blocks /stats route for %s without calling admin API',
  async (role) => {
    currentRole = role;
    mount('/stats');

    expect(await screen.findByText('页面不存在')).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: '统计概览' }),
    ).not.toBeInTheDocument();
    expect(
      transport.mock.calls.some((call) =>
        String(call[0]).includes('/admin/statistics'),
      ),
    ).toBe(false);
  },
);

it('clears previously rendered admin statistics immediately on 403 ROLE_FORBIDDEN', async () => {
  mount('/stats');
  expect(await screen.findByText('15.5 分钟')).toBeInTheDocument();

  statsFailure = { status: 403, code: 'ROLE_FORBIDDEN' };
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: '刷新统计' }));

  expect(await screen.findByText(/stats-403/)).toBeInTheDocument();
  expect(screen.queryByText('15.5 分钟')).not.toBeInTheDocument();
});

it('surfaces server errors with requestId and allows manual retry', async () => {
  statsFailure = { status: 503, code: 'TEMPORARILY_UNAVAILABLE' };
  mount('/stats');

  expect(await screen.findByText(/stats-503/)).toBeInTheDocument();
  statsFailure = null;
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: '重新读取统计' }));
  expect(await screen.findByText('15.5 分钟')).toBeInTheDocument();
});

it.each([
  ['/stats', 'ADMIN', '15.5 分钟', 'WORKER', '维修工作台'],
  ['/new', 'STUDENT', '提交报修／恢复原提交', 'WORKER', '维修工作台'],
  ['/orders/101', 'ADMIN', '旧角色详情地点', 'WORKER', '维修工作台'],
  ['/notifications', 'ADMIN', '当前暂无站内通知。', 'WORKER', '维修工作台'],
] as const)(
  'resets route to / on explicit logout from %s so the next role lands on workbench with clean session',
  async (startPath, startRole, marker, nextRole, nextHeading) => {
    currentRole = startRole;
    mount(startPath);
    expect(await screen.findByText(marker)).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '退出登录' }));
    expect(
      await screen.findByRole('heading', { name: '账号登录' }),
    ).toBeInTheDocument();
    expect(screen.queryByText(marker)).not.toBeInTheDocument();

    currentRole = nextRole;
    await user.type(screen.getByLabelText('用户名'), 'worker01');
    await user.type(screen.getByLabelText('密码'), 'test-only');
    await user.click(screen.getByRole('button', { name: '登录' }));

    expect(
      await screen.findByRole('heading', { name: nextHeading }),
    ).toBeInTheDocument();
    expect(screen.queryByText('页面不存在')).not.toBeInTheDocument();
  },
);
