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
  Space,
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

/**
 * Splunk session broker.
 *
 * The countdown runs on the *server's* clock: `serverTimeMillis` from `/splunk/totp` gives an offset
 * to the browser's, and a code is only valid relative to the verifier — a browser a minute fast would
 * otherwise count down against the wrong window. The code itself is refetched whenever the step
 * rolls over rather than recomputed here, so the secret never reaches the browser.
 */
export function SplunkPage() {
  const { t } = useI18n();
  const { message } = App.useApp();
  const { token } = theme.useToken();

  const [info, setInfo] = useState<TotpInfo | null>(null);
  const [infoError, setInfoError] = useState<string | null>(null);
  const offsetRef = useRef(0);
  const [now, setNow] = useState(() => Date.now());
  const [code, setCode] = useState<TotpCode | null>(null);

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
      })
      .catch((e: Error) => setInfoError(e.message));
    void loadAudit();
  }, [loadAudit]);

  // A quarter-second tick keeps the ring smooth without re-rendering the audit table noticeably.
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, []);

  const period = info?.periodSeconds ?? 30;
  const serverSeconds = Math.floor((now + offsetRef.current) / 1000);
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

  const submit = async () => {
    setSubmitting(true);
    setFailure(null);
    setSession(null);
    try {
      const result = await splunkApi.session(input);
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
          value,
          row.httpStatus != null ? `HTTP ${row.httpStatus}` : null,
          row.splunkMode ? `mode=${row.splunkMode}` : null,
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
              {info?.loginUrl && (
                <Text type="secondary">
                  {t('splunk.session.target')} <Text code>{info.loginUrl}</Text>
                </Text>
              )}
              <Space wrap>
                <Input.OTP length={digits} value={input} onChange={setInput} />
                <Button
                  type="primary"
                  loading={submitting}
                  disabled={input.length !== digits || !info?.configured || !info.splunkConfigured}
                  onClick={submit}
                >
                  {t('splunk.session.submit')}
                </Button>
              </Space>

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
                  </div>
                  <Descriptions size="small" column={1} bordered>
                    <Descriptions.Item label={t('splunk.session.source')}>
                      <Text code>{session.sourceCookie}</Text> → <Text code>{session.cookieName}</Text>
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
