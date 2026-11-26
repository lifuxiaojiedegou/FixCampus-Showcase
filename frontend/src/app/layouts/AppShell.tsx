import { useEffect, useRef } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useDemo } from '../context';
import { PwaStatus } from '../PwaStatus';
import { roleLabels } from '../../shared/utils/orderStatus';
import { scenarioLabels, type Scenario } from '../../shared/api/mock/scenarios';
import type { RolePath } from '../../shared/api/contracts';
import styles from './shell.module.css';

export function AppShell() {
  const { user, scenario, setScenario } = useDemo();
  const path = useLocation().pathname;
  const main = useRef<HTMLElement>(null);
  useEffect(() => {
    main.current?.focus();
  }, [path]);
  const role = user?.role.toLowerCase() as RolePath | undefined;
  return (
    <>
      <a className={styles.skip} href="#main">
        跳到主要内容
      </a>
      <div className={styles.shell}>
        <aside className={styles.sidebar} aria-label="工作区导航">
          <Link
            to="/"
            className={styles.brand}
            aria-label="FixCampus 选择虚构身份"
          >
            <span className={styles.mark} aria-hidden="true">
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
              <small>校园报修 · UI 演示</small>
            </span>
          </Link>
          {role && (
            <nav aria-label="角色导航" className={styles.nav}>
              <NavLink to={`/${role}/orders`} end={false}>
                <span aria-hidden="true">▤</span>工单工作台
              </NavLink>
              {role === 'student' && (
                <NavLink to="/student/new">
                  <span aria-hidden="true">＋</span>新建报修
                </NavLink>
              )}
            </nav>
          )}
          <p className={styles.sidebarNote}>
            让校园设施更好用<small>仅演示已实现的页面与操作</small>
          </p>
        </aside>
        <div className={styles.content}>
          <div className={styles.mockBanner}>
            Mock 演示 · 虚构数据 · 操作仅存内存，刷新即重置 · 未连接真实业务
          </div>
          <header className={styles.topbar}>
            <span>{role ? `${roleLabels[role]}工作台` : '选择虚构身份'}</span>
            <div className={styles.identity}>
              {user && <span>{user.displayName}</span>}
              <Link to="/">切换虚构身份</Link>
            </div>
          </header>
          <main id="main" tabIndex={-1} ref={main} className={styles.workspace}>
            <PwaStatus />
            <details className={styles.scenario}>
              <summary>模拟场景设置</summary>
              <div className={styles.scenarioBody}>
                <label htmlFor="mock-scenario">演示场景</label>
                <select
                  id="mock-scenario"
                  value={scenario}
                  onChange={(e) => setScenario(e.target.value as Scenario)}
                >
                  {Object.entries(scenarioLabels).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
                <span>切换场景会重置模拟数据</span>
              </div>
            </details>
            <Outlet />
          </main>
          <footer className={styles.footer}>
            FixCampus · UI-only / M4.1 · Mock 不代表真实权限、认证或数据库能力
          </footer>
        </div>
      </div>
    </>
  );
}
