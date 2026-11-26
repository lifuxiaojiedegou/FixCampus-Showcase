import { useState } from 'react';
import { realError, RestResponseError } from '../shared/api/rest/studentClient';
import { Button, Notice } from '../shared/components/ui';
import styles from './pages.module.css';

export function RealErrorNotice({
  error,
  scope = 'order',
}: {
  error: unknown;
  scope?: 'order' | 'notification';
}) {
  const requestId = error instanceof RestResponseError ? error.requestId : null;
  return (
    <Notice error>
      <span>{realError(error, scope)}</span>
      {requestId && <RequestReference key={requestId} requestId={requestId} />}
    </Notice>
  );
}

function RequestReference({ requestId }: { requestId: string }) {
  const [feedback, setFeedback] = useState('');

  async function copyRequestId() {
    try {
      await navigator.clipboard.writeText(requestId);
      setFeedback('已复制请求编号');
    } catch {
      setFeedback('复制失败，可选中编号手动复制');
    }
  }

  return (
    <div className={styles.requestReference}>
      <span>
        请求编号 <code>{requestId}</code>
      </span>
      <Button variant="secondary" onClick={() => void copyRequestId()}>
        复制请求编号
      </Button>
      {feedback && <span aria-live="polite">{feedback}</span>}
    </div>
  );
}
