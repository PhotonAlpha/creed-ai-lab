import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import type { Locale } from 'antd/es/locale';
import enUSAntd from 'antd/locale/en_US';
import zhCNAntd from 'antd/locale/zh_CN';
import enUS from './en-US';
import zhCN from './zh-CN';

export type Lang = 'en-US' | 'zh-CN';
export type MessageKey = keyof typeof enUS;

const DICTIONARIES: Record<Lang, Record<MessageKey, string>> = {
  'en-US': enUS,
  'zh-CN': zhCN,
};

/** antd's own locale bundle, so built-in strings (pagination, empty, …) match the chosen language. */
const ANTD_LOCALES: Record<Lang, Locale> = {
  'en-US': enUSAntd,
  'zh-CN': zhCNAntd,
};

const STORAGE_KEY = 'env-matrix.lang';

interface I18nValue {
  lang: Lang;
  antdLocale: Locale;
  setLang: (lang: Lang) => void;
  toggleLang: () => void;
  /** `t('config.dirty', { count: 3 })` interpolates `{count}` placeholders. */
  t: (key: MessageKey, params?: Record<string, string | number>) => string;
}

const I18nContext = createContext<I18nValue | null>(null);

function initialLang(): Lang {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (stored === 'en-US' || stored === 'zh-CN') {
    return stored;
  }
  // Anything Chinese-ish lands on zh-CN; everyone else gets English.
  return navigator.language.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US';
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(initialLang);

  const setLang = useCallback((next: Lang) => {
    setLangState(next);
    localStorage.setItem(STORAGE_KEY, next);
    document.documentElement.lang = next;
  }, []);

  const value = useMemo<I18nValue>(() => {
    const dictionary = DICTIONARIES[lang];
    return {
      lang,
      antdLocale: ANTD_LOCALES[lang],
      setLang,
      toggleLang: () => setLang(lang === 'en-US' ? 'zh-CN' : 'en-US'),
      t: (key, params) => {
        const template = dictionary[key] ?? key;
        if (!params) return template;
        return Object.entries(params).reduce(
          (text, [name, replacement]) => text.replaceAll(`{${name}}`, String(replacement)),
          template,
        );
      },
    };
  }, [lang, setLang]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const value = useContext(I18nContext);
  if (!value) {
    throw new Error('useI18n must be used inside <I18nProvider>');
  }
  return value;
}
