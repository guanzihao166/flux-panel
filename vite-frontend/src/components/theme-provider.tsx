import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

export type ThemePreference = 'auto' | 'light' | 'dark';
type ResolvedTheme = 'light' | 'dark';

interface ThemeContextValue {
  preference: ThemePreference;
  resolvedTheme: ResolvedTheme;
  setPreference: (preference: ThemePreference) => void;
}

const STORAGE_KEY = 'theme-preference';
const ThemeContext = createContext<ThemeContextValue | null>(null);

const getPreference = (): ThemePreference => {
  const saved = window.localStorage.getItem(STORAGE_KEY);
  return saved === 'light' || saved === 'dark' || saved === 'auto' ? saved : 'auto';
};

const getAutomaticTheme = (date = new Date()): ResolvedTheme => {
  const hour = date.getHours();
  return hour >= 6 && hour < 18 ? 'light' : 'dark';
};

const resolveTheme = (preference: ThemePreference): ResolvedTheme =>
  preference === 'auto' ? getAutomaticTheme() : preference;

interface ThemeProviderProps {
  children: React.ReactNode;
}

export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
  const [preference, setPreferenceState] = useState<ThemePreference>(getPreference);
  const [resolvedTheme, setResolvedTheme] = useState<ResolvedTheme>(() => resolveTheme(getPreference()));

  const applyTheme = useCallback((nextPreference: ThemePreference) => {
    const nextTheme = resolveTheme(nextPreference);
    setResolvedTheme(nextTheme);
    document.documentElement.classList.toggle('dark', nextTheme === 'dark');
    document.documentElement.dataset.theme = nextTheme;
    document.documentElement.style.colorScheme = nextTheme;
  }, []);

  const setPreference = useCallback((nextPreference: ThemePreference) => {
    window.localStorage.setItem(STORAGE_KEY, nextPreference);
    setPreferenceState(nextPreference);
    applyTheme(nextPreference);
  }, [applyTheme]);

  useEffect(() => {
    applyTheme(preference);
    if (preference !== 'auto') return;

    const timer = window.setInterval(() => applyTheme('auto'), 60_000);
    const handleVisibility = () => {
      if (!document.hidden) applyTheme('auto');
    };
    document.addEventListener('visibilitychange', handleVisibility);

    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', handleVisibility);
    };
  }, [applyTheme, preference]);

  const value = useMemo(() => ({ preference, resolvedTheme, setPreference }), [preference, resolvedTheme, setPreference]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
};

export const useAppTheme = () => {
  const context = useContext(ThemeContext);
  if (!context) throw new Error('useAppTheme must be used within ThemeProvider');
  return context;
};
