import { Button, Col, Input, Row, Segmented, Select, Space, Typography } from 'antd';
import { ClearOutlined } from '@ant-design/icons';
import { useI18n } from '../locales';
import type { MessageKey } from '../locales';
import type { Dimensions, EndpointFilter } from '../api/types';

/** The six list-valued dimensions get a multi-select; `scheme` is handled separately. */
const MULTI_DIMENSIONS = [
  'appSystem',
  'tier',
  'envInstance',
  'country',
  'service',
  'instance',
] as const;

type MultiDimension = (typeof MULTI_DIMENSIONS)[number];

interface FilterBarProps {
  dimensions: Dimensions;
  value: EndpointFilter;
  onChange: (next: EndpointFilter) => void;
  loading?: boolean;
}

/**
 * Filter bar shared by both pages.
 *
 * Each dimension maps to repeated query parameters on the backend, so a multi-select is an
 * `IN (...)`, and dimensions AND together. `scheme` is a {@link Segmented} rather than a select
 * because it only ever has three meaningful states (all / http / https) — that is the
 * "filter out the protocol you don't care about" control from the requirements.
 */
export function FilterBar({ dimensions, value, onChange, loading }: FilterBarProps) {
  const { t } = useI18n();

  const activeCount =
    MULTI_DIMENSIONS.reduce((sum, key) => sum + (value[key]?.length ? 1 : 0), 0) +
    (value.scheme?.length ? 1 : 0) +
    (value.keyword ? 1 : 0);

  const setDimension = (key: MultiDimension, next: string[]) =>
    onChange({ ...value, [key]: next.length ? next : undefined });

  // Segmented needs a single value; the wire format is still a list so the backend stays uniform.
  const schemeValue = value.scheme?.length === 1 ? value.scheme[0] : 'all';

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Row gutter={[12, 12]}>
        {MULTI_DIMENSIONS.map((key) => (
          <Col key={key} xs={24} sm={12} md={8} lg={4}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t(`filter.${key}` as MessageKey)}
            </Typography.Text>
            <Select
              mode="multiple"
              allowClear
              loading={loading}
              style={{ width: '100%' }}
              placeholder={t('filter.placeholder')}
              value={value[key] ?? []}
              onChange={(next: string[]) => setDimension(key, next)}
              options={dimensions[key].map((option) => ({ label: option, value: option }))}
              maxTagCount="responsive"
              // Wider dropdown than the (narrow) control, so long service names stay readable.
              popupMatchSelectWidth={false}
            />
          </Col>
        ))}
      </Row>

      <Row gutter={[12, 12]} align="bottom">
        <Col xs={24} sm={12} md={8}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('filter.scheme')}
          </Typography.Text>
          <div>
            <Segmented
              value={schemeValue}
              onChange={(next) =>
                onChange({ ...value, scheme: next === 'all' ? undefined : [next as string] })
              }
              options={[
                { label: t('filter.placeholder'), value: 'all' },
                ...dimensions.scheme.map((scheme) => ({ label: scheme, value: scheme })),
              ]}
            />
          </div>
        </Col>

        <Col xs={24} sm={12} md={10}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('filter.keyword')}
          </Typography.Text>
          <Input.Search
            allowClear
            placeholder={t('filter.keywordPlaceholder')}
            defaultValue={value.keyword}
            // Search-on-submit rather than on every keystroke: each change refetches the matrix.
            onSearch={(keyword) => onChange({ ...value, keyword: keyword || undefined })}
          />
        </Col>

        <Col xs={24} md={6}>
          <Space>
            <Button icon={<ClearOutlined />} onClick={() => onChange({})} disabled={!activeCount}>
              {t('filter.reset')}
            </Button>
            {activeCount > 0 && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('filter.activeCount', { count: activeCount })}
              </Typography.Text>
            )}
          </Space>
        </Col>
      </Row>
    </Space>
  );
}
