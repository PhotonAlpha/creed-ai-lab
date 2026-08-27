import type { CSSProperties } from 'react';
import { Link, Route, Routes, useLocation } from 'react-router-dom';
import { ProLayout } from '@ant-design/pro-components';
import { Button, theme } from 'antd';
import { ApartmentOutlined, GlobalOutlined, SettingOutlined, TableOutlined } from '@ant-design/icons';
import { useI18n } from './locales';
import { MatrixPage } from './pages/Matrix';
import { ConfigPage } from './pages/Config';
import { TopologyPage } from './pages/Topology';

export function App() {
  const { t, toggleLang } = useI18n();
  const { pathname } = useLocation();
  const { token } = theme.useToken();

  /**
   * Bridges antd design tokens into the CSS variables `index.css` uses for cell highlighting. This
   * keeps the stylesheet free of hard-coded colours and free of `.ant-*` overrides, so the
   * highlighting follows whatever theme the root ConfigProvider sets.
   */
  const tokenVars = {
    '--env-matrix-conflict-bg': token.colorErrorBg,
    '--env-matrix-conflict-border': token.colorError,
    '--env-matrix-dirty-border': token.colorWarning,
  } as CSSProperties;

  return (
    <div style={{ height: '100%', ...tokenVars }}>
      <ProLayout
        title={t('app.title')}
        logo={false}
        layout="top"
        fixedHeader
        contentWidth="Fluid"
        location={{ pathname }}
        route={{
          path: '/',
          routes: [
            { path: '/', name: t('nav.matrix'), icon: <TableOutlined /> },
            { path: '/topology', name: t('nav.topology'), icon: <ApartmentOutlined /> },
            { path: '/config', name: t('nav.config'), icon: <SettingOutlined /> },
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
        </Routes>
      </ProLayout>
    </div>
  );
}
