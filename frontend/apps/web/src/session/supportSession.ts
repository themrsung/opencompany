import type { components } from '@coreintra/api-client';
import {
  useMutation,
  useQueryClient,
  useQuery,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { api } from '../api/client.js';

export type LiveSession = components['schemas']['LiveSession'];

export const LIVE_SUPPORT_KEY = ['support', 'session', 'live'] as const;

/**
 * The live vendor support sessions in this company.
 *
 * Polled rather than fetched once. §8 makes the banner a promise to every
 * employee that they will know while an outsider is inside, and a banner that
 * turns up three minutes late has already broken that promise — so this pays
 * one small request every ten seconds on an intranet LAN, which is cheap, and
 * refetches on window focus like everything else.
 */
export function useLiveSupportSessions(enabled: boolean): UseQueryResult<LiveSession[], Error> {
  return useQuery({
    queryKey: LIVE_SUPPORT_KEY,
    queryFn: () => api.get<LiveSession[]>('/support/session/live'),
    enabled,
    refetchInterval: 10_000,
    staleTime: 0,
    retry: false,
  });
}

/** Ends a live session immediately. Any master may; the engineer may not. */
export function useRevokeSupportSession(): UseMutationResult<void, Error, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (grantId: string) =>
      api.post<void>(`/support/temporary-master/${encodeURIComponent(grantId)}/revoke`, undefined),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: LIVE_SUPPORT_KEY });
    },
  });
}
