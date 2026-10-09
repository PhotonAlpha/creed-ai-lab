import { useCallback, useEffect, useRef, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import {
  Alert,
  App,
  Button,
  Descriptions,
  Empty,
  Input,
  Progress,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  theme,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { KeyOutlined, LoginOutlined, ReloadOutlined } from '@ant-design/icons';
import { splunkApi } from '../../api/splunk';
import { ApiError } from '../../api/client';
import { useI18n } from '../../locales';
import type { SplunkAuditRow, SplunkSession, TotpCode, TotpInfo } from '../../api/types';

const { Text, Paragraph } = Typography;

/** Same rule as the server's COOKIE_NAME (server/splunk/config.js) — it ends up inside a script. */
const COOKIE_NAME = /^[A-Za-z0-9_.-]{1,64}$/;

/**
 * The shared account's username with its first three characters masked — enough to tell targets'
 * accounts apart, not enough to copy. Shorter names are masked whole.
 */
const maskUsername = (username: string) => '*'.repeat(Math.min(3, username.length)) + username.slice(3);

/**
 * An audit row's detail as the page shows it. The server writes
 * `as <username> on <target>[ via <tunnel>], <sessionCookie> -> <scriptCookie>`; the username is
 * masked and the cookie names dropped, like everywhere else on the page. The stored row is unchanged.
 */
const displayDetail = (detail: string) => detail
  .replace(/^as (\S+)/, (_, user: string) => `as ${maskUsername(user)}`)
  .replace(/, [A-Za-z0-9_.-]+ -> [A-Za-z0-9_.-]+$/, '');

const hostOf = (loginUrl: string | null) => {
  try {
    return loginUrl ? new URL(loginUrl).host : null;
  } catch {
    return null;
  }
};

/** The login URL as the browser would reach it through the tunnel: same path, the tunnel's host:port. */
const throughTunnel = (loginUrl: string | null, tunnel: string) => {
  if (!loginUrl) return null;
  try {
    const url = new URL(loginUrl);
    return `${url.protocol}//${tunnel}${url.pathname}${url.search}`;
  } catch {
    return null;
  }
};

/** Per-browser convenience only — storage can be unavailable (private mode), so every access is guarded. */
const TARGET_KEY = 'env-matrix.splunk.target';
const readStoredTarget = () => {
  try {
    return window.localStorage.getItem(TARGET_KEY);
  } catch {
    return null;
  }
};
const storeTarget = (id: string) => {
  try {
    window.localStorage.setItem(TARGET_KEY, id);
  } catch {
    // Not remembered; nothing else depends on it.
  }
};

/**
 * Splunk session broker.
 *
 * The countdown runs on the *server's* clock: `serverTimeMillis` from `/splunk/totp` gives an offset
 * to the browser's, and a code is only valid relative to the verifier — a browser a minute fast would
 * otherwise count down against the wrong window. The code itself is refetched whenever the step
 * rolls over rather than recomputed here, so the secret never reaches the browser.
 *
 * The login target is a dropdown over the server's configured Splunk instances. Picking one shows
 * that target's login URL and username; its password stays on the server (the page only learns
 * whether it is set), and the session request names the target so the server logs in with that
 * target's own credentials.
 *
 * Both cookie names start at the target's configured values and can be changed for one login (the
 * name Splunk sets follows its web port, and the name the browser needs depends on how it reaches
 * Splunk). A target with a tunnel gets a switch: on, the server connects through the tunnel's
 * host:port while still addressing the real Splunk host.
 */
export function SplunkPage() {
  const { t, lang } = useI18n();
  const { message } = App.useApp();
  const { token } = theme.useToken();

  const [info, setInfo] = useState<TotpInfo | null>(null);
  const [infoError, setInfoError] = useState<string | null>(null);
  const offsetRef = useRef(0);
  const [now, setNow] = useState(() => Date.now());
  const [code, setCode] = useState<TotpCode | null>(null);

  const [targetId, setTargetId] = useState<string | undefined>();
  const [sessionCookie, setSessionCookie] = useState('');
  const [scriptCookieName, setScriptCookieName] = useState('');
  const [viaTunnel, setViaTunnel] = useState(false);
  const [input, setInput] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [session, setSession] = useState<SplunkSession | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const [audit, setAudit] = useState<SplunkAuditRow[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);

  const loadAudit = useCallback(async () => {
    setAuditLoading(true);
    try {
      setAudit(await splunkApi.audit(50));
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setAuditLoading(false);
    }
  }, [message]);

  useEffect(() => {
    splunkApi
      .totp()
      .then((response) => {
        offsetRef.current = response.serverTimeMillis - Date.now();
        setInfo(response);
        // The last target this browser used, if the server still offers it; else the server's default.
        const stored = readStoredTarget();
        setTargetId(response.targets.some((t) => t.id === stored) ? (stored as string) : response.defaultTarget);
      })
      .catch((e: Error) => setInfoError(e.message));
    void loadAudit();
  }, [loadAudit]);

  /** Re-reads /splunk/totp (block state, targets) without resetting the chosen target. */
  const refreshInfo = useCallback(() => {
    splunkApi
      .totp()
      .then((response) => {
        offsetRef.current = response.serverTimeMillis - Date.now();
        setInfo(response);
      })
      .catch((e: Error) => setInfoError(e.message));
  }, []);

  // A quarter-second tick keeps the ring smooth without re-rendering the audit table noticeably.
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, []);

  const period = info?.periodSeconds ?? 60;
  const serverNow = now + offsetRef.current;
  const serverSeconds = Math.floor(serverNow / 1000);
  const step = Math.floor(serverSeconds / period);
  const remaining = period - (serverSeconds % period);

  useEffect(() => {
    if (!info?.codeVisible) return;
    let cancelled = false;
    splunkApi
      .currentCode()
      .then((response) => {
        if (!cancelled) setCode(response);
      })
      .catch((e: Error) => {
        if (!cancelled) message.error(e.message);
      });
    return () => {
      cancelled = true;
    };
  }, [info?.codeVisible, step, message]);

  const target = info?.targets.find((t) => t.id === targetId);

  // The block window is the server's decision; the page only asks again when the server said it flips
  // (`changesAtMillis`, on the server's clock), so a page left open unlocks at 09:00 by itself.
  const block = info?.block;
  const blocked = Boolean(block?.blocked);
  const blockFlipped = block?.changesAtMillis != null && serverNow >= block.changesAtMillis;
  useEffect(() => {
    if (blockFlipped) refreshInfo();
  }, [blockFlipped, refreshInfo]);
  /** HH:mm of the flip in the block's own zone — the same wall clock the windows are written in. */
  const blockUntil = block?.changesAtMillis != null
    ? new Intl.DateTimeFormat(lang, { timeZone: block.zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
      .format(block.changesAtMillis)
    : null;

  // A new target starts from its own defaults — another target's cookie names or tunnel are meaningless here.
  useEffect(() => {
    if (!target) return;
    setSessionCookie(target.sessionCookie);
    setScriptCookieName(target.scriptCookieName);
    setViaTunnel(target.tunnelDefault);
    // Keyed on the id: `target` is a fresh object whenever `info` is.
  }, [target?.id]);

  const sessionCookieValid = COOKIE_NAME.test(sessionCookie);
  const scriptCookieValid = COOKIE_NAME.test(scriptCookieName);

  const submit = async () => {
    setSubmitting(true);
    setFailure(null);
    setSession(null);
    try {
      const result = await splunkApi.session(input, targetId, {
        sessionCookie,
        scriptCookieName,
        viaTunnel: Boolean(target?.tunnel) && viaTunnel,
      });
      setSession(result);
      setInput('');
      message.success(t('splunk.issued'));
    } catch (e) {
      const error = e as ApiError;
      const code = error.body?.error;
      // The server consumes a code the moment it verifies it. After a replay, or a Splunk failure
      // that came *after* a successful check, resubmitting what is still in the box can only come
      // back "replayed" — so clear it and say to wait for the next one.
      const spent = code === 'otp_replayed' || code?.startsWith('splunk_');
      if (spent) setInput('');
      setFailure(
        [code ? `${code}: ${error.message}` : error.message, spent ? t('splunk.session.codeSpent') : null]
          .filter(Boolean)
          .join(' — '),
      );
    } finally {
      setSubmitting(false);
      void loadAudit();
    }
  };

  const digits = info?.digits ?? 6;
  const expiring = remaining <= 5;
  const targetLabel = (id: string) => info?.targets.find((t) => t.id === id)?.label ?? id;

  const columns: TableColumnsType<SplunkAuditRow> = [
    {
      title: t('splunk.audit.time'),
      dataIndex: 'createdAt',
      width: 170,
      render: (value: string) => new Date(value).toLocaleString(),
    },
    {
      title: t('splunk.audit.event'),
      dataIndex: 'eventType',
      width: 130,
      render: (value: string) => (
        <Tag color={value === 'OTP_VERIFY' ? 'blue' : 'purple'}>
          {value === 'OTP_VERIFY' ? t('splunk.audit.otp') : t('splunk.audit.login')}
        </Tag>
      ),
    },
    {
      title: t('splunk.audit.outcome'),
      dataIndex: 'outcome',
      width: 100,
      render: (value: string) => (
        <Tag color={value === 'SUCCESS' ? 'success' : 'error'}>
          {value === 'SUCCESS' ? t('splunk.audit.success') : t('splunk.audit.failure')}
        </Tag>
      ),
    },
    { title: t('splunk.audit.reason'), dataIndex: 'reason', width: 150, render: (v) => v ?? '—' },
    {
      title: t('splunk.audit.detail'),
      dataIndex: 'detail',
      ellipsis: true,
      render: (value: string | null, row) => {
        const parts = [
          value ? displayDetail(value) : null,
          row.httpStatus != null ? `HTTP ${row.httpStatus}` : null,
          row.splunkMode ? `mode=${row.splunkMode}` : null,
          row.forwardedFor ? `forwardedFor=${row.forwardedFor}` : null,
        ].filter(Boolean);
        return parts.length ? parts.join(' · ') : '—';
      },
    },
    {
      title: t('splunk.audit.client'),
      dataIndex: 'clientIp',
      width: 150,
      render: (value: string | null, row) => (
        <Tooltip title={[row.forwardedFor && `X-Forwarded-For: ${row.forwardedFor}`, row.userAgent].filter(Boolean).join('\n')}>
          <Text>{value ?? '—'}</Text>
        </Tooltip>
      ),
    },
    {
      title: t('splunk.audit.fingerprint'),
      dataIndex: 'cookieFingerprint',
      width: 170,
      render: (value: string | null) => (value ? <Text code>{value}</Text> : '—'),
    },
    {
      title: t('splunk.audit.duration'),
      dataIndex: 'durationMs',
      width: 90,
      align: 'right',
      render: (value: number | null) => (value != null ? `${value} ms` : '—'),
    },
    {
      title: t('splunk.audit.correlation'),
      dataIndex: 'correlationId',
      width: 120,
      render: (value: string) => (
        <Tooltip title={value}>
          <Text copyable={{ text: value }}>{value.slice(0, 8)}</Text>
        </Tooltip>
      ),
    },
  ];

  return (
    <PageContainer title={t('splunk.title')} subTitle={t('splunk.subtitle')}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {infoError && <Alert type="error" showIcon message={infoError} />}
        {info && !info.configured && <Alert type="error" showIcon message={t('splunk.notConfigured')} />}
        {info && !info.splunkConfigured && (
          <Alert type="warning" showIcon message={t('splunk.splunkNotConfigured')} />
        )}
        {info?.splunkMode === 'mock' && <Alert type="info" showIcon message={t('splunk.mockNotice')} />}
        {block && blocked && (
          <Alert
            type="warning"
            showIcon
            message={blockUntil
              ? t('splunk.block.active', { windows: block.windows.join(', '), zone: block.zone, until: blockUntil })
              : t('splunk.block.activeForever', { windows: block.windows.join(', '), zone: block.zone })}
          />
        )}

        <ProCard gutter={16} wrap ghost>
          <ProCard
            colSpan={{ xs: 24, md: 10 }}
            title={
              <Space>
                <KeyOutlined />
                {t('splunk.otp.title')}
              </Space>
            }
            bordered
          >
            <Space direction="vertical" align="center" style={{ width: '100%' }}>
              <Progress
                type="circle"
                percent={(remaining / period) * 100}
                status={expiring ? 'exception' : 'normal'}
                format={() => `${remaining}s`}
                size={96}
              />
              {info?.codeVisible ? (
                <>
                  <Text
                    copyable={code ? { text: code.code } : false}
                    style={{
                      fontFamily: token.fontFamilyCode,
                      fontSize: token.fontSizeHeading1,
                      letterSpacing: token.marginXS,
                      color: expiring ? token.colorError : token.colorText,
                    }}
                  >
                    {code?.code ?? '— — —'}
                  </Text>
                  <Button size="small" disabled={!code} onClick={() => code && setInput(code.code)}>
                    {t('splunk.otp.fill')}
                  </Button>
                </>
              ) : (
                <Text type="secondary">{t('splunk.otp.hidden')}</Text>
              )}
              <Text type="secondary">
                {t('splunk.otp.window', { period, drift: info?.allowedDriftSteps ?? 1 })}
              </Text>
            </Space>
          </ProCard>

          <ProCard
            colSpan={{ xs: 24, md: 14 }}
            title={
              <Space>
                <LoginOutlined />
                {t('splunk.session.title')}
              </Space>
            }
            extra={info && <Tag color={info.splunkMode === 'mock' ? 'orange' : 'green'}>{info.splunkMode}</Tag>}
            bordered
          >
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <Space direction="vertical" size="small" style={{ width: '100%' }}>
                <Space wrap>
                  <Text>{t('splunk.session.target')}</Text>
                  <Select
                    style={{ minWidth: 260 }}
                    value={targetId}
                    onChange={(id: string) => {
                      setTargetId(id);
                      storeTarget(id);
                      // A session shown under another target's name would be misleading.
                      setSession(null);
                      setFailure(null);
                    }}
                    options={(info?.targets ?? []).map((option) => ({
                      value: option.id,
                      label: option.label,
                      title: option.loginUrl ?? undefined,
                    }))}
                    optionRender={(option) => {
                      const item = info?.targets.find((x) => x.id === option.value);
                      return (
                        <Space direction="vertical" size={0}>
                          <Space size="small">
                            <Text>{option.label}</Text>
                            {item && !item.configured && <Tag color="warning" bordered={false}>{t('splunk.session.unset')}</Tag>}
                          </Space>
                          {item?.loginUrl && <Text type="secondary" style={{ fontSize: token.fontSizeSM }}>{item.loginUrl}</Text>}
                        </Space>
                      );
                    }}
                    loading={!info && !infoError}
                  />
                </Space>
                {target && (
                  <Descriptions size="small" column={1} bordered>
                    <Descriptions.Item label={t('splunk.session.loginUrl')}>
                      {target.loginUrl ? <Text code>{target.loginUrl}</Text> : <Text type="danger">{t('splunk.session.unset')}</Text>}
                    </Descriptions.Item>
                    <Descriptions.Item label={t('splunk.session.username')}>
                      {target.username ? <Text code>{maskUsername(target.username)}</Text> : <Text type="danger">{t('splunk.session.unset')}</Text>}
                    </Descriptions.Item>
                    {/* Password, session cookie and script cookie are not shown (by request). The cookie
                        names still go with the request: they are preset from the target above. */}
                    {target.tunnel && (
                      <Descriptions.Item label={t('splunk.session.tunnel')}>
                        <Space direction="vertical" size={0}>
                          <Space wrap>
                            <Switch checked={viaTunnel} onChange={setViaTunnel} />
                            <Text code>{target.tunnel}</Text>
                          </Space>
                          {viaTunnel && (
                            <Text type="secondary" style={{ fontSize: token.fontSizeSM }}>
                              {t('splunk.session.tunnelHint', {
                                tunnel: target.tunnel,
                                host: hostOf(target.loginUrl) ?? '—',
                              })}
                            </Text>
                          )}
                        </Space>
                      </Descriptions.Item>
                    )}
                  </Descriptions>
                )}
                {target && !target.configured && (
                  <Alert type="warning" showIcon message={t('splunk.session.targetNotConfigured')} />
                )}
              </Space>
              <Space wrap>
                <Input.OTP length={digits} value={input} onChange={setInput} />
                <Button
                  type="primary"
                  loading={submitting}
                  disabled={
                    blocked || input.length !== digits || !info?.configured || !target?.configured
                    || !sessionCookieValid || !scriptCookieValid
                  }
                  onClick={submit}
                >
                  {t('splunk.session.submit')}
                </Button>
              </Space>
              {block && !blocked && block.windows.length > 0 && (
                <Text type="secondary">{t('splunk.block.schedule', { windows: block.windows.join(', '), zone: block.zone })}</Text>
              )}

              {failure && <Alert type="error" showIcon message={t('splunk.session.failed')} description={failure} />}

              {session && (
                <>
                  {session.mode === 'mock' && <Alert type="warning" showIcon message={t('splunk.session.mockValue')} />}
                  <div>
                    <Text strong>{t('splunk.session.script')}</Text>
                    <Paragraph
                      copyable={{ text: session.script }}
                      code
                      style={{ fontFamily: token.fontFamilyCode, wordBreak: 'break-all', marginTop: token.marginXS }}
                    >
                      {session.script}
                    </Paragraph>
                    <Text type="secondary">{t('splunk.session.scriptHint')}</Text>
                    {session.tunnel && throughTunnel(target?.loginUrl ?? null, session.tunnel) && (
                      <Alert
                        type="info"
                        showIcon
                        style={{ marginTop: token.marginXS }}
                        message={t('splunk.session.tunnelBrowser', {
                          url: throughTunnel(target?.loginUrl ?? null, session.tunnel) as string,
                        })}
                      />
                    )}
                  </div>
                  <Descriptions size="small" column={1} bordered>
                    <Descriptions.Item label={t('splunk.session.target')}>{targetLabel(session.target)}</Descriptions.Item>
                    <Descriptions.Item label={t('splunk.session.route')}>
                      {session.tunnel
                        ? <>{t('splunk.session.tunnel')} <Text code>{session.tunnel}</Text></>
                        : t('splunk.session.direct')}
                    </Descriptions.Item>
                    <Descriptions.Item label={t('splunk.audit.fingerprint')}>
                      <Text code>{session.cookieFingerprint}</Text>
                    </Descriptions.Item>
                    <Descriptions.Item label={t('splunk.audit.correlation')}>
                      <Text copyable>{session.correlationId}</Text>
                    </Descriptions.Item>
                    <Descriptions.Item label={t('splunk.session.issuedAt')}>
                      {new Date(session.issuedAt).toLocaleString()}
                    </Descriptions.Item>
                  </Descriptions>
                </>
              )}
            </Space>
          </ProCard>
        </ProCard>

        <ProCard
          title={t('splunk.audit.title')}
          subTitle={t('splunk.audit.subtitle')}
          bordered
          extra={
            <Button icon={<ReloadOutlined />} onClick={() => void loadAudit()} loading={auditLoading}>
              {t('splunk.audit.reload')}
            </Button>
          }
        >
          <Table<SplunkAuditRow>
            rowKey="id"
            size="small"
            columns={columns}
            dataSource={audit}
            loading={auditLoading}
            pagination={{ pageSize: 10, hideOnSinglePage: true }}
            scroll={{ x: 1300 }}
            locale={{ emptyText: <Empty description={t('splunk.audit.empty')} /> }}
          />
        </ProCard>
      </Space>
    </PageContainer>
  );
}
