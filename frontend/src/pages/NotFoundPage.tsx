import { Link } from 'react-router-dom';
import { PageHeading, Surface } from '../shared/components/ui';

export function NotFoundPage() {
  return (
    <Surface>
      <PageHeading title="404 · 未找到此演示页面" />
      <p>该路径不属于 Mock 页面。没有尝试访问真实服务。</p>
      <Link to="/">返回虚构身份选择</Link>
    </Surface>
  );
}
