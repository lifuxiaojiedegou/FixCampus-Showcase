import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { useId } from 'react';
import type { OrderStatus } from '../api/contracts';
import { MockProblem } from '../api/errors';
import { statuses } from '../utils/orderStatus';
import styles from './ui.module.css';

export function Button({
  children,
  variant = 'primary',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary';
}) {
  return (
    <button
      type="button"
      className={`${styles.button} ${styles[variant]}`}
      {...props}
    >
      {children}
    </button>
  );
}
export function StatusTag({ status }: { status: OrderStatus }) {
  return (
    <span className={`${styles.tag} ${styles[statuses[status].tone]}`}>
      {statuses[status].label}
    </span>
  );
}
export function Notice({
  children,
  error = false,
}: {
  children: ReactNode;
  error?: boolean;
}) {
  return (
    <div
      className={`${styles.notice} ${error ? styles.error : ''}`}
      role={error ? 'alert' : 'status'}
    >
      {children}
    </div>
  );
}
export function Surface({ children }: { children: ReactNode }) {
  return <section className={styles.surface}>{children}</section>;
}
export function LoadingState() {
  return <Notice>正在加载虚构数据…</Notice>;
}
export function EmptyState({
  message = '试试调整筛选条件，或创建一条虚构报修。',
}: {
  message?: string;
}) {
  return (
    <div className={styles.empty}>
      <h2>暂无模拟工单</h2>
      <p>{message}</p>
    </div>
  );
}
export function ErrorState({
  error,
  onRetry,
}: {
  error: unknown;
  onRetry?: () => void;
}) {
  return (
    <Notice error>
      <strong>
        {error instanceof MockProblem
          ? `${error.problem.status} · ${error.problem.code}`
          : '模拟操作失败'}
      </strong>
      <p>{error instanceof Error ? error.message : '请稍后重新查看。'}</p>
      {onRetry && (
        <Button variant="secondary" onClick={onRetry}>
          重新读取模拟数据
        </Button>
      )}
    </Notice>
  );
}
export function PageHeading({
  title,
  children,
  description,
  eyebrow = 'FIXCAMPUS / UI PREVIEW',
}: {
  title: string;
  description?: string;
  eyebrow?: string;
  children?: ReactNode;
}) {
  return (
    <div className={styles.heading}>
      <div>
        <p className={styles.eyebrow}>{eyebrow}</p>
        <h1>{title}</h1>
        <p>{description ?? '选择虚构身份，体验已有报修流程。'}</p>
      </div>
      {children}
    </div>
  );
}
export function FormField({
  label,
  id,
  error,
  hint,
  required = false,
  children,
}: {
  label: string;
  id: string;
  error?: string;
  hint?: string;
  required?: boolean;
  children: ReactNode;
}) {
  return (
    <div className={styles.field}>
      <div className={styles.fieldLabel}>
        <label htmlFor={id}>{label}</label>
        {required && <span className={styles.required}>必填</span>}
      </div>
      {children}
      {hint && (
        <p id={`${id}-hint`} className={styles.help}>
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className={styles.fieldError} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
export function CharacterCount({ value, max }: { value: string; max: number }) {
  const id = useId();
  return (
    <span id={id} className={styles.help}>
      {Array.from(value).length} / {max} 字符
    </span>
  );
}
export function Pagination({
  page,
  totalPages,
  onPage,
}: {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}) {
  return (
    <nav aria-label="模拟工单分页" className={styles.pagination}>
      <Button
        variant="secondary"
        disabled={page === 0}
        onClick={() => onPage(page - 1)}
      >
        上一页
      </Button>
      <span aria-live="polite">
        第 {page + 1} 页 / 共 {totalPages} 页
      </span>
      <Button
        variant="secondary"
        disabled={page + 1 >= totalPages}
        onClick={() => onPage(page + 1)}
      >
        下一页
      </Button>
    </nav>
  );
}
