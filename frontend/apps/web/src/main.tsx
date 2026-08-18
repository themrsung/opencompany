import { RouterProvider } from '@tanstack/react-router';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './app.css';
import { AppProviders } from './providers.js';
import { router } from './router.js';
import { SessionProvider } from './session/session.js';

const container = document.getElementById('root');
if (container === null) {
  throw new Error('index.html is missing #root');
}

createRoot(container).render(
  <StrictMode>
    <AppProviders>
      {/*
       * Outside the router on purpose: the guard, the sign-in screen and the
       * support banner all read the same session, and the boot probe must not
       * restart every time someone navigates.
       */}
      <SessionProvider>
        <RouterProvider router={router} />
      </SessionProvider>
    </AppProviders>
  </StrictMode>,
);
