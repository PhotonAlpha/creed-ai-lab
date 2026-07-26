import { Badge, Table, Tag, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useI18n } from '../../locales';
import { HealthDot } from '../../components/HealthTag';
import type { Endpoint, MatrixCell } from '../../api/types';

/** One table row = one service; the country columns hold that service's cells. */
export interface MatrixRow {
  service: string;
  cells: Record<string, MatrixCell | undefined>;
}

interface MatrixTableProps {
  services: string[];
  countries: string[];
  cells: MatrixCell[];
  loading: boolean;
  /** Hide rows without a conflict — the "I only care about clashes" view. */
  conflictsOnly: boolean;
}

function EndpointLine({ endpoint }: { endpoint: Endpoint }) {
  const { t } = useI18n();
  return (
    <Tooltip
      title={
        <div>
          <div>{endpoint.url}</div>
          <div>
            {endpoint.ip}:{endpoint.port}
          </div>
          <div>
            {endpoint.appSystem} / {endpoint.envInstance} / {endpoint.instance}
          </div>
          <div>
            {t('column.health')}: {t(`health.${endpoint.health}`)}
          </div>
          {endpoint.conflictKeys.length > 0 && (
            <div>
              {t('matrix.tooltipConflict')}: {endpoint.conflictKeys.join(', ')}
            </div>
          )}
          {endpoint.note && <div>{endpoint.note}</div>}
        </div>
      }
    >
      <div className="env-matrix-endpoint">
        <HealthDot health={endpoint.health} />
        <Tag bordered={false} color={endpoint.scheme === 'https' ? 'blue' : 'default'}>
          {endpoint.scheme}
        </Tag>
        <Typography.Text strong={endpoint.conflict} type={endpoint.conflict ? 'danger' : undefined}>
          {endpoint.port}
        </Typography.Text>
        <Typography.Text type="secondary" ellipsis>
          {endpoint.instance}
        </Typography.Text>
      </div>
    </Tooltip>
  );
}

/**
 * The matrix grid.
 *
 * Cell highlighting is applied through `onCell` → `className` (see `.env-matrix-cell--conflict` in
 * `index.css`), not by reaching into `.ant-table-cell`, so it survives antd internals changing.
 */
export function MatrixTable({
  services,
  countries,
  cells,
  loading,
  conflictsOnly,
}: MatrixTableProps) {
  const { t } = useI18n();

  const byKey = new Map(cells.map((cell) => [`${cell.service}|${cell.country}`, cell]));

  const rows: MatrixRow[] = services
    .map((service) => ({
      service,
      cells: Object.fromEntries(
        countries.map((country) => [country, byKey.get(`${service}|${country}`)]),
      ),
    }))
    .filter((row) => !conflictsOnly || Object.values(row.cells).some((cell) => cell?.conflict));

  const columns: TableColumnsType<MatrixRow> = [
    {
      title: t('matrix.service'),
      dataIndex: 'service',
      key: 'service',
      fixed: 'left',
      width: 150,
      render: (service: string) => <span className="env-matrix-row-header">{service}</span>,
    },
    ...countries.map((country) => ({
      title: country,
      key: country,
      width: 190,
      onCell: (row: MatrixRow) => ({
        className: [
          'env-matrix-cell',
          row.cells[country]?.conflict ? 'env-matrix-cell--conflict' : '',
        ]
          .filter(Boolean)
          .join(' '),
      }),
      render: (_: unknown, row: MatrixRow) => {
        const cell = row.cells[country];
        if (!cell) {
          return <Typography.Text type="secondary">—</Typography.Text>;
        }
        return (
          <div>
            {cell.conflict && (
              <Badge
                count={cell.conflictCount}
                size="small"
                style={{ marginBottom: 4 }}
                title={t('matrix.legendConflict')}
              />
            )}
            {cell.endpoints.map((endpoint) => (
              <EndpointLine key={endpoint.id} endpoint={endpoint} />
            ))}
          </div>
        );
      },
    })),
  ];

  return (
    <Table<MatrixRow>
      rowKey="service"
      size="small"
      bordered
      loading={loading}
      columns={columns}
      dataSource={rows}
      pagination={false}
      // Horizontal scroll for the country columns, vertical for the service rows; the header and the
      // service column stay put so a wide matrix is still navigable. Width is computed from the
      // column widths rather than 'max-content' so the last country column cannot collapse.
      // No `sticky`: scroll.y already pins the header inside the table, and combining the two
      // renders a second, offset header while the page is scrolled.
      scroll={{ x: 150 + countries.length * 190, y: 600 }}
      locale={{ emptyText: t('matrix.empty') }}
    />
  );
}
