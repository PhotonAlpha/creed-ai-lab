// antd 5 targets React 16-18; on React 19 its wave/portal internals still call the removed
// ReactDOM.render APIs. This is the official compatibility shim and must be imported before antd —
// without it antd logs "[antd: compatible] antd v5 support React is 16 ~ 18" and Modal/message
// misbehave.
import '@ant-design/v5-patch-for-react-19';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App as AntdApp, ConfigProvider } from 'antd';
import { ProConfigProvider, enUSIntl, zhCNIntl } from '@ant-design/pro-components';
import { I18nProvider, useI18n } from './locales';
import { App } from './App';
import './index.css';

/**
 * The single root `ConfigProvider` (theme + antd's own locale bundle). `AntdApp` supplies the
 * context that `App.useApp()` needs, so pages get `message`/`modal` bound to this provider instead of
 * antd's detached static methods — which would ignore the theme and warn in the console.
 */
function Root() {
  const { lang, antdLocale } = useI18n();
  return (
    <ConfigProvider
      locale={antdLocale}
      theme={{
        token: {
          // Slightly tighter than the default so a wide matrix fits more columns on screen.
          borderRadius: 6,
          fontSize: 13,
        },
        components: {
          Table: {
            cellPaddingBlockSM: 4,
            cellPaddingInlineSM: 8,
          },
        },
      }}
    >
      {/*
        * ProComponents keeps its own message catalogue and does NOT read antd's `locale`, so
        * without this its built-in strings — form placeholders, pagination, the table's column
        * settings — stayed Chinese with the UI in English.
        */}
      <ProConfigProvider intl={lang === 'zh-CN' ? zhCNIntl : enUSIntl}>
        <AntdApp>
          <App />
        </AntdApp>
      </ProConfigProvider>
    </ConfigProvider>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <I18nProvider>
        <Root />
      </I18nProvider>
    </BrowserRouter>
  </StrictMode>,
);
