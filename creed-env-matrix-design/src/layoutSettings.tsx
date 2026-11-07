import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import type { ProSettings } from '@ant-design/pro-components';

/** What Ant Design Pro's SettingDrawer edits: ProLayout's own settings plus the two theme extras. */
export type LayoutSettings = Partial<ProSettings> & { colorPrimary?: string; colorWeak?: boolean };

/** Ant Design Pro's default: header across the top, the menu in a collapsible left sider. */
export const DEFAULT_LAYOUT_SETTINGS: LayoutSettings = {
  navTheme: 'light',
  layout: 'mix',
  contentWidth: 'Fluid',
  fixedHeader: true,
  fixSiderbar: true,
  splitMenus: false,
  colorPrimary: '#1677FF',
  colorWeak: false,
};

const STORAGE_KEY = 'env-matrix.layout-settings';

/**
 * Everything the drawer edits. The four `*Render` keys (its "regional settings" switches) are
 * `false` when hidden and absent otherwise, so they have no entry in the defaults to be found by.
 */
const EDITABLE_KEYS: (keyof LayoutSettings)[] = [
  ...(Object.keys(DEFAULT_LAYOUT_SETTINGS) as (keyof LayoutSettings)[]),
  'headerRender',
  'footerRender',
  'menuRender',
  'menuHeaderRender',
];

interface LayoutSettingsValue {
  settings: LayoutSettings;
  setSettings: (next: LayoutSettings) => void;
}

const LayoutSettingsContext = createContext<LayoutSettingsValue | null>(null);

function initialSettings(): LayoutSettings {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) return { ...DEFAULT_LAYOUT_SETTINGS, ...(JSON.parse(stored) as LayoutSettings) };
  } catch {
    // Blocked storage or a hand-edited value: fall back to the defaults rather than a blank page.
  }
  return DEFAULT_LAYOUT_SETTINGS;
}

export function LayoutSettingsProvider({ children }: { children: ReactNode }) {
  const [settings, setState] = useState<LayoutSettings>(initialSettings);

  const setSettings = useCallback((next: LayoutSettings) => {
    // The drawer hands back every key it knows, including title/menu/logo that ProLayout takes as
    // props here — keep only what it edits so a stored copy can never override those.
    const picked: LayoutSettings = {};
    for (const key of EDITABLE_KEYS) {
      if (next[key] !== undefined) (picked as Record<string, unknown>)[key] = next[key];
    }
    setState(picked);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(picked));
    } catch {
      // Not persisted; the setting still applies for this tab.
    }
  }, []);

  // The drawer only applies the colour-weak filter when the switch is flipped, so a reload with it
  // stored as on would come back unfiltered. Owning it here covers both.
  useEffect(() => {
    document.body.style.filter = settings.colorWeak ? 'invert(80%)' : '';
  }, [settings.colorWeak]);

  const value = useMemo(() => ({ settings, setSettings }), [settings, setSettings]);
  return <LayoutSettingsContext.Provider value={value}>{children}</LayoutSettingsContext.Provider>;
}

export function useLayoutSettings(): LayoutSettingsValue {
  const value = useContext(LayoutSettingsContext);
  if (!value) throw new Error('useLayoutSettings must be used inside <LayoutSettingsProvider>');
  return value;
}
