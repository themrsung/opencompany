/**
 * Every accounting endpoint hangs off a book, and a book hangs off a company,
 * so the choice has to be made once and then remembered. It lives in a context
 * rather than in each screen because moving between the chart and the journal
 * must not silently change which ledger you are reading.
 *
 * The chosen book is stored locally. It is a view preference, not a permission:
 * the server decides what the account may open, and asking it again on every
 * paint would cost a round trip before the first table can be drawn.
 */
import { useQuery } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

import { accounting, keys, type Book, type Company, type Currency } from './queries.js';

const STORAGE_KEY = 'coreintra.accounting.book';

/**
 * A currency the book does not define falls back to zero decimals.
 *
 * Not a guess at its precision — a refusal to guess. At zero, any fractional
 * part makes `<Amount>` mark the figure as abbreviated and keep the exact value
 * one keystroke away, which is the safe direction to be wrong in.
 */
const UNKNOWN_CURRENCY_DECIMALS = 0;

export interface BookContextValue {
  readonly companies: readonly Company[];
  readonly books: readonly Book[];
  readonly companyId: string | null;
  readonly bookId: string | null;
  readonly book: Book | null;
  readonly currencies: readonly Currency[];
  /** The book's base currency. Every balance is measured in it. */
  readonly baseCurrencyCode: string;
  readonly baseDecimals: number;
  readonly decimalsFor: (code: string | null | undefined) => number;
  readonly selectCompany: (companyId: string) => void;
  readonly selectBook: (bookId: string) => void;
  readonly loading: boolean;
  readonly error: unknown;
}

const BookContext = createContext<BookContextValue | null>(null);

function readStored(): { companyId: string | null; bookId: string | null } {
  try {
    const raw = globalThis.localStorage?.getItem(STORAGE_KEY);
    if (raw === null || raw === undefined) {
      return { companyId: null, bookId: null };
    }
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) {
      return { companyId: null, bookId: null };
    }
    const record = parsed as Record<string, unknown>;
    return {
      companyId: typeof record['companyId'] === 'string' ? record['companyId'] : null,
      bookId: typeof record['bookId'] === 'string' ? record['bookId'] : null,
    };
  } catch {
    // A locked-down browser policy is not a reason to fail to open the ledger.
    return { companyId: null, bookId: null };
  }
}

function writeStored(companyId: string | null, bookId: string | null): void {
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, JSON.stringify({ companyId, bookId }));
  } catch {
    // Not being able to remember the choice is survivable.
  }
}

export function BookProvider({ children }: { readonly children: ReactNode }): ReactNode {
  const stored = useMemo(readStored, []);
  const [companyChoice, setCompanyChoice] = useState<string | null>(stored.companyId);
  const [bookChoice, setBookChoice] = useState<string | null>(stored.bookId);

  const companiesQuery = useQuery({ queryKey: keys.companies, queryFn: accounting.companies });
  const companies = useMemo(
    () => (companiesQuery.data ?? []).filter((company): company is Company => typeof company.id === 'string'),
    [companiesQuery.data],
  );

  const companyId =
    companyChoice !== null && companies.some((company) => company.id === companyChoice)
      ? companyChoice
      : (companies[0]?.id ?? null);

  const booksQuery = useQuery({
    queryKey: keys.books(companyId ?? ''),
    queryFn: () => accounting.books(companyId ?? ''),
    enabled: companyId !== null,
  });
  const books = useMemo(
    () => (booksQuery.data ?? []).filter((book): book is Book => typeof book.id === 'string'),
    [booksQuery.data],
  );

  const bookId =
    bookChoice !== null && books.some((book) => book.id === bookChoice) ? bookChoice : (books[0]?.id ?? null);
  const book = books.find((candidate) => candidate.id === bookId) ?? null;

  const currenciesQuery = useQuery({
    queryKey: keys.currencies(bookId ?? ''),
    queryFn: () => accounting.currencies(bookId ?? ''),
    enabled: bookId !== null,
  });
  const currencies = useMemo(() => currenciesQuery.data ?? [], [currenciesQuery.data]);

  const selectCompany = useCallback((next: string) => {
    setCompanyChoice(next);
    setBookChoice(null);
    writeStored(next, null);
  }, []);

  const selectBook = useCallback(
    (next: string) => {
      setBookChoice(next);
      writeStored(companyId, next);
    },
    [companyId],
  );

  const baseCurrencyCode = book?.baseCurrencyCode ?? '';

  const decimalsFor = useCallback(
    (code: string | null | undefined): number => {
      if (code === null || code === undefined || code === '') {
        return currencies.find((currency) => currency.code === baseCurrencyCode)?.displayDecimals ?? UNKNOWN_CURRENCY_DECIMALS;
      }
      return currencies.find((currency) => currency.code === code)?.displayDecimals ?? UNKNOWN_CURRENCY_DECIMALS;
    },
    [currencies, baseCurrencyCode],
  );

  const value = useMemo<BookContextValue>(
    () => ({
      companies,
      books,
      companyId,
      bookId,
      book,
      currencies,
      baseCurrencyCode,
      baseDecimals: decimalsFor(baseCurrencyCode),
      decimalsFor,
      selectCompany,
      selectBook,
      loading: companiesQuery.isPending || booksQuery.isPending,
      error: companiesQuery.error ?? booksQuery.error ?? currenciesQuery.error,
    }),
    [
      companies,
      books,
      companyId,
      bookId,
      book,
      currencies,
      baseCurrencyCode,
      decimalsFor,
      selectCompany,
      selectBook,
      companiesQuery.isPending,
      companiesQuery.error,
      booksQuery.isPending,
      booksQuery.error,
      currenciesQuery.error,
    ],
  );

  return <BookContext.Provider value={value}>{children}</BookContext.Provider>;
}

/**
 * Throws when there is no provider. A screen that reads the book cannot render
 * without one, and a clear failure in a test beats a screen that quietly shows
 * an empty ledger.
 */
export function useBook(): BookContextValue {
  const context = useContext(BookContext);
  if (context === null) {
    throw new Error('useBook must be used inside the accounting area');
  }
  return context;
}
