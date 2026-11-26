import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { useDemo } from '../app/context';
import type { CreateOrderRequest } from '../shared/api/contracts';
import { categories, createSchema } from '../shared/forms/schemas';
import {
  Button,
  CharacterCount,
  ErrorState,
  FormField,
  PageHeading,
  Surface,
} from '../shared/components/ui';
import styles from './pages.module.css';

export function NewOrderPage() {
  const { user, repository } = useDemo();
  const navigate = useNavigate();
  const client = useQueryClient();
  const [error, setError] = useState<unknown>(null);
  const {
    register,
    handleSubmit,
    control,
    formState: { errors, isSubmitting },
  } = useForm<CreateOrderRequest>({
    resolver: zodResolver(createSchema),
    defaultValues: { location: '', description: '' },
  });
  const locationValue = useWatch({ control, name: 'location' });
  const descriptionValue = useWatch({ control, name: 'description' });
  const submit = handleSubmit(async (input) => {
    setError(null);
    try {
      const order = await repository.createOrder(user!, input);
      await client.invalidateQueries({ queryKey: ['orders'] });
      navigate(`/student/orders/${order.id}`, {
        state: { notice: '模拟提交成功，仅保存在内存中；刷新后消失。' },
      });
    } catch (e) {
      setError(e);
    }
  });
  return (
    <>
      <Link to="/student/orders" className={styles.back}>
        ← 返回我的报修
      </Link>
      <PageHeading
        title="新建模拟报修"
        description="填写虚构地点和故障信息，体验报修提交过程。"
      />
      <div className={styles.formLayout}>
        <Surface>
          <h2>报修信息</h2>
          {error !== null && <ErrorState error={error} />}
          <form onSubmit={submit} noValidate aria-label="新建模拟报修表单">
            <FormField
              required
              label="报修地点"
              id="location"
              error={errors.location?.message}
              hint="仅填写虚构地点，1～120 个 Unicode 字符"
            >
              <input
                id="location"
                aria-required="true"
                {...register('location')}
                aria-invalid={!!errors.location}
                aria-describedby={`location-hint${errors.location ? ' location-error' : ''}`}
                placeholder="如：虚构园区 A101"
              />
              <CharacterCount value={locationValue} max={120} />
            </FormField>
            <FormField
              required
              label="故障类型"
              id="category"
              error={errors.category?.message}
            >
              <select
                id="category"
                aria-required="true"
                {...register('category')}
                aria-invalid={!!errors.category}
                aria-describedby={
                  errors.category ? 'category-error' : undefined
                }
              >
                <option value="">请选择故障类型</option>
                {Object.entries(categories).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField
              required
              label="问题描述"
              id="description"
              error={errors.description?.message}
              hint="描述虚构故障，1～2000 个 Unicode 字符"
            >
              <textarea
                id="description"
                aria-required="true"
                {...register('description')}
                aria-invalid={!!errors.description}
                aria-describedby={`description-hint${errors.description ? ' description-error' : ''}`}
                placeholder="请描述虚构故障的现象与位置"
              />
              <CharacterCount value={descriptionValue} max={2000} />
            </FormField>
            <div className={styles.formActions}>
              <Button
                type="submit"
                disabled={isSubmitting}
                aria-busy={isSubmitting}
              >
                {isSubmitting ? '正在模拟提交…' : '模拟提交报修'}
              </Button>
              <Link to="/student/orders">取消填写</Link>
            </div>
          </form>
        </Surface>
        <Surface>
          <h2>填写前请留意</h2>
          <p className={styles.note}>
            全部数据均为虚构。请勿输入真实姓名、电话、账号或校园资料。
          </p>
          <p className={styles.note}>
            这里不会创建真实工单，不提供附件、认证、数据库存储或持久化幂等。刷新页面会重置。
          </p>
        </Surface>
      </div>
    </>
  );
}
