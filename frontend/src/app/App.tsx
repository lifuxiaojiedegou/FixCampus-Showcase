import { BrowserRouter } from 'react-router-dom';
import { RealStudentApp } from '../pages/RealStudentApp';
import { MockApp } from './MockApp';

export default function App() {
  if (import.meta.env.MODE === 'mock') {
    return <MockApp />;
  }
  return <RealStudentApp />;
}

export function AppRouter() {
  return (
    <BrowserRouter basename="/app/">
      <App />
    </BrowserRouter>
  );
}
