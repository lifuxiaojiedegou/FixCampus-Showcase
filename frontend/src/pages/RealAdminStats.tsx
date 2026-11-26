import { useEffect, useState } from 'react';
import type {
  AdminStatisticsResponse,
  DailyTrendItem,
  StatsWindowDays,
  TurnaroundMetrics,
} from '../shared/api/contracts';
import type { StudentClient } from '../shared/api/rest/studentClient';
import {
  Button,
  Notice,
  PageHeading,
  StatusTag,
  Surface,
} from '../shared/components/ui';
import { categories } from '../shared/forms/schemas';
import { formatDate } from '../shared/utils/formatDate';
import { RealErrorNotice } from './RealErrorNotice';
import styles from './pages.module.css';

const WINDOW_OPTIONS: readonly StatsWindowDays[] = [7, 14, 30];

function formatMinutes(minutes: number | null) {
  if (minutes === null) return '暂无有效样本';
  const base = `${minutes.toFixed(1)} 分钟`;
  if (minutes >= 60) {
    return `${base}（约 ${(minutes / 60).toFixed(1)} 小时）`;
  }
  return base;
}

function formatPercent(count: number, total: number) {
  if (total <= 0) return '0.0%';
  return `${((count / total) * 100).toFixed(1)}%`;
}

function TurnaroundCard({
  title,
  definition,
  metrics,
}: {
  title: string;
  definition: string;
  metrics: TurnaroundMetrics;
}) {
  return (
    <article className={styles.statsCard}>
      <h3>{title}</h3>
      <p className={styles.note}>{definition}</p>
      {metrics.sampleCount === 0 ? (
        <p className={styles.statsEmptyMetric}>暂无有效样本</p>
      ) : (
        <dl className={styles.statsMetricList}>
          <div>
            <dt>平均耗时</dt>
            <dd className={styles.statsHighlight}>
              {formatMinutes(metrics.averageMinutes)}
            </dd>
          </div>
          <div>
            <dt>最短耗时</dt>
            <dd>{formatMinutes(metrics.minMinutes)}</dd>
          </div>
          <div>
            <dt>最长耗时</dt>
            <dd>{formatMinutes(metrics.maxMinutes)}</dd>
          </div>
        </dl>
      )}
      <p className={styles.statsMeta}>
        有效样本：{metrics.sampleCount} 单
        {metrics.missingEventCount > 0 &&
          ` · 另有 ${metrics.missingEventCount} 单缺失或异常事件记录`}
      </p>
      {metrics.missingEventCount > 0 && (
        <p className={styles.statsWarning} role="note">
          存在 {metrics.missingEventCount}{' '}
          单缺失或异常事件记录，已如实排除在耗时均值之外，不作时间推断补齐。
        </p>
      )}
    </article>
  );
}

function TrendChart({ items }: { items: DailyTrendItem[] }) {
  const width = 640;
  const height = 210;
  const padLeft = 36;
  const padRight = 14;
  const padTop = 14;
  const padBottom = 34;
  const plotWidth = width - padLeft - padRight;
  const plotHeight = height - padTop - padBottom;
  const actualMax = Math.max(
    0,
    ...items.flatMap((d) => [
      d.createdCount,
      d.repairCompletedCount,
      d.confirmedCount,
    ]),
  );
  const scaleMax = Math.max(1, actualMax);
  const groupWidth = items.length > 0 ? plotWidth / items.length : plotWidth;
  const barWidth = Math.max(2, Math.min(10, (groupWidth - 6) / 3));
  const tickStep = items.length <= 7 ? 1 : items.length <= 14 ? 2 : 5;
  const yTicks = Array.from(new Set([0, Math.ceil(scaleMax / 2), scaleMax]));

  return (
    <div className={styles.statsChartWrap}>
      <div className={styles.statsLegend} aria-hidden="true">
        <span>
          <i className={styles.legendCreated} />
          新建工单
        </span>
        <span>
          <i className={styles.legendRepair} />
          维修完工
        </span>
        <span>
          <i className={styles.legendConfirmed} />
          学生确认
        </span>
      </div>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className={styles.statsSvg}
        role="img"
        aria-label={`近 ${items.length} 天每日新建、维修完工与学生确认工单趋势图，最高单日 ${actualMax} 单`}
      >
        {yTicks.map((tick) => {
          const y = padTop + plotHeight - (tick / scaleMax) * plotHeight;
          return (
            <g key={tick}>
              <line
                x1={padLeft}
                y1={y}
                x2={width - padRight}
                y2={y}
                stroke="#d5deef"
                strokeDasharray={tick === 0 ? undefined : '3 3'}
              />
              <text
                x={padLeft - 6}
                y={y + 4}
                textAnchor="end"
                fontSize="11"
                fill="#526176"
              >
                {tick}
              </text>
            </g>
          );
        })}
        {items.map((item, idx) => {
          const groupX = padLeft + idx * groupWidth;
          const totalBarsWidth = barWidth * 3 + 2;
          const startX =
            groupX + Math.max(0, (groupWidth - totalBarsWidth) / 2);
          const bars = [
            { value: item.createdCount, fill: '#1559e8' },
            { value: item.repairCompletedCount, fill: '#1c663d' },
            { value: item.confirmedCount, fill: '#92440b' },
          ];
          const showLabel =
            idx === 0 || idx === items.length - 1 || idx % tickStep === 0;
          return (
            <g key={item.date}>
              {bars.map((bar, bIdx) => {
                const h = (bar.value / scaleMax) * plotHeight;
                return (
                  <rect
                    key={bIdx}
                    x={startX + bIdx * (barWidth + 1)}
                    y={padTop + plotHeight - h}
                    width={barWidth}
                    height={Math.max(0, h)}
                    fill={bar.fill}
                    rx="1.5"
                  />
                );
              })}
              {showLabel && (
                <text
                  x={groupX + groupWidth / 2}
                  y={height - 10}
                  textAnchor="middle"
                  fontSize="10"
                  fill="#526176"
                >
                  {item.date.slice(5)}
                </text>
              )}
            </g>
          );
        })}
      </svg>
    </div>
  );
}

export function RealAdminStats({ client }: { client: StudentClient }) {
  const [days, setDays] = useState<StatsWindowDays>(14);
  const [reloadCount, setReloadCount] = useState(0);
  const [data, setData] = useState<AdminStatisticsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let active = true;
    void client
      .adminStatistics(days)
      .then((result) => {
        if (active) {
          setData(result);
          setError(null);
        }
      })
      .catch((e) => {
        if (active) {
          setData(null);
          setError(e);
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [client, days, reloadCount]);

  const selectDays = (option: StatsWindowDays) => {
    if (option === days) return;
    setLoading(true);
    setData(null);
    setError(null);
    setDays(option);
  };

  const reload = () => {
    setLoading(true);
    setError(null);
    setReloadCount((count) => count + 1);
  };

  const statusMap = new Map(
    (data?.statusCounts ?? []).map((item) => [item.status, item.count]),
  );
  const pendingAssignment = statusMap.get('PENDING_ASSIGNMENT') ?? 0;
  const openCount =
    pendingAssignment +
    (statusMap.get('PENDING_REPAIR') ?? 0) +
    (statusMap.get('IN_PROGRESS') ?? 0) +
    (statusMap.get('PENDING_CONFIRMATION') ?? 0);
  const completedCount = statusMap.get('COMPLETED') ?? 0;
  const windowCreatedTotal = (data?.dailyTrend ?? []).reduce(
    (sum, item) => sum + item.createdCount,
    0,
  );

  return (
    <div className={styles.statsPage}>
      <PageHeading
        eyebrow="FIXCAMPUS / 校园报修"
        title="管理员统计概览"
        description="状态分布、报修分类与处理耗时基于全量工单；按日趋势基于所选自然日窗口。"
      />
      <Surface>
        <div className={styles.statsHeader}>
          <div>
            <h2 tabIndex={-1}>统计周期与刷新</h2>
            <p className={styles.note}>
              切换自然日趋势窗口，或手动重新读取最新只读聚合结果。
            </p>
          </div>
          <div className={styles.statsControls}>
            <div
              role="group"
              aria-label="趋势时间窗口"
              className={styles.statsWindowGroup}
            >
              {WINDOW_OPTIONS.map((option) => (
                <Button
                  key={option}
                  variant={days === option ? 'primary' : 'secondary'}
                  aria-pressed={days === option}
                  disabled={loading && days === option}
                  onClick={() => selectDays(option)}
                >
                  近 {option} 天
                </Button>
              ))}
            </div>
            <Button variant="secondary" disabled={loading} onClick={reload}>
              刷新统计
            </Button>
          </div>
        </div>
        {data && (
          <p className={styles.statsMeta}>
            生成时间：{formatDate(data.generatedAt)}（{data.timezone}） ·
            趋势区间：{data.windowStartDate} 至 {data.windowEndDate}
          </p>
        )}
      </Surface>

      {error !== null && (
        <div className={styles.statsSection}>
          <RealErrorNotice error={error} />
          <div className={styles.formActions}>
            <Button variant="secondary" onClick={reload}>
              重新读取统计
            </Button>
          </div>
        </div>
      )}

      {loading && !data ? (
        <Notice>正在读取管理员统计…</Notice>
      ) : data ? (
        <>
          <section aria-label="核心工单指标" className={styles.statsKpiGrid}>
            <article className={styles.statsCard}>
              <h3>全量工单总数</h3>
              <p className={styles.statsNumber}>{data.totalOrders}</p>
              <p className={styles.note}>包含所有状态的历史与当前工单</p>
            </article>
            <article className={styles.statsCard}>
              <h3>未闭环工单</h3>
              <p className={styles.statsNumber}>{openCount}</p>
              <p className={styles.note}>其中待分配 {pendingAssignment} 单</p>
            </article>
            <article className={styles.statsCard}>
              <h3>已确认完成</h3>
              <p className={styles.statsNumber}>{completedCount}</p>
              <p className={styles.note}>
                占全量 {formatPercent(completedCount, data.totalOrders)}
              </p>
            </article>
            <article className={styles.statsCard}>
              <h3>近 {data.windowDays} 天新建</h3>
              <p className={styles.statsNumber}>{windowCreatedTotal}</p>
              <p className={styles.note}>按报修提交自然日统计</p>
            </article>
          </section>

          <section aria-label="处理耗时统计" className={styles.statsTwoCol}>
            <TurnaroundCard
              title="提交 → 维修员完工耗时"
              definition="口径：提交报修至提交维修结果（全量已达待确认/已完成工单）"
              metrics={data.durations.repairTurnaround}
            />
            <TurnaroundCard
              title="提交 → 学生确认耗时"
              definition="口径：提交报修至确认维修完成（全量已完成工单）"
              metrics={data.durations.confirmationTurnaround}
            />
          </section>

          <section aria-label="状态与分类分布" className={styles.statsTwoCol}>
            <Surface>
              <h3 className={styles.statsSectionTitle}>工单状态分布（全量）</h3>
              <ul className={styles.statsBarList}>
                {data.statusCounts.map((item) => {
                  const pct =
                    data.totalOrders > 0
                      ? (item.count / data.totalOrders) * 100
                      : 0;
                  return (
                    <li key={item.status} className={styles.statsBarItem}>
                      <div className={styles.statsBarLabel}>
                        <StatusTag status={item.status} />
                        <span>
                          {item.count} 单（
                          {formatPercent(item.count, data.totalOrders)}）
                        </span>
                      </div>
                      <div className={styles.statsBarTrack} aria-hidden="true">
                        <div
                          className={styles.statsBarFill}
                          style={{ width: `${pct}%` }}
                        />
                      </div>
                    </li>
                  );
                })}
              </ul>
            </Surface>

            <Surface>
              <h3 className={styles.statsSectionTitle}>报修分类分布（全量）</h3>
              <ul className={styles.statsBarList}>
                {data.categoryCounts.map((item) => {
                  const pct =
                    data.totalOrders > 0
                      ? (item.count / data.totalOrders) * 100
                      : 0;
                  return (
                    <li key={item.category} className={styles.statsBarItem}>
                      <div className={styles.statsBarLabel}>
                        <strong>{categories[item.category]}</strong>
                        <span>
                          {item.count} 单（
                          {formatPercent(item.count, data.totalOrders)}）
                        </span>
                      </div>
                      <div className={styles.statsBarTrack} aria-hidden="true">
                        <div
                          className={styles.statsBarFill}
                          style={{ width: `${pct}%` }}
                        />
                      </div>
                    </li>
                  );
                })}
              </ul>
            </Surface>
          </section>

          <Surface>
            <h3 className={styles.statsSectionTitle}>
              按日趋势（近 {data.windowDays} 天）
            </h3>
            <p className={styles.note}>
              分别按工单创建时间、维修完工事件时间与学生确认事件时间落日统计，无数据日期补
              0。
            </p>
            <TrendChart items={data.dailyTrend} />
            <div className={styles.statsTableWrap}>
              <table className={styles.statsTable}>
                <caption>近 {data.windowDays} 天每日工单趋势明细</caption>
                <thead>
                  <tr>
                    <th scope="col">日期</th>
                    <th scope="col">新建工单</th>
                    <th scope="col">维修完工</th>
                    <th scope="col">学生确认</th>
                  </tr>
                </thead>
                <tbody>
                  {data.dailyTrend.map((row) => (
                    <tr key={row.date}>
                      <th scope="row">{row.date}</th>
                      <td>{row.createdCount}</td>
                      <td>{row.repairCompletedCount}</td>
                      <td>{row.confirmedCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Surface>
        </>
      ) : null}
    </div>
  );
}
