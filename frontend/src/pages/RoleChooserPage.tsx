import { Link } from 'react-router-dom';
import { PageHeading, Surface } from '../shared/components/ui';
import { roleLabels } from '../shared/utils/orderStatus';
import type { RolePath } from '../shared/api/contracts';
import styles from './pages.module.css';

export function RoleChooserPage() {
  return (
    <>
      <PageHeading title="选择一个虚构身份" />
      <p className={styles.lead}>
        从提交报修到确认完成，体验三种角色的工作台。这里没有登录或真实账号。
      </p>
      <div className={styles.roleGrid}>
        {(Object.keys(roleLabels) as RolePath[]).map((role, i) => (
          <Surface key={role}>
            <p className={styles.roleNumber}>0{i + 1}</p>
            <h2>{roleLabels[role]}工作台</h2>
            <p className={styles.lead}>
              {
                [
                  '查看我的报修，了解进展，模拟提交与确认。',
                  '查看待派工单，为虚构维修人员分配任务。',
                  '查看我的任务，模拟开始维修与填写处理结果。',
                ][i]
              }
            </p>
            <Link className={styles.linkButton} to={`/${role}/orders`}>
              进入{roleLabels[role]}演示 <span aria-hidden="true">→</span>
            </Link>
          </Surface>
        ))}
      </div>
    </>
  );
}
