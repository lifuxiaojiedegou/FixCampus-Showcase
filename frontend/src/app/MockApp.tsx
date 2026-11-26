import { useState } from 'react';
import { Route, Routes } from 'react-router-dom';
import { DemoProviders } from './providers';
import { RequireMockRole } from './RequireMockRole';
import { AppShell } from './layouts/AppShell';
import { RoleChooserPage } from '../pages/RoleChooserPage';
import { NotFoundPage } from '../pages/NotFoundPage';
import { OrdersPage } from '../pages/OrdersPage';
import { NewOrderPage } from '../pages/NewOrderPage';
import { OrderDetailPage } from '../pages/OrderDetailPage';
import type { Scenario } from '../shared/api/mock/scenarios';

export function MockApp() {
  const [scenario, setScenario] = useState<Scenario>('normal');
  return (
    <DemoProviders key={scenario} scenario={scenario} setScenario={setScenario}>
      <Routes>
        <Route element={<AppShell />}>
          <Route index element={<RoleChooserPage />} />
          <Route element={<RequireMockRole role="STUDENT" />}>
            <Route path="student/orders" element={<OrdersPage />} />
            <Route path="student/new" element={<NewOrderPage />} />
            <Route
              path="student/orders/:orderId"
              element={<OrderDetailPage />}
            />
          </Route>
          <Route element={<RequireMockRole role="ADMIN" />}>
            <Route path="admin/orders" element={<OrdersPage />} />
            <Route path="admin/orders/:orderId" element={<OrderDetailPage />} />
          </Route>
          <Route element={<RequireMockRole role="WORKER" />}>
            <Route path="worker/orders" element={<OrdersPage />} />
            <Route
              path="worker/orders/:orderId"
              element={<OrderDetailPage />}
            />
          </Route>
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </DemoProviders>
  );
}
