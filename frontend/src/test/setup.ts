import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

afterEach(() => {
  cleanup();
});
// Any accidental business transport fails tests, instead of reaching a real service.
const denied = () => {
  throw new Error('Network/storage forbidden in UI-only tests');
};
vi.stubGlobal('fetch', vi.fn(denied));
vi.stubGlobal('XMLHttpRequest', vi.fn(denied));
vi.stubGlobal('WebSocket', vi.fn(denied));
vi.stubGlobal('EventSource', vi.fn(denied));
