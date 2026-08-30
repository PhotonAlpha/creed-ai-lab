import { Alert, Descriptions, Empty, Space, Tag, Typography } from 'antd';
import { HealthTag } from '../../components/HealthTag';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';
import { ANY_COUNTRY } from '../../api/types';
import type { EdgeKind, TopoEdge, TopologyModel } from './buildGraph';

interface NodeDetailProps {
  model: TopologyModel;
  selectedId: string | null;
  /** Colour per edge kind, mirrored from the graph so the legend dots match the lines on screen. */
  kindColors: Record<EdgeKind, string>;
}

/**
 * The right-hand panel. Every field here already exists on the `Endpoint` DTO the graph was built
 * from — selecting a node never issues another request.
 */
export function NodeDetail({ model, selectedId, kindColors }: NodeDetailProps) {
  const { t } = useI18n();

  if (!selectedId) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('topology.detail.empty')} />;
  }

  const links = model.edges.filter(
    (edge) => edge.source === selectedId || edge.target === selectedId,
  );

  const layerCount = Math.max(...model.combos.map((c) => c.layer), 0) + 1;

  /*
   * An app-system box, in either layout.
   *
   * Both lists are searched because the two layouts key their boxes differently — `app:CCS@2` in
   * the layered view, `app:CCS` in the clustered one — and the ids cannot collide, so the panel
   * does not need to know which layout is on screen.
   */
  const group =
    model.appGroups.find((g) => g.id === selectedId) ??
    model.clusterGroups.find((g) => g.id === selectedId);
  if (group) {
    const members = model.combos.filter((combo) => group.comboIds.includes(combo.id));
    return (
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {group.appSystem}
        </Typography.Title>
        <Descriptions column={1} size="small" colon={false}>
          <Descriptions.Item label={t('topology.detail.participants')}>
            {members.length}
          </Descriptions.Item>
          <Descriptions.Item label={t('topology.stats.nodes')}>{group.count}</Descriptions.Item>
          {group.layer !== null && (
            <Descriptions.Item label={t('topology.detail.layer')}>
              {group.layer + 1} / {layerCount}
            </Descriptions.Item>
          )}
        </Descriptions>
        <Space size={4} wrap>
          {members.map((member) => (
            <Tag key={member.id} bordered={false}>
              {member.title}
            </Tag>
          ))}
        </Space>
        {/* The box itself has no edges — the arrows belong to the participants inside it. */}
        <LinkList
          links={model.edges.filter(
            (edge) =>
              group.comboIds.includes(edge.source) || group.comboIds.includes(edge.target),
          )}
          selectedId={selectedId}
          ownIds={group.comboIds}
          model={model}
          kindColors={kindColors}
        />
      </Space>
    );
  }

  const combo = model.combos.find((c) => c.id === selectedId);
  if (combo) {
    return (
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {combo.title}
        </Typography.Title>
        <Descriptions column={1} size="small" colon={false}>
          <Descriptions.Item label={t('column.appSystem')}>{combo.appSystem}</Descriptions.Item>
          <Descriptions.Item label={t('column.country')}>
            {combo.country === ANY_COUNTRY ? t('links.anyCountry') : combo.country}
          </Descriptions.Item>
          <Descriptions.Item label={t('column.envInstance')}>{combo.envInstance}</Descriptions.Item>
          <Descriptions.Item label={t('topology.stats.nodes')}>{combo.count}</Descriptions.Item>
          <Descriptions.Item label={t('topology.detail.layer')}>
            {combo.layer + 1} / {layerCount}
            {/* A pinned participant is not where the links put it, and the panel is the only place
                that difference is visible once the box has moved. */}
            {combo.pinned && (
              <Typography.Text type="warning" style={{ fontSize: 12, marginInlineStart: 6 }}>
                {t('topology.detail.pinned', { derived: combo.derivedLayer + 1 })}
              </Typography.Text>
            )}
          </Descriptions.Item>
        </Descriptions>
        <LinkList links={links} selectedId={selectedId} model={model} kindColors={kindColors} />
      </Space>
    );
  }

  const node = model.nodeById.get(selectedId);
  if (!node) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('topology.detail.empty')} />;
  }

  const { endpoint } = node;

  // A placeholder: the wiring names this app system but the matrix has no endpoint for it here.
  if (!endpoint) {
    return (
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {model.combos.find((c) => c.id === node.comboId)?.title ?? ''}
        </Typography.Title>
        <Alert type="warning" showIcon message={t('topology.detail.placeholder')} />
        <LinkList
          links={model.edges.filter(
            (edge) => edge.source === node.comboId || edge.target === node.comboId,
          )}
          selectedId={node.comboId}
          model={model}
          kindColors={kindColors}
        />
      </Space>
    );
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space align="center" size="small" wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {endpoint.service}
        </Typography.Title>
        <HealthTag health={endpoint.health} />
      </Space>

      {endpoint.conflict && (
        <Alert
          type="error"
          showIcon
          message={t('conflict.title')}
          description={endpoint.conflictKeys.join(' · ')}
        />
      )}

      <Descriptions column={1} size="small" colon={false}>
        <Descriptions.Item label={t('column.appSystem')}>{endpoint.appSystem}</Descriptions.Item>
        <Descriptions.Item label={t('column.envInstance')}>
          {endpoint.tier} / {endpoint.envInstance} / {endpoint.country}
        </Descriptions.Item>
        <Descriptions.Item label={t('column.instance')}>{endpoint.instance}</Descriptions.Item>
        <Descriptions.Item label={t('column.host')}>
          <Typography.Text copyable style={{ fontSize: 12 }}>
            {endpoint.host}
          </Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label={t('column.ip')}>
          {endpoint.ip}:{endpoint.port}
        </Descriptions.Item>
        <Descriptions.Item label={t('topology.detail.url')}>
          <Typography.Text copyable style={{ fontSize: 12 }}>
            {endpoint.url}
          </Typography.Text>
        </Descriptions.Item>
        {endpoint.note && (
          <Descriptions.Item label={t('column.note')}>{endpoint.note}</Descriptions.Item>
        )}
      </Descriptions>

      <LinkList links={links} selectedId={selectedId} model={model} kindColors={kindColors} />
    </Space>
  );
}

function LinkList({
  links,
  selectedId,
  ownIds,
  model,
  kindColors,
}: {
  links: TopoEdge[];
  selectedId: string;
  /**
   * Ids that count as "this side" of a link besides `selectedId`.
   *
   * An app-system box is never itself an end of an arrow — its participants are — so without this
   * the list would name the participant the reader already selected instead of the far end.
   */
  ownIds?: string[];
  model: TopologyModel;
  kindColors: Record<EdgeKind, string>;
}) {
  const { t } = useI18n();
  const isOwn = (id: string) => id === selectedId || (ownIds?.includes(id) ?? false);

  const nameOf = (id: string) =>
    model.nodeById.get(id)?.endpoint?.service ??
    model.combos.find((combo) => combo.id === id)?.title ??
    id;

  return (
    <div>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {t('topology.detail.links')} · {links.length}
      </Typography.Text>
      {links.length === 0 ? (
        <div style={{ marginTop: 6 }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('topology.detail.noLinks')}
          </Typography.Text>
        </div>
      ) : (
        <Space direction="vertical" size={8} style={{ width: '100%', marginTop: 6 }}>
          {links.map((link) => (
            <div key={link.id}>
              <Space size={6} align="center">
                <span
                  style={{
                    display: 'inline-block',
                    width: 8,
                    height: 8,
                    borderRadius: 2,
                    background: kindColors[link.kind],
                  }}
                />
                <Tag bordered={false} style={{ marginInlineEnd: 0 }}>
                  {t(`topology.link.${link.kind}` as MessageKey)}
                </Tag>
                <Typography.Text strong style={{ fontSize: 12 }}>
                  {nameOf(isOwn(link.source) ? link.target : link.source)}
                </Typography.Text>
              </Space>
              {/* The reason is a full host or `ip:port`, far too long to sit on the same line as the
                  label in a 280px panel — it wrapped mid-service-name. */}
              <Typography.Text
                type="secondary"
                style={{ fontSize: 11, display: 'block', marginInlineStart: 14, wordBreak: 'break-all' }}
              >
                {link.reason}
              </Typography.Text>
            </div>
          ))}
        </Space>
      )}
    </div>
  );
}
