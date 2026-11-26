import { useEffect, useState } from 'react';
import { Notice } from '../shared/components/ui';

export function PwaStatus({ real = false }: { real?: boolean }) {
  const [offline, setOffline] = useState(() => !navigator.onLine);
  const [updateReady, setUpdateReady] = useState(false);

  useEffect(() => {
    const connectivityChanged = () => setOffline(!navigator.onLine);
    window.addEventListener('online', connectivityChanged);
    window.addEventListener('offline', connectivityChanged);
    return () => {
      window.removeEventListener('online', connectivityChanged);
      window.removeEventListener('offline', connectivityChanged);
    };
  }, []);

  useEffect(() => {
    if (
      !import.meta.env.PROD ||
      !window.isSecureContext ||
      !('serviceWorker' in navigator)
    ) {
      return;
    }
    let active = true;
    let disconnect = () => {};
    const serviceWorker = navigator.serviceWorker;
    const base = import.meta.env.BASE_URL;
    void serviceWorker
      .register(`${base}service-worker.js`, {
        scope: base,
        updateViaCache: 'none',
      })
      .then((registration) => {
        if (!active) return;
        let installing: ServiceWorker | null = null;
        const showWaiting = () =>
          setUpdateReady(!!serviceWorker.controller && !!registration.waiting);
        const trackInstaller = () => {
          installing?.removeEventListener('statechange', showWaiting);
          installing = registration.installing;
          installing?.addEventListener('statechange', showWaiting);
          showWaiting();
        };
        const checkForUpdate = () => {
          if (navigator.onLine) void registration.update().catch(() => {});
        };
        registration.addEventListener('updatefound', trackInstaller);
        serviceWorker.addEventListener('controllerchange', showWaiting);
        window.addEventListener('focus', checkForUpdate);
        window.addEventListener('online', checkForUpdate);
        trackInstaller();
        disconnect = () => {
          registration.removeEventListener('updatefound', trackInstaller);
          serviceWorker.removeEventListener('controllerchange', showWaiting);
          window.removeEventListener('focus', checkForUpdate);
          window.removeEventListener('online', checkForUpdate);
          installing?.removeEventListener('statechange', showWaiting);
        };
      })
      .catch(() => {
        // The ordinary online workspace remains available without a worker.
      });
    return () => {
      active = false;
      disconnect();
    };
  }, []);

  return (
    <>
      {offline && (
        <Notice>
          {real
            ? '当前离线。登录、退出和工单操作需要联网。已发出的操作请联网后核对。'
            : '当前离线。虚构演示仍可使用。'}
        </Notice>
      )}
      {updateReady && (
        <Notice>
          新版本已准备好。请先核对未确认的工单操作，然后关闭所有 FixCampus
          页面和窗口，再重新打开以更新。
        </Notice>
      )}
    </>
  );
}
