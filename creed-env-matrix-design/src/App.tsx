import type { CSSProperties } from 'react';
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { ProLayout, SettingDrawer } from '@ant-design/pro-components';
import { Button, theme } from 'antd';
import {
  ApartmentOutlined,
  BookOutlined,
  BranchesOutlined,
  BugOutlined,
  CloudDownloadOutlined,
  DiffOutlined,
  FileTextOutlined,
  GlobalOutlined,
  KeyOutlined,
  LinkOutlined,
  LockOutlined,
  SafetyOutlined,
  SettingOutlined,
  SwapOutlined,
  TableOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import { useI18n } from './locales';
import { useLayoutSettings } from './layoutSettings';
import { MatrixPage } from './pages/Matrix';
import { ConfigPage } from './pages/Config';
import { TopologyPage } from './pages/Topology';
import { SplunkPage } from './pages/Splunk';
import { AesPage } from './pages/Aes';
import { VeracodePage } from './pages/Veracode';
import { MigrationRiskPage } from './pages/MigrationRisk';
import { InstallPage } from './pages/Ccrg/Install';
import { PromptPage } from './pages/Ccrg/Prompt';
import { ComparisonPage } from './pages/Ccrg/Comparison';
import { ShortcutPage } from './pages/Ccrg/Shortcut';
import { SecurePage } from './pages/Ccrg/Secure';
import { DictPage } from './pages/Ccrg/Dict';

export function App() {
  const { t, toggleLang } = useI18n();
  const { pathname } = useLocation();
  const { token } = theme.useToken();
  const { settings, setSettings } = useLayoutSettings();

  /**
   * Bridges antd design tokens into the CSS variables `index.css` uses for cell highlighting. This
   * keeps the stylesheet free of hard-coded colours and free of `.ant-*` overrides, so the
   * highlighting follows whatever theme the root ConfigProvider sets.
   */
  const tokenVars = {
    '--env-matrix-conflict-bg': token.colorErrorBg,
    '--env-matrix-conflict-border': token.colorError,
    '--env-matrix-dirty-border': token.colorWarning,
    '--env-matrix-selected-bg': token.controlItemBgActive,
    '--ccrg-group-bg': token.colorSuccessBg,
    '--ccrg-today-bg': token.colorSuccessBg,
    '--ccrg-yesterday-bg': token.colorWarningBg,
  } as CSSProperties;

  return (
    <div style={{ height: '100%', ...tokenVars }}>
      <ProLayout
        title={t('app.title')}
        logo={false}
        // layout / navTheme / fixed header & sider / content width / split menus: from the drawer
        {...settings}
        siderWidth={208}
        location={{ pathname }}
        route={{
          path: '/',
          routes: [
            { path: '/', name: t('nav.matrix'), icon: <TableOutlined /> },
            { path: '/topology', name: t('nav.topology'), icon: <ApartmentOutlined /> },
            { path: '/config', name: t('nav.config'), icon: <SettingOutlined /> },
            { path: '/splunk', name: t('nav.splunk'), icon: <KeyOutlined /> },
            { path: '/aes', name: t('nav.aes'), icon: <LockOutlined /> },
            { path: '/veracode', name: t('nav.veracode'), icon: <BugOutlined /> },
            { path: '/migration-risk', name: t('nav.migrationRisk'), icon: <BranchesOutlined /> },
            {
              // The old ccrg-tool-ui (Vue), migrated; backend is ccrg-tool-starter behind /api/ccrg.
              path: '/ccrg',
              name: t('nav.ccrg'),
              icon: <ToolOutlined />,
              routes: [
                { path: '/ccrg/install', name: t('nav.ccrg.install'), icon: <CloudDownloadOutlined /> },
                { path: '/ccrg/report', name: t('nav.ccrg.report'), icon: <FileTextOutlined /> },
                { path: '/ccrg/batch', name: t('nav.ccrg.batch'), icon: <DiffOutlined /> },
                { path: '/ccrg/crossover', name: t('nav.ccrg.crossover'), icon: <SwapOutlined /> },
                { path: '/ccrg/shortcut', name: t('nav.ccrg.shortcut'), icon: <LinkOutlined /> },
                { path: '/ccrg/secure', name: t('nav.ccrg.secure'), icon: <SafetyOutlined /> },
                { path: '/ccrg/dict', name: t('nav.ccrg.dict'), icon: <BookOutlined /> },
              ],
            },
          ],
        }}
        menuItemRender={(item, dom) => <Link to={item.path ?? '/'}>{dom}</Link>}
        actionsRender={() => [
          <Button key="lang" type="text" icon={<GlobalOutlined />} onClick={toggleLang}>
            {t('nav.lang')}
          </Button>,
        ]}
        // No avatar/footer chrome: this is an internal tool with no accounts behind it.
        avatarProps={false}
      >
        <Routes>
          <Route path="/" element={<MatrixPage />} />
          <Route path="/topology" element={<TopologyPage />} />
          <Route path="/config" element={<ConfigPage />} />
          <Route path="/splunk" element={<SplunkPage />} />
          <Route path="/aes" element={<AesPage />} />
          <Route path="/veracode" element={<VeracodePage />} />
          <Route path="/migration-risk" element={<MigrationRiskPage />} />
          <Route path="/ccrg" element={<Navigate to="/ccrg/install" replace />} />
          <Route path="/ccrg/install" element={<InstallPage />} />
          <Route path="/ccrg/report" element={<PromptPage />} />
          {/* keyed: one component, two saved forms — a shared instance would carry state across */}
          <Route path="/ccrg/batch" element={<ComparisonPage key="batch" mode="batch" />} />
          <Route path="/ccrg/crossover" element={<ComparisonPage key="crossover" mode="crossover" />} />
          <Route path="/ccrg/shortcut" element={<ShortcutPage />} />
          <Route path="/ccrg/secure" element={<SecurePage />} />
          <Route path="/ccrg/dict" element={<DictPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </ProLayout>
      {/*
        * Ant Design Pro's "page style setting" drawer (the handle on the right edge). Persisted by
        * LayoutSettingsProvider instead of the drawer's default of writing every setting into the
        * query string, which would leak into every link and into pages that read their own params.
        * No copy button / hint: they exist to paste into a umi `defaultSettings`, which this app
        * does not have, and the clipboard API is unavailable over plain HTTP.
        */}
      <SettingDrawer
        pathname={pathname}
        enableDarkTheme
        disableUrlParams
        hideCopyButton
        hideHintAlert
        settings={settings}
        onSettingChange={setSettings}
      />
    </div>
  );
}
