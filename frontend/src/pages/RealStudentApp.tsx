import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Link,
  NavLink,
  Navigate,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from 'react-router-dom';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { PwaStatus } from '../app/PwaStatus';
import {
  getDemoDisplayConfig,
  PROD_DEMO_CONFIG,
  type DemoDisplayConfig,
} from '../app/demoConfig';
import type { CreateOrderRequest, MeResponse } from '../shared/api/contracts';
import { StudentClient } from '../shared/api/rest/studentClient';
import { categories, createSchema } from '../shared/forms/schemas';
import {
  Button,
  CharacterCount,
  FormField,
  Notice,
  PageHeading,
  Surface,
} from '../shared/components/ui';
import shellStyles from '../app/layouts/shell.module.css';
import styles from './pages.module.css';
import { RealList, RealDetail } from './RealOrderWorkspace';
import { RealAdminStats } from './RealAdminStats';
import { RealNotifications } from './RealNotifications';
import { RealErrorNotice } from './RealErrorNotice';

const UNREAD_POLL_MS = 30_000;

export function RealStudentApp({
  demoConfig,
}: {
  demoConfig?: DemoDisplayConfig;
} = {}) {
  const demo =
    demoConfig ??
    (import.meta.env.MODE === 'demo' ||
    import.meta.env.VITE_DEMO_MODE === 'true'
      ? getDemoDisplayConfig()
      : PROD_DEMO_CONFIG);
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const workspace = useRef<HTMLDivElement>(null);
  const [user, setUser] = useState<MeResponse | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [client] = useState(() => new StudentClient(setUser, setError));
  const [checking, setChecking] = useState(true);
  const [unreadState, setUnreadState] = useState<{
    owner: string;
    count: number;
  } | null>(null);
  const unreadWriteVersionRef = useRef<{ owner: string; version: number }>({
    owner: '',
    version: 0,
  });
  const [clientNeedsRecovery, setClientNeedsRecovery] = useState(false);
  const [confirmSeq, setConfirmSeq] = useState(0);
  const userKey = user ? `${user.id}:${user.role}` : null;
  const userKeyRef = useRef<string | null>(null);
  const unreadCount =
    userKey && unreadState?.owner === userKey ? unreadState.count : null;
  const needsRecovery = Boolean(
    userKey &&
    (clientNeedsRecovery ||
      error !== null ||
      client.writeConfirmation() === null),
  );

  useEffect(() => {
    return client.subscribeRecovery(setClientNeedsRecovery);
  }, [client]);

  useEffect(() => {
    userKeyRef.current = userKey;
    if (unreadWriteVersionRef.current.owner !== (userKey ?? '')) {
      unreadWriteVersionRef.current = { owner: userKey ?? '', version: 0 };
    }
  }, [userKey]);

  const getUnreadWriteVersion = useCallback(() => {
    if (!userKey) return 0;
    return unreadWriteVersionRef.current.owner === userKey
      ? unreadWriteVersionRef.current.version
      : 0;
  }, [userKey]);

  const handleReadUnreadCount = useCallback(
    (count: number, startedWriteVersion: number) => {
      if (!userKey || userKeyRef.current !== userKey) return;
      const currentVersion =
        unreadWriteVersionRef.current.owner === userKey
          ? unreadWriteVersionRef.current.version
          : 0;
      if (startedWriteVersion < currentVersion) return;
      setUnreadState({ owner: userKey, count });
    },
    [userKey],
  );

  const handleWriteUnreadCount = useCallback(
    (count: number) => {
      if (!userKey || userKeyRef.current !== userKey) return;
      const nextVersion =
        (unreadWriteVersionRef.current.owner === userKey
          ? unreadWriteVersionRef.current.version
          : 0) + 1;
      unreadWriteVersionRef.current = { owner: userKey, version: nextVersion };
      setUnreadState({ owner: userKey, count });
    },
    [userKey],
  );

  useEffect(() => {
    workspace.current?.querySelector('h2')?.focus();
  }, [pathname, checking, user?.id, user?.role]);
  const refresh = async () => {
    const startedEpoch = client.authEpoch();
    try {
      await client.refresh();
      setError(null);
      setClientNeedsRecovery(client.needsRecovery());
      setConfirmSeq((n) => n + 1);
    } catch (e) {
      if (client.authEpoch() === startedEpoch) {
        setError(e);
      }
    } finally {
      setChecking(false);
    }
  };
  useEffect(() => {
    let active = true;
    const startedEpoch = client.authEpoch();
    void client
      .refresh()
      .then(() => {
        if (active) {
          setClientNeedsRecovery(client.needsRecovery());
          setConfirmSeq((n) => n + 1);
        }
      })
      .catch((e) => {
        if (active && client.authEpoch() === startedEpoch) setError(e);
      })
      .finally(() => {
        if (active) setChecking(false);
      });
    return () => {
      active = false;
    };
  }, [client]);
  useEffect(() => {
    const recheck = () => {
      const startedEpoch = client.authEpoch();
      void client
        .refresh()
        .then(() => {
          setError(null);
          setClientNeedsRecovery(client.needsRecovery());
          setConfirmSeq((n) => n + 1);
        })
        .catch((e) => {
          if (client.authEpoch() === startedEpoch) {
            setError(e);
          }
        });
    };
    const visible = () => {
      if (document.visibilityState === 'visible') recheck();
    };
    window.addEventListener('focus', recheck);
    document.addEventListener('visibilitychange', visible);
    return () => {
      window.removeEventListener('focus', recheck);
      document.removeEventListener('visibilitychange', visible);
    };
  }, [client]);
  useEffect(() => {
    if (!userKey) return;
    let active = true;
    let inFlight = false;
    const pollUnread = () => {
      if (
        !active ||
        inFlight ||
        client.isRefreshing() ||
        userKeyRef.current !== userKey ||
        document.visibilityState === 'hidden' ||
        (typeof navigator !== 'undefined' && navigator.onLine === false)
      ) {
        return;
      }
      const startedWriteVersion = getUnreadWriteVersion();
      inFlight = true;
      void client
        .unreadNotificationCount()
        .then((res) => {
          if (active && userKeyRef.current === userKey) {
            handleReadUnreadCount(res.unreadCount, startedWriteVersion);
          }
        })
        .catch(() => {
          // 401 revokes identity inside StudentClient; transient polling errors stay non-blocking.
        })
        .finally(() => {
          inFlight = false;
        });
    };
    pollUnread();
    const timer = window.setInterval(pollUnread, UNREAD_POLL_MS);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [client, getUnreadWriteVersion, handleReadUnreadCount, userKey]);
  if (pathname === '/real' || pathname.startsWith('/real/')) {
    return <Navigate to="/" replace />;
  }
  const roleTitle =
    user?.role === 'ADMIN'
      ? '管理员工作台'
      : user?.role === 'WORKER'
        ? '维修工作台'
        : user?.role === 'STUDENT'
          ? '学生工作台'
          : '登录工作台';
  return (
    <>
      <a href="#real-content" className={shellStyles.skip}>
        跳到工作内容
      </a>
      <div className={shellStyles.shell}>
        <aside className={shellStyles.sidebar} aria-label="工作区导航">
          <Link
            to="/"
            className={shellStyles.brand}
            aria-label="FixCampus 返回工单工作台"
          >
            <span className={shellStyles.mark} aria-hidden="true">
              <svg
                viewBox="0 0 24 24"
                width="25"
                height="25"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="m14 5 1 4 4 1 3-3a6 6 0 0 1-8 8l-6 6a3 3 0 0 1-4-4l6-6a6 6 0 0 1 8-8Z" />
              </svg>
            </span>
            <span>
              <strong>FixCampus</strong>
              <small>校园报修管理平台</small>
            </span>
          </Link>
          {user && (
            <nav aria-label="工单导航" className={shellStyles.nav}>
              <NavLink to="/" end>
                <span aria-hidden="true">▤</span>
                <span>工单工作台</span>
              </NavLink>
              <NavLink to="/notifications">
                <span aria-hidden="true">◈</span>
                <span>站内通知</span>
                {unreadCount !== null && unreadCount > 0 && (
                  <span
                    className={styles.navBadge}
                    aria-label={`有 ${unreadCount} 条未读通知`}
                  >
                    {unreadCount > 99 ? '99+' : unreadCount}
                  </span>
                )}
              </NavLink>
              {user.role === 'ADMIN' && (
                <NavLink to="/stats">
                  <span aria-hidden="true">◫</span>
                  <span>统计概览</span>
                </NavLink>
              )}
              {user.role === 'STUDENT' && (
                <NavLink to="/new">
                  <span aria-hidden="true">＋</span>
                  <span>提交报修</span>
                </NavLink>
              )}
            </nav>
          )}
          <p className={shellStyles.sidebarNote}>
            让校园设施更好用
            <small>三角色协同闭环 · 全程留痕可追溯</small>
          </p>
        </aside>
        <div className={shellStyles.content}>
          {demo.enabled && user && (
            <div className={shellStyles.mockBanner}>{demo.resetNotice}</div>
          )}
          <header className={shellStyles.topbar}>
            <span>{roleTitle}</span>
            <div className={shellStyles.identity}>
              {user ? (
                <>
                  <span>
                    {user.displayName}（{user.username}）
                  </span>
                  <button
                    type="button"
                    className={shellStyles.topbarAction}
                    onClick={() =>
                      void client
                        .logout()
                        .then(() => {
                          setClientNeedsRecovery(false);
                          setError(null);
                          setUser(null);
                          navigate('/', { replace: true });
                        })
                        .catch(setError)
                    }
                  >
                    退出登录
                  </button>
                </>
              ) : (
                <span className={shellStyles.guestHint}>
                  请登录以访问校园报修工作台
                </span>
              )}
            </div>
          </header>
          <main id="main" className={shellStyles.workspace}>
            <PwaStatus real />
            {error !== null && <RealErrorNotice error={error} />}
            {user && needsRecovery && (
              <Notice>
                <div className={styles.recoveryBar}>
                  <span>
                    当前会话或安全令牌需要重新确认，完成后可继续提交操作。
                  </span>
                  <Button variant="secondary" onClick={() => void refresh()}>
                    重新确认身份／CSRF
                  </Button>
                </div>
              </Notice>
            )}
            <div id="real-content" ref={workspace} tabIndex={-1}>
              {checking ? (
                <Notice>正在确认服务器身份…</Notice>
              ) : !user ? (
                <Login
                  client={client}
                  demo={demo}
                  onAuthenticated={() => {
                    setError(null);
                    setClientNeedsRecovery(false);
                    setConfirmSeq((n) => n + 1);
                  }}
                />
              ) : (
                <Routes key={`${user.id}:${user.role}`}>
                  <Route
                    index
                    element={<RealList client={client} role={user.role} />}
                  />
                  {user.role === 'ADMIN' && (
                    <Route
                      path="stats"
                      element={<RealAdminStats client={client} />}
                    />
                  )}
                  {user.role === 'STUDENT' && (
                    <Route
                      path="new"
                      element={
                        <RealCreate
                          client={client}
                          needsRecovery={needsRecovery}
                        />
                      }
                    />
                  )}
                  <Route
                    path="notifications"
                    element={
                      <>
                        <PageHeading
                          eyebrow="FIXCAMPUS / 校园报修"
                          title="通知中心"
                          description="查看当前登录账号的工单流转提醒（未读角标每 30 秒自动刷新，列表支持随时手动刷新）。"
                        />
                        <RealNotifications
                          client={client}
                          unreadCount={unreadCount}
                          confirmSeq={confirmSeq}
                          getUnreadWriteVersion={getUnreadWriteVersion}
                          onReadUnreadCount={handleReadUnreadCount}
                          onWriteUnreadCount={handleWriteUnreadCount}
                        />
                      </>
                    }
                  />
                  <Route
                    path="orders/:id"
                    element={
                      <RealDetail
                        client={client}
                        role={user.role}
                        confirmSeq={confirmSeq}
                      />
                    }
                  />
                  <Route
                    path="*"
                    element={
                      <Notice error>
                        <span>页面不存在</span>{' '}
                        <Link to="/">返回工单工作台</Link>
                      </Notice>
                    }
                  />
                </Routes>
              )}
            </div>
          </main>
          <footer className={shellStyles.footer}>
            FixCampus · 校园报修管理平台 · Web / PWA
          </footer>
        </div>
      </div>
    </>
  );
}
function Login({
  client,
  demo,
  onAuthenticated,
}: {
  client: StudentClient;
  demo: DemoDisplayConfig;
  onAuthenticated: () => void;
}) {
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const lock = useRef(false);
  return (
    <>
      <PageHeading
        eyebrow="FIXCAMPUS / 校园报修"
        title="登录校园报修工作台"
        description="使用校园报修账号登录，按角色进入对应的报修与处理工作台。"
      />
      <div className={styles.formLayout}>
        <Surface>
          <h2 tabIndex={-1}>账号登录</h2>
          {demo.enabled && <Notice>{demo.resetNotice}</Notice>}
          {error !== null && <RealErrorNotice error={error} />}
          <form
            aria-label="账号登录"
            aria-busy={busy}
            onSubmit={(e) => {
              e.preventDefault();
              if (lock.current) return;
              lock.current = true;
              const data = new FormData(e.currentTarget);
              setBusy(true);
              setError(null);
              void client
                .login(
                  String(data.get('username')),
                  String(data.get('password')),
                )
                .then((user) => {
                  if (user) onAuthenticated();
                })
                .catch(setError)
                .finally(() => {
                  lock.current = false;
                  setBusy(false);
                });
            }}
          >
            <FormField id="real-username" label="用户名" required>
              <input
                id="real-username"
                name="username"
                autoComplete="username"
                placeholder="请输入账号（如 student01）"
                required
                disabled={busy}
              />
            </FormField>
            <FormField id="real-password" label="密码" required>
              <div className={styles.passwordWrap}>
                <input
                  id="real-password"
                  type={showPassword ? 'text' : 'password'}
                  name="password"
                  autoComplete="current-password"
                  placeholder="请输入登录密码"
                  required
                  disabled={busy}
                />
                <button
                  type="button"
                  className={styles.passwordToggle}
                  aria-pressed={showPassword}
                  disabled={busy}
                  onClick={() => setShowPassword((prev) => !prev)}
                >
                  {showPassword ? '隐藏' : '显示'}
                </button>
              </div>
            </FormField>
            <Button type="submit" disabled={busy}>
              {busy ? '正在登录…' : '登录'}
            </Button>
          </form>
          {demo.enabled && (
            <section aria-label="公开演示账号说明" className={styles.demoPanel}>
              <h3>公开演示角色与账号说明</h3>
              <ul className={styles.demoRoles}>
                {demo.accounts.map((account) => (
                  <li key={account.role}>
                    <strong>
                      {account.roleLabel}（<code>{account.username}</code>）
                    </strong>
                    <span>：{account.description}</span>
                  </li>
                ))}
              </ul>
              {demo.accessHint && (
                <p className={styles.demoHint}>{demo.accessHint}</p>
              )}
            </section>
          )}
        </Surface>
        <Surface>
          <h2>平台流程说明</h2>
          <ul className={styles.loginFlowList}>
            <li>
              <strong>学生端</strong>
              <p className={styles.note}>
                提交校园设施报修并上传现场照片，随时查看处理进展，完工后确认闭环。
              </p>
            </li>
            <li>
              <strong>管理员端</strong>
              <p className={styles.note}>
                统一筛选全校工单、向有效维修人员派单，并查阅运营统计与两类耗时指标。
              </p>
            </li>
            <li>
              <strong>维修人员端</strong>
              <p className={styles.note}>
                接收派单站内通知，记录开工状态、上传完工现场图并提交维修处理结果。
              </p>
            </li>
          </ul>
        </Surface>
      </div>
    </>
  );
}
function RealCreate({
  client,
  needsRecovery,
}: {
  client: StudentClient;
  needsRecovery: boolean;
}) {
  const navigate = useNavigate();
  const [error, setError] = useState<unknown>(null);
  const {
    register,
    handleSubmit,
    control,
    formState: { errors, isSubmitting },
  } = useForm<CreateOrderRequest>({
    resolver: zodResolver(createSchema),
    defaultValues: client.draftInput() ?? {
      location: '',
      category: '' as CreateOrderRequest['category'],
      description: '',
    },
  });
  const locationValue = useWatch({ control, name: 'location' }) ?? '';
  const categoryValue = useWatch({ control, name: 'category' }) ?? '';
  const descriptionValue = useWatch({ control, name: 'description' }) ?? '';
  useEffect(() => {
    client.saveDraft({
      location: locationValue,
      category: categoryValue as CreateOrderRequest['category'],
      description: descriptionValue,
    });
  }, [client, locationValue, categoryValue, descriptionValue]);
  return (
    <>
      <Link to="/" className={styles.back}>
        ← 返回工单列表
      </Link>
      <PageHeading
        eyebrow="FIXCAMPUS / 校园报修"
        title="新建报修"
        description="填写报修地点和故障信息，提交后可在详情页上传现场照片。"
      />
      <div className={styles.formLayout}>
        <Surface>
          <h2 tabIndex={-1}>提交报修</h2>
          {error !== null && <RealErrorNotice error={error} />}
          <form
            aria-label="报修表单"
            aria-busy={isSubmitting}
            noValidate
            onSubmit={handleSubmit(async (input) => {
              if (needsRecovery || client.needsRecovery()) return;
              setError(null);
              try {
                const result = await client.create(input);
                navigate(`/orders/${result.id}`);
              } catch (e) {
                setError(e);
              }
            })}
          >
            <FormField
              id="real-location"
              label="报修地点"
              required
              hint="请输入具体楼栋、宿舍或教室位置（1～120 个字符）"
              error={errors.location?.message}
            >
              <input
                id="real-location"
                aria-required="true"
                disabled={isSubmitting}
                placeholder="如：示例教学楼 302 室"
                {...register('location')}
                aria-invalid={!!errors.location}
                aria-describedby={
                  errors.location
                    ? 'real-location-hint real-location-error'
                    : 'real-location-hint'
                }
              />
              <CharacterCount value={locationValue} max={120} />
            </FormField>
            <FormField
              id="real-category"
              label="故障类型"
              required
              error={errors.category?.message}
            >
              <select
                id="real-category"
                aria-required="true"
                disabled={isSubmitting}
                {...register('category')}
                aria-invalid={!!errors.category}
                aria-describedby={
                  errors.category ? 'real-category-error' : undefined
                }
              >
                <option value="">请选择</option>
                {Object.entries(categories).map(([key, label]) => (
                  <option key={key} value={key}>
                    {label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField
              id="real-description"
              label="问题描述"
              required
              hint="详细描述故障现象以便维修人员提前准备工具（1～2000 个字符）"
              error={errors.description?.message}
            >
              <textarea
                id="real-description"
                aria-required="true"
                disabled={isSubmitting}
                placeholder="请描述故障现象、发生时间与影响范围"
                {...register('description')}
                aria-invalid={!!errors.description}
                aria-describedby={
                  errors.description
                    ? 'real-description-hint real-description-error'
                    : 'real-description-hint'
                }
              />
              <CharacterCount value={descriptionValue} max={2000} />
            </FormField>
            <div className={styles.formActions}>
              <Button type="submit" disabled={isSubmitting || needsRecovery}>
                {isSubmitting ? '正在提交…' : '提交报修／恢复原提交'}
              </Button>
              <Link to="/">取消返回</Link>
            </div>
          </form>
        </Surface>
        <Surface>
          <h2>提交与恢复说明</h2>
          <p className={styles.note}>
            失败时保留原提交键。结果未知时保持页面和原内容，确认身份后手动恢复；不要刷新或新建重复提交。
          </p>
          <p className={styles.note}>
            工单提交成功后，可在详情页继续上传 PNG / JPEG
            故障现场照片（学生每单最多 3 张，单图不超过 2 MiB）。
          </p>
        </Surface>
      </div>
    </>
  );
}
