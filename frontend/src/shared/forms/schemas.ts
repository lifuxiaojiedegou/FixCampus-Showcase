import { z } from 'zod';

export const categories = {
  WATER_ELECTRICITY: '水电设施',
  DOORS_FURNITURE: '门窗家具',
  NETWORK: '网络',
  AIR_CONDITIONING: '空调',
  OTHER: '其他',
} as const;
export const normalize = (value: string) =>
  value.replace(/\r\n?/g, '\n').trim();
export const codePoints = (value: string) => Array.from(value).length;
const text = (max: number, label: string, required = true) =>
  z
    .string()
    .transform(normalize)
    .refine(
      (v) => (!required || codePoints(v) >= 1) && codePoints(v) <= max,
      `${label}${required ? '需为 1～' : '最多 '}${max} 个字符`,
    );
export const createSchema = z.object({
  location: text(120, '报修地点'),
  category: z.enum(
    [
      'WATER_ELECTRICITY',
      'DOORS_FURNITURE',
      'NETWORK',
      'AIR_CONDITIONING',
      'OTHER',
    ],
    { error: '请选择故障类型' },
  ),
  description: text(2000, '问题描述'),
});
export const assignSchema = z.object({
  workerId: z.string().regex(/^[1-9]\d*$/, '请选择有效的维修人员'),
});
export const completeSchema = z.object({
  repairResult: text(1000, '维修说明', false),
});
export const searchSchema = text(120, '搜索内容', false);
